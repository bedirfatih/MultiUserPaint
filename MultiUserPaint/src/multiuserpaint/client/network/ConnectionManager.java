package multiuserpaint.client.network;

import com.rabbitmq.client.*;
import multiuserpaint.common.*;
import multiuserpaint.mq.MQConfig;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages connection lifecycle and provides the send/receive API for the GUI.
 *
 * Supports two transport modes selected at connect time:
 *
 *   Socket mode – original TCP + NIO path
 *     connect(host, tcpPort)
 *
 *   MQ mode – RabbitMQ AMQP path
 *     connectMQ(brokerHost)   (default port 5672)
 *     connectMQ(host, port)
 *
 * All other API methods (sendLoginReq, sendDrawEvent, …) work identically
 * in both modes; they call send(byte[]) which routes through the active
 * transport.
 *
 * After receiving FILE_OPEN_DATA the caller must invoke
 * notifyFileOpened(fileId) so that the MQ reader subscribes to the
 * per-file fanout exchange.  Likewise notifyFileClosed(fileId) triggers
 * an unsubscribe.  These are no-ops in socket mode.
 */
public class ConnectionManager {
    private static final Logger LOG = Logger.getLogger(ConnectionManager.class.getName());

    // ── Socket mode fields ────────────────────────────────────────────────────
    private Socket        socket;
    private NetworkReader reader;
    private NetworkWriter writer;

    // ── MQ mode fields ────────────────────────────────────────────────────────
    private Connection        mqConnection;
    private Channel           mqChannel;
    private MQNetworkReader   mqReader;
    private MQNetworkWriter   mqWriter;
    private String            clientQueueName;

    // ── Common fields ─────────────────────────────────────────────────────────
    private ScheduledExecutorService pingScheduler;
    private FSMState state    = FSMState.DISCONNECTED;
    private String   username;
    private int      sessionId;
    private boolean  mqMode   = false;

    private final Consumer<Message> messageHandler;
    private final Runnable          disconnectHandler;

    public ConnectionManager(Consumer<Message> messageHandler,
                              Runnable disconnectHandler) {
        this.messageHandler    = messageHandler;
        this.disconnectHandler = disconnectHandler;
    }

    // ── Socket connect ────────────────────────────────────────────────────────

    public synchronized void connect(String host, int port) throws IOException {
        if (state != FSMState.DISCONNECTED)
            throw new IllegalStateException("Already connected");
        state   = FSMState.CONNECTING;
        mqMode  = false;

        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);

        writer = new NetworkWriter();
        writer.start(socket.getOutputStream());

        reader = new NetworkReader(messageHandler, this::handleDisconnect);
        reader.start(socket.getInputStream());

        state = FSMState.LOGGING_IN;
        LOG.info("Socket connected to " + host + ":" + port);

        startPingScheduler();
    }

    // ── MQ connect ────────────────────────────────────────────────────────────

    public synchronized void connectMQ(String brokerHost) throws IOException, TimeoutException {
        connectMQ(brokerHost, MQConfig.DEFAULT_AMQP_PORT);
    }

    public synchronized void connectMQ(String brokerHost, int amqpPort)
            throws IOException, TimeoutException {
        if (state != FSMState.DISCONNECTED)
            throw new IllegalStateException("Already connected");
        state  = FSMState.CONNECTING;
        mqMode = true;

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(brokerHost);
        factory.setPort(amqpPort);
        factory.setVirtualHost(MQConfig.VHOST);
        factory.setUsername(MQConfig.USER);
        factory.setPassword(MQConfig.PASSWORD);
        factory.setAutomaticRecoveryEnabled(true);

        mqConnection = factory.newConnection("MultiUserPaint-Client");
        mqChannel    = mqConnection.createChannel();

        // Unique client queue name (survives until connection closes)
        clientQueueName = MQConfig.clientQueue(UUID.randomUUID().toString());

        // Also declare server queue so it exists before the client tries to publish
        mqChannel.queueDeclare(MQConfig.SERVER_QUEUE,
                false, false, false, null);

        mqReader = new MQNetworkReader(messageHandler, this::handleDisconnect);
        mqReader.start(mqChannel, clientQueueName);

        mqWriter = new MQNetworkWriter();
        mqWriter.start(mqChannel, clientQueueName);

        state = FSMState.LOGGING_IN;
        LOG.info("MQ connected to " + brokerHost + ":" + amqpPort
                + " queue=" + clientQueueName);

        startPingScheduler();
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    public synchronized void disconnect() {
        if (state == FSMState.DISCONNECTED) return;
        state = FSMState.DISCONNECTED;

        if (pingScheduler != null) pingScheduler.shutdownNow();

        if (mqMode) {
            if (mqWriter != null) mqWriter.stop();
            if (mqReader != null) mqReader.stop();
            try {
                if (mqChannel    != null && mqChannel.isOpen())    mqChannel.close();
                if (mqConnection != null && mqConnection.isOpen()) mqConnection.close();
            } catch (Exception ignored) {}
        } else {
            if (writer != null) writer.stop();
            if (reader != null) reader.stop();
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
        LOG.info("Disconnected");
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /** Send a raw wire-format byte array to the server. Thread-safe. */
    public void send(byte[] data) {
        if (mqMode) {
            if (mqWriter != null) mqWriter.send(data);
        } else {
            if (writer  != null) writer.send(data);
        }
    }

    // ── File open/close notification (MQ only) ────────────────────────────────

    /**
     * Bind the client's queue to the per-file fanout exchange so that
     * DRAW_BROADCAST and CANVAS_UPDATE messages for this file are received.
     * No-op in socket mode.
     */
    public void notifyFileOpened(int fileId) {
        if (mqMode && mqReader != null) mqReader.bindToFileExchange(fileId);
    }

    /**
     * Unbind from the per-file fanout exchange when the file is closed.
     * No-op in socket mode.
     */
    public void notifyFileClosed(int fileId) {
        if (mqMode && mqReader != null) mqReader.unbindFromFileExchange(fileId);
    }

    // ── Convenience send methods ──────────────────────────────────────────────

    public void sendLoginReq(String username) {
        this.username = username;
        send(MessageEncoder.encodeLoginReq(username));
    }

    public void sendDrawEvent(int fileId, byte tool, int color, short stroke,
                              int x1, int y1, int x2, int y2) {
        send(MessageEncoder.encodeDrawEvent(fileId, tool, color, stroke, x1, y1, x2, y2));
    }

    public void sendFileCreateReq(String filename, int width, int height) {
        send(MessageEncoder.encodeFileCreateReq(filename, width, height));
    }

    public void sendFileOpenReq(int fileId) {
        send(MessageEncoder.encodeFileOpenReq(fileId));
    }

    public void sendFileSaveReq(int fileId, byte[] pixelData) {
        send(MessageEncoder.encodeFileSaveReq(fileId, pixelData));
    }

    public void sendFileDeleteReq(int fileId) {
        send(MessageEncoder.encodeFileDeleteReq(fileId));
    }

    public void sendFileListReq() {
        send(MessageEncoder.encodeFileListReq());
    }

    public void sendClipboardCopy(int fileId, int rx, int ry, int rw, int rh,
                                   byte[] pixelData) {
        send(MessageEncoder.encodeClipboardCopy(fileId, rx, ry, rw, rh, pixelData));
    }

    public void sendClipboardCut(int fileId, int rx, int ry, int rw, int rh,
                                  byte[] pixelData) {
        send(MessageEncoder.encodeClipboardCut(fileId, rx, ry, rw, rh, pixelData));
    }

    public void sendClipboardPasteReq(int fileId, int pasteX, int pasteY) {
        send(MessageEncoder.encodeClipboardPasteReq(fileId, pasteX, pasteY));
    }

    public void sendCanvasSnapshotData(int fileId, int width, int height,
                                        byte[] pixelData) {
        send(MessageEncoder.encodeCanvasSnapshotData(fileId, width, height, pixelData));
    }

    public void sendPing() {
        send(MessageEncoder.encodePing());
    }

    // ── State ─────────────────────────────────────────────────────────────────

    public FSMState getState()   { return state; }
    public String getUsername()  { return username; }
    public int getSessionId()    { return sessionId; }
    public boolean isConnected() { return state == FSMState.CONNECTED; }
    public boolean isMQMode()    { return mqMode; }

    public void onLoginOk(int sessionId) {
        this.sessionId = sessionId;
        this.state     = FSMState.CONNECTED;
        if (mqMode && mqWriter != null) {
            mqWriter.setSessionId(sessionId);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void handleDisconnect() {
        state = FSMState.DISCONNECTED;
        disconnectHandler.run();
    }

    private void startPingScheduler() {
        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PingScheduler");
            t.setDaemon(true);
            return t;
        });
        pingScheduler.scheduleAtFixedRate(() -> {
            if (state == FSMState.CONNECTED) sendPing();
        }, ProtocolConstants.PING_INTERVAL_SECONDS,
           ProtocolConstants.PING_INTERVAL_SECONDS,
           TimeUnit.SECONDS);
    }
}
