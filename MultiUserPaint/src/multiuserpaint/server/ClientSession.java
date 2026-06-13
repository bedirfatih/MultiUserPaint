package multiuserpaint.server;

import multiuserpaint.common.FSMState;
import multiuserpaint.common.ProtocolConstants;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Holds all state for one connected client.
 *
 * Frame assembly uses a two-phase approach to handle messages larger than the
 * small read buffer:
 *   Phase 1: accumulate HEADER_SIZE bytes in headerBuf
 *   Phase 2: once payload length is known, allocate payloadBuf and fill it
 *
 * In MQ mode: channel is null; mqReplyTo holds the client's reply queue name;
 * mqSink replaces writeQueue for outbound data.
 */
public class ClientSession {
    private final int sessionId;
    private final SocketChannel channel;  // null in MQ mode

    /** MQ mode: the client's exclusive reply-queue name. */
    private String mqReplyTo;

    /**
     * Pluggable outbound sink.
     * Socket mode default: adds to writeQueue.
     * MQ mode: set by MQServerTransport to publish via broker.
     */
    private Consumer<byte[]> mqSink = null;

    private FSMState state = FSMState.HANDSHAKE;
    private String username = null;
    private long lastPingTime = System.currentTimeMillis();

    // ---- Two-phase frame assembly ----
    /** Small ring buffer for raw incoming bytes from the channel. */
    public final ByteBuffer readBuffer =
        ByteBuffer.allocate(ProtocolConstants.READ_BUFFER_SIZE);

    /** Accumulates the 8-byte header. */
    final ByteBuffer headerBuf = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE);

    /** Allocated once the payload length is known; null in Phase 1. */
    ByteBuffer payloadBuf = null;

    /** Type byte extracted from the header; valid only in Phase 2. */
    byte pendingTypeCode = 0;

    // ---- Write queue ----
    public final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    // ---- Open files and clipboard ----
    private final Set<Integer> openFileIds = new HashSet<>();
    private byte[] clipboardData = null;
    private int clipboardWidth = 0;
    private int clipboardHeight = 0;

    public ClientSession(int sessionId, SocketChannel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
    }

    /** MQ-mode constructor: no NIO channel. */
    public ClientSession(int sessionId, String mqReplyTo) {
        this.sessionId = sessionId;
        this.channel = null;
        this.mqReplyTo = mqReplyTo;
    }

    // -------------------------------------------------------------------------
    // FSM
    // -------------------------------------------------------------------------

    public FSMState getState() { return state; }

    public void transitionTo(FSMState newState) { this.state = newState; }

    public boolean isLoggedIn() {
        return state == FSMState.ACTIVE || state == FSMState.SAVING;
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    public int getSessionId()         { return sessionId; }
    public SocketChannel getChannel() { return channel; }
    public String getUsername()       { return username; }
    public void setUsername(String u) { this.username = u; }

    public String getMqReplyTo()         { return mqReplyTo; }
    public void   setMqReplyTo(String q) { this.mqReplyTo = q; }

    /**
     * Set the outbound sink used in MQ mode.
     * The sink receives a fully-encoded wire-format frame and delivers it
     * to the client (e.g. via AMQP basicPublish).
     */
    public void setMqSink(Consumer<byte[]> sink) { this.mqSink = sink; }

    /**
     * Unified outbound dispatch.
     * Socket mode: adds frame to writeQueue (NIOSelector drains it).
     * MQ mode: delegates immediately to the installed mqSink.
     */
    public void enqueue(byte[] data) {
        if (mqSink != null) {
            mqSink.accept(data);
        } else {
            writeQueue.offer(ByteBuffer.wrap(data));
        }
    }

    // -------------------------------------------------------------------------
    // Open files
    // -------------------------------------------------------------------------

    public void addOpenFile(int fileId)    { openFileIds.add(fileId); }
    public void removeOpenFile(int fileId) { openFileIds.remove(fileId); }
    public boolean hasFileOpen(int fileId) { return openFileIds.contains(fileId); }
    public Set<Integer> getOpenFileIds()   { return openFileIds; }

    // -------------------------------------------------------------------------
    // Clipboard
    // -------------------------------------------------------------------------

    public void setClipboard(byte[] data, int width, int height) {
        this.clipboardData  = data;
        this.clipboardWidth  = width;
        this.clipboardHeight = height;
    }

    public byte[] getClipboardData()  { return clipboardData; }
    public int getClipboardWidth()    { return clipboardWidth; }
    public int getClipboardHeight()   { return clipboardHeight; }
    public boolean hasClipboard()     { return clipboardData != null; }

    // -------------------------------------------------------------------------
    // Keepalive
    // -------------------------------------------------------------------------

    public void updatePingTime()   { lastPingTime = System.currentTimeMillis(); }
    public long getLastPingTime()  { return lastPingTime; }

    @Override
    public String toString() {
        return "ClientSession{id=" + sessionId + ", user=" + username + ", state=" + state + "}";
    }
}
