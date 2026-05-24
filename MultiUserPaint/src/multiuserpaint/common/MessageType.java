package multiuserpaint.common;

public enum MessageType {
    // Login
    LOGIN_REQ          (0x01),
    LOGIN_OK           (0x02),
    LOGIN_ERR          (0x03),

    // File operations
    FILE_CREATE_REQ    (0x10),
    FILE_CREATE_OK     (0x11),
    FILE_OPEN_REQ      (0x12),
    FILE_OPEN_DATA     (0x13),
    FILE_LIST_REQ      (0x14),
    FILE_LIST_RESP     (0x15),
    FILE_SAVE_REQ      (0x16),
    FILE_SAVE_OK       (0x17),
    FILE_DELETE_REQ    (0x18),
    FILE_DELETE_OK     (0x19),
    FILE_DELETE_ERR    (0x1A),

    // Drawing
    DRAW_EVENT         (0x20),
    DRAW_BROADCAST     (0x21),

    // Clipboard
    CLIPBOARD_COPY     (0x30),
    CLIPBOARD_PASTE_REQ(0x31),
    CLIPBOARD_DATA     (0x32),
    CLIPBOARD_CUT      (0x33),

    // User events
    USER_JOIN          (0x40),
    USER_LEAVE         (0x41),
    USER_LIST_REQ      (0x42),
    USER_LIST_RESP     (0x43),

    // Canvas snapshot (for sync when new user joins active session)
    CANVAS_SNAPSHOT_REQ  (0x60),
    CANVAS_SNAPSHOT_DATA (0x61),
    // Server broadcasts full canvas state to other viewers (e.g. after undo)
    CANVAS_UPDATE        (0x62),

    // Server notifications
    AUTOSAVE_NOTIFY    (0x50),

    // Keepalive
    PING               (0xF0),
    PONG               (0xF1),

    // Error
    ERROR              (0xFF);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public byte getCode() {
        return (byte) code;
    }

    public static MessageType fromCode(byte code) {
        int unsigned = code & 0xFF;
        for (MessageType t : values()) {
            if (t.code == unsigned) return t;
        }
        throw new IllegalArgumentException("Unknown message type: 0x" + Integer.toHexString(unsigned));
    }
}
