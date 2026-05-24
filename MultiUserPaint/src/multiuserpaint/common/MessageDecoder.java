package multiuserpaint.common;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes wire-format payloads into typed objects.
 */
public class MessageDecoder {

    private MessageDecoder() {}

    // -------------------------------------------------------------------------
    // Low-level helpers
    // -------------------------------------------------------------------------

    public static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static DataInputStream wrap(byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    // -------------------------------------------------------------------------
    // Payload objects (simple POJOs)
    // -------------------------------------------------------------------------

    public static class LoginReqPayload {
        public final String username;
        public LoginReqPayload(String username) { this.username = username; }
    }

    public static class LoginOkPayload {
        public final int sessionId;
        public final long serverTimestamp;
        public LoginOkPayload(int sessionId, long serverTimestamp) {
            this.sessionId = sessionId;
            this.serverTimestamp = serverTimestamp;
        }
    }

    public static class LoginErrPayload {
        public final byte errorCode;
        public final String message;
        public LoginErrPayload(byte errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }
    }

    public static class FileCreateReqPayload {
        public final String filename;
        public final int width, height;
        public FileCreateReqPayload(String filename, int width, int height) {
            this.filename = filename; this.width = width; this.height = height;
        }
    }

    public static class FileCreateOkPayload {
        public final int fileId;
        public final String filename;
        public FileCreateOkPayload(int fileId, String filename) {
            this.fileId = fileId; this.filename = filename;
        }
    }

    public static class FileOpenDataPayload {
        public final int fileId;
        public final String filename, owner;
        public final int width, height;
        public final byte[] pixelData;
        public FileOpenDataPayload(int fileId, String filename, String owner,
                                   int width, int height, byte[] pixelData) {
            this.fileId = fileId; this.filename = filename; this.owner = owner;
            this.width = width; this.height = height; this.pixelData = pixelData;
        }
    }

    public static class FileListEntry {
        public final int fileId;
        public final String filename, owner;
        public final int width, height;
        public final long lastModified;
        public FileListEntry(int fileId, String filename, String owner,
                             int width, int height, long lastModified) {
            this.fileId = fileId; this.filename = filename; this.owner = owner;
            this.width = width; this.height = height; this.lastModified = lastModified;
        }
    }

    public static class FileSaveReqPayload {
        public final int fileId;
        public final byte[] pixelData;
        public FileSaveReqPayload(int fileId, byte[] pixelData) {
            this.fileId = fileId; this.pixelData = pixelData;
        }
    }

    public static class DrawEventPayload {
        public final int fileId;
        public final byte toolType;
        public final int colorArgb;
        public final short strokeWidth;
        public final int x1, y1, x2, y2;
        public DrawEventPayload(int fileId, byte toolType, int colorArgb,
                                short strokeWidth, int x1, int y1, int x2, int y2) {
            this.fileId = fileId; this.toolType = toolType; this.colorArgb = colorArgb;
            this.strokeWidth = strokeWidth; this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
        }
    }

    public static class DrawBroadcastPayload {
        public final int fileId;
        public final String senderUsername;
        public final byte toolType;
        public final int colorArgb;
        public final short strokeWidth;
        public final int x1, y1, x2, y2;
        public DrawBroadcastPayload(int fileId, String senderUsername, byte toolType,
                                    int colorArgb, short strokeWidth,
                                    int x1, int y1, int x2, int y2) {
            this.fileId = fileId; this.senderUsername = senderUsername;
            this.toolType = toolType; this.colorArgb = colorArgb;
            this.strokeWidth = strokeWidth; this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
        }
    }

    public static class ClipboardRegionPayload {
        public final int fileId, rx, ry, rw, rh;
        public final byte[] pixelData;
        public ClipboardRegionPayload(int fileId, int rx, int ry, int rw, int rh, byte[] pixelData) {
            this.fileId = fileId; this.rx = rx; this.ry = ry;
            this.rw = rw; this.rh = rh; this.pixelData = pixelData;
        }
    }

    public static class ClipboardPasteReqPayload {
        public final int fileId, x, y;
        public ClipboardPasteReqPayload(int fileId, int x, int y) {
            this.fileId = fileId; this.x = x; this.y = y;
        }
    }

    public static class ClipboardDataPayload {
        public final int fileId, x, y, width, height;
        public final byte[] pixelData;
        public ClipboardDataPayload(int fileId, int x, int y, int width, int height, byte[] pixelData) {
            this.fileId = fileId; this.x = x; this.y = y;
            this.width = width; this.height = height; this.pixelData = pixelData;
        }
    }

    public static class UserEventPayload {
        public final String username;
        public final long timestamp;
        public UserEventPayload(String username, long timestamp) {
            this.username = username; this.timestamp = timestamp;
        }
    }

    public static class CanvasUpdatePayload {
        public final int fileId, width, height;
        public final byte[] pixelData;
        public CanvasUpdatePayload(int fileId, int width, int height, byte[] pixelData) {
            this.fileId = fileId; this.width = width; this.height = height; this.pixelData = pixelData;
        }
    }

    public static class CanvasSnapshotDataPayload {
        public final int fileId, width, height;
        public final byte[] pixelData;
        public CanvasSnapshotDataPayload(int fileId, int width, int height, byte[] pixelData) {
            this.fileId = fileId; this.width = width; this.height = height; this.pixelData = pixelData;
        }
    }

    public static class AutosaveNotifyPayload {
        public final int fileId;
        public final long timestamp;
        public AutosaveNotifyPayload(int fileId, long timestamp) {
            this.fileId = fileId; this.timestamp = timestamp;
        }
    }

    // -------------------------------------------------------------------------
    // Decode methods
    // -------------------------------------------------------------------------

    public static LoginReqPayload decodeLoginReq(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new LoginReqPayload(readString(in));
    }

    public static LoginOkPayload decodeLoginOk(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new LoginOkPayload(in.readInt(), in.readLong());
    }

    public static LoginErrPayload decodeLoginErr(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new LoginErrPayload(in.readByte(), readString(in));
    }

    public static FileCreateReqPayload decodeFileCreateReq(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new FileCreateReqPayload(readString(in), in.readInt(), in.readInt());
    }

    public static FileCreateOkPayload decodeFileCreateOk(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new FileCreateOkPayload(in.readInt(), readString(in));
    }

    public static int decodeFileOpenReq(byte[] p) throws IOException {
        return wrap(p).readInt();
    }

    public static FileOpenDataPayload decodeFileOpenData(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt();
        String filename = readString(in);
        String owner = readString(in);
        int width = in.readInt();
        int height = in.readInt();
        int len = in.readInt();
        byte[] pixelData = new byte[len];
        in.readFully(pixelData);
        return new FileOpenDataPayload(fileId, filename, owner, width, height, pixelData);
    }

    public static List<FileListEntry> decodeFileListResp(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int count = in.readUnsignedShort();
        List<FileListEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new FileListEntry(
                in.readInt(), readString(in), readString(in),
                in.readInt(), in.readInt(), in.readLong()
            ));
        }
        return list;
    }

    public static FileSaveReqPayload decodeFileSaveReq(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt();
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return new FileSaveReqPayload(fileId, data);
    }

    public static int decodeFileSaveOk(byte[] p) throws IOException {
        return wrap(p).readInt();
    }

    public static int decodeFileDeleteReq(byte[] p) throws IOException {
        return wrap(p).readInt();
    }

    public static int decodeFileDeleteOk(byte[] p) throws IOException {
        return wrap(p).readInt();
    }

    public static DrawEventPayload decodeDrawEvent(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new DrawEventPayload(
            in.readInt(), in.readByte(), in.readInt(),
            in.readShort(), in.readInt(), in.readInt(), in.readInt(), in.readInt()
        );
    }

    public static DrawBroadcastPayload decodeDrawBroadcast(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt();
        String sender = readString(in);
        return new DrawBroadcastPayload(
            fileId, sender, in.readByte(), in.readInt(),
            in.readShort(), in.readInt(), in.readInt(), in.readInt(), in.readInt()
        );
    }

    public static ClipboardRegionPayload decodeClipboardRegion(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt();
        int rx = in.readInt(), ry = in.readInt(), rw = in.readInt(), rh = in.readInt();
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return new ClipboardRegionPayload(fileId, rx, ry, rw, rh, data);
    }

    public static ClipboardPasteReqPayload decodeClipboardPasteReq(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new ClipboardPasteReqPayload(in.readInt(), in.readInt(), in.readInt());
    }

    public static ClipboardDataPayload decodeClipboardData(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt(), x = in.readInt(), y = in.readInt();
        int w = in.readInt(), h = in.readInt();
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return new ClipboardDataPayload(fileId, x, y, w, h, data);
    }

    public static UserEventPayload decodeUserEvent(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new UserEventPayload(readString(in), in.readLong());
    }

    public static List<String> decodeUserListResp(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int count = in.readUnsignedShort();
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(readString(in));
        return list;
    }

    public static AutosaveNotifyPayload decodeAutosaveNotify(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        return new AutosaveNotifyPayload(in.readInt(), in.readLong());
    }

    public static CanvasUpdatePayload decodeCanvasUpdate(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt(), width = in.readInt(), height = in.readInt();
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return new CanvasUpdatePayload(fileId, width, height, data);
    }

    public static int decodeCanvasSnapshotReq(byte[] p) throws IOException {
        return wrap(p).readInt();
    }

    public static CanvasSnapshotDataPayload decodeCanvasSnapshotData(byte[] p) throws IOException {
        DataInputStream in = wrap(p);
        int fileId = in.readInt();
        int width  = in.readInt();
        int height = in.readInt();
        int len    = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return new CanvasSnapshotDataPayload(fileId, width, height, data);
    }

    public static String decodeError(byte[] p) throws IOException {
        return readString(wrap(p));
    }
}
