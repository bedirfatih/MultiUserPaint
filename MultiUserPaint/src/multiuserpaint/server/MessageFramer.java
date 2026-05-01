package multiuserpaint.server;

import multiuserpaint.common.Message;
import multiuserpaint.common.MessageType;
import multiuserpaint.common.ProtocolConstants;

import java.nio.ByteBuffer;

/**
 * Two-phase frame assembler for NIO sessions.
 *
 * Phase 1 — header: fills session.headerBuf (8 bytes).
 * Phase 2 — payload: once header is complete, allocates session.payloadBuf
 *            and fills it.  When payloadBuf is full, returns a complete Message.
 *
 * Called only from the NIO thread; no synchronisation needed.
 */
public class MessageFramer {

    private static final int MAGIC_HI = (ProtocolConstants.MAGIC >> 8) & 0xFF;
    private static final int MAGIC_LO = ProtocolConstants.MAGIC & 0xFF;

    private MessageFramer() {}

    /**
     * Feed bytes from {@code src} into the session's frame assembler.
     * May return multiple complete messages per call; the caller should loop.
     *
     * @param session the session whose buffers will be updated
     * @param src     the ByteBuffer that was just read from the channel
     *                (already flipped to read mode)
     * @return the next complete Message, or null if more bytes are needed
     * @throws ProtocolException on bad magic, unknown type, or oversized payload
     */
    public static Message feed(ClientSession session, ByteBuffer src)
            throws ProtocolException {

        // ---- Phase 1: fill the 8-byte header ----
        if (session.payloadBuf == null) {
            transfer(src, session.headerBuf);

            if (session.headerBuf.hasRemaining()) {
                return null; // header not yet complete
            }

            // Header is full — parse it
            session.headerBuf.flip();

            int hi = session.headerBuf.get() & 0xFF;
            int lo = session.headerBuf.get() & 0xFF;
            if (hi != MAGIC_HI || lo != MAGIC_LO) {
                throw new ProtocolException(
                    "Invalid magic: 0x" + Integer.toHexString(hi) + Integer.toHexString(lo));
            }

            session.headerBuf.get(); // version — consumed, not validated
            session.pendingTypeCode = session.headerBuf.get(); // type

            int payloadLen = session.headerBuf.getInt(); // 4-byte big-endian length
            session.headerBuf.clear(); // ready for next header

            if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
                throw new ProtocolException("Payload too large: " + payloadLen);
            }

            // Allocate payload buffer (may be 0 bytes for no-payload messages)
            session.payloadBuf = ByteBuffer.allocate(payloadLen);
        }

        // ---- Phase 2: fill the payload ----
        transfer(src, session.payloadBuf);

        if (session.payloadBuf.hasRemaining()) {
            return null; // payload not yet complete
        }

        // Payload is complete — build the Message
        byte[] payload = session.payloadBuf.array();
        session.payloadBuf = null; // reset for next frame

        MessageType type;
        try {
            type = MessageType.fromCode(session.pendingTypeCode);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(
                "Unknown message type: 0x" + Integer.toHexString(session.pendingTypeCode & 0xFF));
        }

        return Message.of(type, payload);
    }

    /**
     * Copy as many bytes as possible from {@code src} into {@code dst}
     * without exceeding either buffer's remaining capacity.
     */
    private static void transfer(ByteBuffer src, ByteBuffer dst) {
        int n = Math.min(src.remaining(), dst.remaining());
        if (n == 0) return;
        // Temporarily limit src so we don't overshoot dst
        int oldLimit = src.limit();
        src.limit(src.position() + n);
        dst.put(src);
        src.limit(oldLimit);
    }

    public static class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
    }
}
