package multiuserpaint.server.mq;

import com.rabbitmq.client.*;
import multiuserpaint.mq.MQConfig;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side AMQP connection manager.
 *
 * Responsibilities:
 *  - Declare the server request queue and global fanout exchange at startup.
 *  - Provide thread-safe send / broadcast helpers used by SessionRegistry.
 *  - Lazily declare per-file fanout exchanges on first broadcast.
 *
 * Thread safety: all publish methods synchronize on the single channel because
 * the RabbitMQ Java client Channel is NOT thread-safe.
 */
public class MQBroker {
    private static final Logger LOG = Logger.getLogger(MQBroker.class.getName());

    private Connection connection;
    private Channel   channel;
    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();

    private final String host;
    private final int    port;

    public MQBroker(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Connect to the broker and declare static topology. */
    public void connect() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setVirtualHost(MQConfig.VHOST);
        factory.setUsername(MQConfig.USER);
        factory.setPassword(MQConfig.PASSWORD);
        factory.setAutomaticRecoveryEnabled(true);

        connection = factory.newConnection("MultiUserPaint-Server");
        channel    = connection.createChannel();

        // Server inbound queue
        channel.queueDeclare(MQConfig.SERVER_QUEUE,
                /*durable*/ false, /*exclusive*/ false, /*autoDelete*/ false, null);

        // Global fanout exchange (USER_JOIN / USER_LEAVE / FILE_LIST_RESP)
        channel.exchangeDeclare(MQConfig.EXCHANGE_GLOBAL,
                BuiltinExchangeType.FANOUT, /*durable*/ false, /*autoDelete*/ false, null);
        declaredExchanges.add(MQConfig.EXCHANGE_GLOBAL);

        LOG.info("MQBroker connected to " + host + ":" + port);
    }

    // ── Send / broadcast helpers ──────────────────────────────────────────────

    /**
     * Publish a frame directly to one client's personal queue.
     * Used for point-to-point responses (LOGIN_OK, FILE_OPEN_DATA, …).
     */
    public synchronized void sendToClient(String replyTo, byte[] frame) {
        try {
            channel.basicPublish("", replyTo, null, frame);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "sendToClient failed (queue=" + replyTo + ")", e);
        }
    }

    /**
     * Publish to the global fanout exchange.
     * All subscribed clients receive the message (USER_JOIN, USER_LEAVE, file list).
     */
    public synchronized void broadcastGlobal(byte[] frame) {
        try {
            channel.basicPublish(MQConfig.EXCHANGE_GLOBAL, "", null, frame);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "broadcastGlobal failed", e);
        }
    }

    /**
     * Publish to the per-file fanout exchange.
     * All clients that have opened the file receive the message (DRAW_BROADCAST, CANVAS_UPDATE).
     * The exchange is declared lazily on first use and auto-deleted when empty.
     */
    public synchronized void broadcastToFile(int fileId, byte[] frame) {
        String exchange = MQConfig.fileExchange(fileId);
        try {
            ensureFileExchange(exchange);
            channel.basicPublish(exchange, "", null, frame);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "broadcastToFile failed (file=" + fileId + ")", e);
        }
    }

    /** Lazily declare a per-file fanout exchange (idempotent). */
    public synchronized void ensureFileExchange(String exchange) throws IOException {
        if (declaredExchanges.add(exchange)) {
            channel.exchangeDeclare(exchange,
                    BuiltinExchangeType.FANOUT,
                    /*durable*/ false,
                    /*autoDelete*/ true,
                    null);
        }
    }

    public Channel getChannel() { return channel; }

    public void close() {
        try {
            if (channel != null && channel.isOpen())     channel.close();
            if (connection != null && connection.isOpen()) connection.close();
            LOG.info("MQBroker connection closed.");
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error closing MQBroker", e);
        }
    }
}
