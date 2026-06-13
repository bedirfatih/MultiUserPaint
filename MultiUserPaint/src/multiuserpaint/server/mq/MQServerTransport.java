package multiuserpaint.server.mq;

import com.rabbitmq.client.*;
import multiuserpaint.common.*;
import multiuserpaint.mq.MQConfig;
import multiuserpaint.server.ClientSession;
import multiuserpaint.server.MessageDispatcher;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MQ-mode replacement for NIOSelector.
 *
 * Consumes messages from {@code paint.server} queue; for each message:
 *  1. Extracts sessionId + replyTo from AMQP headers.
 *  2. Finds or creates the corresponding ClientSession.
 *  3. Decodes the wire-format body into a Message.
 *  4. Dispatches to MessageDispatcher (business logic unchanged).
 *
 * Outbound delivery is done via the ClientSession.enqueue() → mqSink chain
 * which calls MQBroker.sendToClient().  Broadcasts are handled by
 * SessionRegistry using MQBroker.broadcastGlobal() / broadcastToFile().
 *
 * A keepalive sweeper thread closes sessions that have not sent a PING
 * within KEEPALIVE_TIMEOUT_SECONDS.
 */
public class MQServerTransport {
    private static final Logger LOG = Logger.getLogger(MQServerTransport.class.getName());

    private final MQBroker          broker;
    private final MessageDispatcher dispatcher;
    private final SessionRegistry   registry;
    private final AtomicInteger     sessionIdCounter = new AtomicInteger(1);

    private ScheduledExecutorService keepaliveScheduler;
    private volatile boolean running = false;

    public MQServerTransport(MQBroker broker,
                              MessageDispatcher dispatcher,
                              SessionRegistry registry) {
        this.broker     = broker;
        this.dispatcher = dispatcher;
        this.registry   = registry;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        running = true;

        Channel channel = broker.getChannel();

        // One unacked message at a time keeps ordering simple
        channel.basicQos(1);

        channel.basicConsume(MQConfig.SERVER_QUEUE, /*autoAck*/ false,
                new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag,
                                               Envelope envelope,
                                               AMQP.BasicProperties props,
                                               byte[] body) throws IOException {
                        long tag = envelope.getDeliveryTag();
                        try {
                            processDelivery(props, body);
                            channel.basicAck(tag, false);
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Message processing error – discarding", e);
                            channel.basicNack(tag, false, /*requeue*/ false);
                        }
                    }
                });

        // Keepalive sweeper: check every 30 s, close idle sessions after 90 s
        keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MQ-Keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveScheduler.scheduleAtFixedRate(
                this::checkKeepaliveTimeouts,
                ProtocolConstants.PING_INTERVAL_SECONDS,
                ProtocolConstants.PING_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        LOG.info("MQServerTransport started – consuming from " + MQConfig.SERVER_QUEUE);
    }

    public void stop() {
        running = false;
        if (keepaliveScheduler != null) keepaliveScheduler.shutdownNow();
    }

    // ── Message processing ────────────────────────────────────────────────────

    private void processDelivery(AMQP.BasicProperties props, byte[] body) throws IOException {
        Map<String, Object> headers = props.getHeaders();

        // Extract replyTo (client queue name)
        String replyTo = null;
        if (headers != null && headers.get(MQConfig.HDR_REPLY_TO) != null) {
            replyTo = headers.get(MQConfig.HDR_REPLY_TO).toString();
        }
        if (replyTo == null) replyTo = props.getReplyTo();
        if (replyTo == null) {
            LOG.warning("Received message with no replyTo – ignoring");
            return;
        }

        // Extract sessionId header (absent before LOGIN_OK)
        Integer sessionId = null;
        if (headers != null && headers.get(MQConfig.HDR_SESSION_ID) != null) {
            Object sid = headers.get(MQConfig.HDR_SESSION_ID);
            if (sid instanceof Number) sessionId = ((Number) sid).intValue();
        }

        // Decode wire-format frame
        Message msg = decodeFrame(body);

        // Find or create session
        ClientSession session = resolveSession(sessionId, replyTo);

        // Update keepalive clock on every received message
        session.updatePingTime();

        // Dispatch (business logic unchanged)
        boolean keepOpen = dispatcher.dispatch(session, msg);
        if (!keepOpen) {
            evictSession(session);
        }
    }

    /**
     * Locate an existing session by id, or create a fresh pre-login session
     * bound to replyTo.
     */
    private ClientSession resolveSession(Integer sessionId, String replyTo) {
        if (sessionId != null) {
            ClientSession existing = registry.getBySessionId(sessionId);
            if (existing != null) {
                // Refresh replyTo in case client reconnected with a new queue name
                if (!replyTo.equals(existing.getMqReplyTo())) {
                    existing.setMqReplyTo(replyTo);
                    existing.setMqSink(buildSink(replyTo));
                }
                return existing;
            }
        }

        // New (pre-login) session
        int newId = sessionIdCounter.getAndIncrement();
        ClientSession session = new ClientSession(newId, replyTo);
        session.setMqSink(buildSink(replyTo));
        registry.register(session);
        LOG.info("New MQ session " + newId + " replyTo=" + replyTo);
        return session;
    }

    /** Build the outbound sink that publishes directly to the client queue. */
    private java.util.function.Consumer<byte[]> buildSink(String replyTo) {
        return frame -> broker.sendToClient(replyTo, frame);
    }

    // ── Keepalive ─────────────────────────────────────────────────────────────

    private void checkKeepaliveTimeouts() {
        if (!running) return;
        long now       = System.currentTimeMillis();
        long timeoutMs = ProtocolConstants.KEEPALIVE_TIMEOUT_SECONDS * 1000L;

        for (ClientSession session : registry.getAllSessions()) {
            if (session.isLoggedIn()
                    && now - session.getLastPingTime() > timeoutMs) {
                LOG.warning("Keepalive timeout for " + session.getUsername());
                evictSession(session);
            }
        }
    }

    private void evictSession(ClientSession session) {
        if (session.isLoggedIn() && session.getUsername() != null) {
            byte[] leaveMsg = MessageEncoder.encodeUserLeave(session.getUsername());
            // Broadcast USER_LEAVE to all clients via global fanout
            broker.broadcastGlobal(leaveMsg);
            LOG.info("User disconnected (MQ timeout): " + session.getUsername());
        }
        session.transitionTo(FSMState.CLOSING);
        registry.unregister(session.getSessionId());
    }

    // ── Wire-format decode (mirrors MessageFramer but for complete byte[]) ────

    private static Message decodeFrame(byte[] body) throws IOException {
        if (body.length < ProtocolConstants.HEADER_SIZE) {
            throw new IOException("Frame too short: " + body.length);
        }

        int hi = body[0] & 0xFF;
        int lo = body[1] & 0xFF;
        if (((hi << 8) | lo) != (ProtocolConstants.MAGIC & 0xFFFF)) {
            throw new IOException("Bad magic: 0x"
                    + Integer.toHexString((hi << 8) | lo));
        }

        // body[2] = version (accepted without validation for forward compat)
        byte typeCode  = body[3];
        int  payloadLen = ((body[4] & 0xFF) << 24) | ((body[5] & 0xFF) << 16)
                        | ((body[6] & 0xFF) <<  8) |  (body[7] & 0xFF);

        if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new IOException("Payload out of range: " + payloadLen);
        }
        if (body.length < ProtocolConstants.HEADER_SIZE + payloadLen) {
            throw new IOException("Frame body too short for declared payload length");
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(body, ProtocolConstants.HEADER_SIZE, payload, 0, payloadLen);

        MessageType type;
        try {
            type = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "Unknown type: 0x" + Integer.toHexString(typeCode & 0xFF));
        }

        return Message.of(type, payload);
    }
}
