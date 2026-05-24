package multiuserpaint.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Converts typed parameters into wire-format byte arrays.
 * Wire format: [MAGIC 2B][VER 1B][TYPE 1B][PAYLOAD_LEN 4B][PAYLOAD NB]
 */
public class MessageEncoder {

    private MessageEncoder() {}

    // -------------------------------------------------------------------------
    // Low-level frame builder
    // -------------------------------------------------------------------------

    public static byte[] encode(MessageType type, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        byte[] frame = new byte[ProtocolConstants.HEADER_SIZE + payloadLen];
        // MAGIC (big-endian)
        frame[0] = (byte) ((ProtocolConstants.MAGIC >> 8) & 0xFF);
        frame[1] = (byte) (ProtocolConstants.MAGIC & 0xFF);
        // VERSION
        frame[2] = ProtocolConstants.VERSION;
        // TYPE
        frame[3] = type.getCode();
        // PAYLOAD LENGTH (big-endian int)
        frame[4] = (byte) ((payloadLen >> 24) & 0xFF);
        frame[5] = (byte) ((payloadLen >> 16) & 0xFF);
        frame[6] = (byte) ((payloadLen >> 8) & 0xFF);
        frame[7] = (byte) (payloadLen & 0xFF);
        // PAYLOAD
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, 0, frame, ProtocolConstants.HEADER_SIZE, payloadLen);
        }
        return frame;
    }

    // -------------------------------------------------------------------------
    // Helper writers (used internally)
    // -------------------------------------------------------------------------

    public static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    // -------------------------------------------------------------------------
    // Login messages
    // -------------------------------------------------------------------------

    public static byte[] encodeLoginReq(String username) {
        return buildWith(type -> writeString(type, username), MessageType.LOGIN_REQ);
    }

    public static byte[] encodeLoginOk(int sessionId) {
        return buildWith(out -> {
            out.writeInt(sessionId);
            out.writeLong(System.currentTimeMillis());
        }, MessageType.LOGIN_OK);
    }

    public static byte[] encodeLoginErr(byte errorCode, String message) {
        return buildWith(out -> {
            out.writeByte(errorCode);
            writeString(out, message);
        }, MessageType.LOGIN_ERR);
    }

    // -------------------------------------------------------------------------
    // File messages
    // -------------------------------------------------------------------------

    public static byte[] encodeFileCreateReq(String filename, int width, int height) {
        return buildWith(out -> {
            writeString(out, filename);
            out.writeInt(width);
            out.writeInt(height);
        }, MessageType.FILE_CREATE_REQ);
    }

    public static byte[] encodeFileCreateOk(int fileId, String filename) {
        return buildWith(out -> {
            out.writeInt(fileId);
            writeString(out, filename);
        }, MessageType.FILE_CREATE_OK);
    }

    public static byte[] encodeFileOpenReq(int fileId) {
        return buildWith(out -> out.writeInt(fileId), MessageType.FILE_OPEN_REQ);
    }

    public static byte[] encodeFileOpenData(int fileId, String filename, String owner,
                                             int width, int height, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            writeString(out, filename);
            writeString(out, owner);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, MessageType.FILE_OPEN_DATA);
    }

    public static byte[] encodeFileListReq() {
        return encode(MessageType.FILE_LIST_REQ, null);
    }

    public static byte[] encodeFileListResp(java.util.List<multiuserpaint.server.store.FileMetadata> files) {
        return buildWith(out -> {
            out.writeShort(files.size());
            for (multiuserpaint.server.store.FileMetadata f : files) {
                out.writeInt(f.getFileId());
                writeString(out, f.getFilename());
                writeString(out, f.getOwner());
                out.writeInt(f.getWidth());
                out.writeInt(f.getHeight());
                out.writeLong(f.getLastModified());
            }
        }, MessageType.FILE_LIST_RESP);
    }

    public static byte[] encodeFileSaveReq(int fileId, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, MessageType.FILE_SAVE_REQ);
    }

    public static byte[] encodeFileSaveOk(int fileId) {
        return buildWith(out -> out.writeInt(fileId), MessageType.FILE_SAVE_OK);
    }

    public static byte[] encodeFileDeleteReq(int fileId) {
        return buildWith(out -> out.writeInt(fileId), MessageType.FILE_DELETE_REQ);
    }

    public static byte[] encodeFileDeleteOk(int fileId) {
        return buildWith(out -> out.writeInt(fileId), MessageType.FILE_DELETE_OK);
    }

    public static byte[] encodeFileDeleteErr(int fileId, String reason) {
        return buildWith(out -> {
            out.writeInt(fileId);
            writeString(out, reason);
        }, MessageType.FILE_DELETE_ERR);
    }

    // -------------------------------------------------------------------------
    // Drawing messages
    // -------------------------------------------------------------------------

    public static byte[] encodeDrawEvent(int fileId, byte toolType, int colorArgb,
                                          short strokeWidth, int x1, int y1, int x2, int y2) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeByte(toolType);
            out.writeInt(colorArgb);
            out.writeShort(strokeWidth);
            out.writeInt(x1);
            out.writeInt(y1);
            out.writeInt(x2);
            out.writeInt(y2);
        }, MessageType.DRAW_EVENT);
    }

    public static byte[] encodeDrawBroadcast(int fileId, String senderUsername,
                                              byte toolType, int colorArgb,
                                              short strokeWidth, int x1, int y1, int x2, int y2) {
        return buildWith(out -> {
            out.writeInt(fileId);
            writeString(out, senderUsername);
            out.writeByte(toolType);
            out.writeInt(colorArgb);
            out.writeShort(strokeWidth);
            out.writeInt(x1);
            out.writeInt(y1);
            out.writeInt(x2);
            out.writeInt(y2);
        }, MessageType.DRAW_BROADCAST);
    }

    // -------------------------------------------------------------------------
    // Clipboard messages
    // -------------------------------------------------------------------------

    public static byte[] encodeCanvasUpdate(int fileId, int width, int height, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, MessageType.CANVAS_UPDATE);
    }

    public static byte[] encodeCanvasSnapshotReq(int fileId) {
        return buildWith(out -> out.writeInt(fileId), MessageType.CANVAS_SNAPSHOT_REQ);
    }

    public static byte[] encodeCanvasSnapshotData(int fileId, int width, int height, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, MessageType.CANVAS_SNAPSHOT_DATA);
    }

    public static byte[] encodeClipboardCopy(int fileId, int rx, int ry, int rw, int rh, byte[] pixelData) {
        return encodeClipboardRegion(MessageType.CLIPBOARD_COPY, fileId, rx, ry, rw, rh, pixelData);
    }

    public static byte[] encodeClipboardCut(int fileId, int rx, int ry, int rw, int rh, byte[] pixelData) {
        return encodeClipboardRegion(MessageType.CLIPBOARD_CUT, fileId, rx, ry, rw, rh, pixelData);
    }

    private static byte[] encodeClipboardRegion(MessageType t, int fileId,
                                                  int rx, int ry, int rw, int rh, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(rx);
            out.writeInt(ry);
            out.writeInt(rw);
            out.writeInt(rh);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, t);
    }

    public static byte[] encodeClipboardPasteReq(int fileId, int pasteX, int pasteY) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(pasteX);
            out.writeInt(pasteY);
        }, MessageType.CLIPBOARD_PASTE_REQ);
    }

    public static byte[] encodeClipboardData(int fileId, int pasteX, int pasteY,
                                              int width, int height, byte[] pixelData) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeInt(pasteX);
            out.writeInt(pasteY);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(pixelData.length);
            out.write(pixelData);
        }, MessageType.CLIPBOARD_DATA);
    }

    // -------------------------------------------------------------------------
    // User events
    // -------------------------------------------------------------------------

    public static byte[] encodeUserJoin(String username) {
        return buildWith(out -> {
            writeString(out, username);
            out.writeLong(System.currentTimeMillis());
        }, MessageType.USER_JOIN);
    }

    public static byte[] encodeUserLeave(String username) {
        return buildWith(out -> {
            writeString(out, username);
            out.writeLong(System.currentTimeMillis());
        }, MessageType.USER_LEAVE);
    }

    public static byte[] encodeUserListReq() {
        return encode(MessageType.USER_LIST_REQ, null);
    }

    public static byte[] encodeUserListResp(java.util.List<String> usernames) {
        return buildWith(out -> {
            out.writeShort(usernames.size());
            for (String u : usernames) writeString(out, u);
        }, MessageType.USER_LIST_RESP);
    }

    // -------------------------------------------------------------------------
    // Notifications / keepalive / error
    // -------------------------------------------------------------------------

    public static byte[] encodeAutosaveNotify(int fileId, long timestamp) {
        return buildWith(out -> {
            out.writeInt(fileId);
            out.writeLong(timestamp);
        }, MessageType.AUTOSAVE_NOTIFY);
    }

    public static byte[] encodePing() {
        return encode(MessageType.PING, null);
    }

    public static byte[] encodePong() {
        return encode(MessageType.PONG, null);
    }

    public static byte[] encodeError(String message) {
        return buildWith(out -> writeString(out, message), MessageType.ERROR);
    }

    // -------------------------------------------------------------------------
    // Internal builder helper
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] buildWith(PayloadWriter writer, MessageType type) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            writer.write(dos);
            dos.flush();
            return encode(type, bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Encoding failed for " + type, e);
        }
    }
}
