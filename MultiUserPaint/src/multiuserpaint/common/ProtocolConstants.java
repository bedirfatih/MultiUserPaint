package multiuserpaint.common;

public class ProtocolConstants {
    public static final short MAGIC = (short) 0xCAFE;
    public static final byte VERSION = 0x01;
    public static final int HEADER_SIZE = 8;           // 2+1+1+4
    public static final int MAX_PAYLOAD_SIZE = 10_485_760; // 10 MB
    public static final int READ_BUFFER_SIZE = 65_536;     // 64 KB (header+small msgs)
    public static final int DEFAULT_PORT = 9090;
    public static final int MAX_USERNAME_LENGTH = 16;
    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int AUTO_SAVE_INTERVAL_SECONDS = 60;
    public static final int PING_INTERVAL_SECONDS = 30;
    public static final int KEEPALIVE_TIMEOUT_SECONDS = 90;
    public static final int DEFAULT_CANVAS_WIDTH = 800;
    public static final int DEFAULT_CANVAS_HEIGHT = 600;

    private ProtocolConstants() {}
}
