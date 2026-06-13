package multiuserpaint.client.network;

import com.rabbitmq.client.*;
import multiuserpaint.common.*;
import multiuserpaint.mq.MQConfig;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side AMQP subscriber.
 *
 * Consumes from the client's exclusive reply queue and, for each received
 * frame, decodes it into a Message and dispatches on the Swing EDT – exactly
 * matching the behaviour of the original blocking NetworkReader.
 *
 * Lifecycle:
 *   1. start(channel, clientQueueName, globalExchange) – subscribe to queues
 *   2. bindToFileExchange(fileId) – called after FILE_OPEN_DATA is received
 *   3. unbindFromFileExchange(fileId) – called when a file is closed
 *   4. stop() – cancel consumer
 */
public class MQNetworkReader {
    private static final Logger LOG = Logger.getLogger(MQNetworkReader.class.getName());

    private final Consumer<Message> onMessage;
    private final Runnable          onDisconnect;

    private Channel channel;
    private String  clientQueueName;
    private String  consumerTag;
    private volatile boolean running = false;

    public MQNetworkReader(Consumer<Message> onMessage, Runnable onDisconnect) {
        this.onMessage    = onMessage;
        this.onDisconnect = onDisconnect;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Declare the exclusive client queue, bind it to the global fanout exchange,
     * and start consuming.
     */
    public void start(Channel channel, String clientQueueName) throws IOException {
        this.channel         = channel;
        this.clientQueueName = clientQueueName;
        this.running         = true;

        // Declare exclusive auto-delete queue (survives until this client disconnects)
        channel.queueDeclare(clientQueueName,
                /*durable*/ false, /*exclusive*/ true, /*autoDelete*/ true, null);

        // Ensure global fanout exchange exists and bind the client queue to it
        channel.exchangeDeclare(MQConfig.EXCHANGE_GLOBAL,
                BuiltinExchangeType.FANOUT, /*durable*/ false, /*autoDelete*/ false, null);
        channel.queueBind(clientQueueName, MQConfig.EXCHANGE_GLOBAL, "");

        consumerTag = channel.basicConsume(clientQueueName, /*autoAck*/ true,
                new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String tag,
                                               Envelope env,
                                               AMQP.BasicProperties props,
                                               byte[] body) {
                        if (!running) return;
                        try {
                            Message msg = decodeFrame(body);
                            SwingUtilities.invokeLater(() -> onMessage.accept(msg));
                        } catch (IOException e) {
                            LOG.log(Level.WARNING, "Frame decode error", e);
                        }
                    }

                    @Override
                    public void handleCancelOk(String ct) {
                        running = false;
                        SwingUtilities.invokeLater(onDisconnect);
                    }

                    @Override
                    public void handleShutdownSignal(String ct, ShutdownSignalException sig) {
                        if (running) {
                            running = false;
                            LOG.warning("MQ connection lost: " + sig.getMessage());
                            SwingUtilities.invokeLater(onDisconnect);
                        }
                    }
                });

        LOG.info("MQNetworkReader started on queue: " + clientQueueName);
    }

    /** Bind client queue to the per-file fanout exchange for file-scoped events. */
    public void bindToFileExchange(int fileId) {
        if (!running) return;
        String exchange = MQConfig.fileExchange(fileId);
        try {
            channel.exchangeDeclare(exchange,
                    BuiltinExchangeType.FANOUT, /*durable*/ false, /*autoDelete*/ true, null);
            channel.queueBind(clientQueueName, exchange, "");
            LOG.info("Bound to file exchange: " + exchange);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "bindToFileExchange failed for " + fileId, e);
        }
    }

    /** Unbind client queue from a per-file fanout exchange when the file is closed. */
    public void unbindFromFileExchange(int fileId) {
        if (!running) return;
        String exchange = MQConfig.fileExchange(fileId);
        try {
            channel.queueUnbind(clientQueueName, exchange, "");
            LOG.fine("Unbound from file exchange: " + exchange);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "unbindFromFileExchange failed for " + fileId, e);
        }
    }

    public void stop() {
        running = false;
        if (channel != null && consumerTag != null) {
            try { channel.basicCancel(consumerTag); } catch (IOException ignored) {}
        }
    }

    // ── Wire-format decode ────────────────────────────────────────────────────

    private static Message decodeFrame(byte[] body) throws IOException {
        if (body.length < ProtocolConstants.HEADER_SIZE) {
            throw new IOException("Frame too short: " + body.length);
        }

        int hi = body[0] & 0xFF;
        int lo = body[1] & 0xFF;
        if (((hi << 8) | lo) != (ProtocolConstants.MAGIC & 0xFFFF)) {
            throw new IOException("Bad magic: 0x" + Integer.toHexString((hi << 8) | lo));
        }

        // body[2] = version
        byte typeCode   = body[3];
        int  payloadLen = ((body[4] & 0xFF) << 24) | ((body[5] & 0xFF) << 16)
                        | ((body[6] & 0xFF) <<  8) |  (body[7] & 0xFF);

        if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new IOException("Payload out of range: " + payloadLen);
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(body, ProtocolConstants.HEADER_SIZE, payload, 0, payloadLen);

        MessageType type;
        try {
            type = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown type 0x" + Integer.toHexString(typeCode & 0xFF));
        }

        return Message.of(type, payload);
    }
}
