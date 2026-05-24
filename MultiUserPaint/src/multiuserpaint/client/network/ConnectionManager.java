package multiuserpaint.client.network;

import multiuserpaint.common.*;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages TCP connection lifecycle and provides send/receive API.
 */
public class ConnectionManager {
    private static final Logger LOG = Logger.getLogger(ConnectionManager.class.getName());

    private Socket socket;
    private NetworkReader reader;
    private NetworkWriter writer;
    private ScheduledExecutorService pingScheduler;

    private FSMState state = FSMState.DISCONNECTED;
    private String username;
    private int sessionId;

    private final Consumer<Message> messageHandler;
    private final Runnable disconnectHandler;

    public ConnectionManager(Consumer<Message> messageHandler, Runnable disconnectHandler) {
        this.messageHandler = messageHandler;
        this.disconnectHandler = disconnectHandler;
    }

    public synchronized void connect(String host, int port) throws IOException {
        if (state != FSMState.DISCONNECTED) throw new IllegalStateException("Already connected");
        state = FSMState.CONNECTING;

        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);

        writer = new NetworkWriter();
        writer.start(socket.getOutputStream());

        reader = new NetworkReader(messageHandler, this::handleDisconnect);
        reader.start(socket.getInputStream());

        state = FSMState.LOGGING_IN;
        LOG.info("Connected to " + host + ":" + port);

        // Otomatik PING — her 30 saniyede bir sunucuya gönderilir
        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PingScheduler");
            t.setDaemon(true);
            return t;
        });
        pingScheduler.scheduleAtFixedRate(() -> {
            if (state == FSMState.CONNECTED) sendPing();
        }, 30, 30, TimeUnit.SECONDS);
    }

    public synchronized void disconnect() {
        if (state == FSMState.DISCONNECTED) return;
        state = FSMState.DISCONNECTED;

        if (pingScheduler != null) pingScheduler.shutdownNow();
        if (writer != null) writer.stop();
        if (reader != null) reader.stop();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        LOG.info("Disconnected");
    }

    /** Send a raw wire-format byte array to the server. Thread-safe. */
    public void send(byte[] data) {
        if (writer != null) writer.send(data);
    }

    // -------------------------------------------------------------------------
    // Convenience send methods
    // -------------------------------------------------------------------------

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

    public void sendClipboardCopy(int fileId, int rx, int ry, int rw, int rh, byte[] pixelData) {
        send(MessageEncoder.encodeClipboardCopy(fileId, rx, ry, rw, rh, pixelData));
    }

    public void sendClipboardCut(int fileId, int rx, int ry, int rw, int rh, byte[] pixelData) {
        send(MessageEncoder.encodeClipboardCut(fileId, rx, ry, rw, rh, pixelData));
    }

    public void sendClipboardPasteReq(int fileId, int pasteX, int pasteY) {
        send(MessageEncoder.encodeClipboardPasteReq(fileId, pasteX, pasteY));
    }

    public void sendCanvasSnapshotData(int fileId, int width, int height, byte[] pixelData) {
        send(MessageEncoder.encodeCanvasSnapshotData(fileId, width, height, pixelData));
    }

    public void sendPing() {
        send(MessageEncoder.encodePing());
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public FSMState getState()  { return state; }
    public String getUsername() { return username; }
    public int getSessionId()   { return sessionId; }
    public boolean isConnected(){ return state == FSMState.CONNECTED; }

    public void onLoginOk(int sessionId) {
        this.sessionId = sessionId;
        this.state = FSMState.CONNECTED;
    }

    private void handleDisconnect() {
        state = FSMState.DISCONNECTED;
        disconnectHandler.run();
    }
}
