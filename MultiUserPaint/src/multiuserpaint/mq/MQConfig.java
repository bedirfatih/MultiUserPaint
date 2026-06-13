package multiuserpaint.mq;

/**
 * RabbitMQ topology constants shared by server and client.
 *
 * Topology overview:
 *   paint.server           – server request queue (all client → server messages)
 *   paint.global           – fanout exchange  (USER_JOIN / USER_LEAVE / FILE_LIST_RESP)
 *   paint.file.{fileId}    – fanout exchange  (DRAW_BROADCAST / CANVAS_UPDATE)
 *   paint.client.{id}      – exclusive client queue (direct server → client responses)
 *
 * Message body: the same wire-format bytes produced by MessageEncoder
 *   [MAGIC 2B][VER 1B][TYPE 1B][PAYLOAD_LEN 4B][PAYLOAD NB]
 *
 * AMQP message headers (sent by client):
 *   "sessionId"  – int,    absent until after LOGIN_OK
 *   "replyTo"    – String, client queue name (always present)
 */
public final class MQConfig {

    // ── Broker connection defaults ────────────────────────────────────────────
    public static final String DEFAULT_HOST     = "localhost";
    public static final int    DEFAULT_AMQP_PORT = 5672;
    public static final String VHOST            = "/";
    public static final String USER             = "guest";
    public static final String PASSWORD         = "guest";

    // ── Queue / exchange names ────────────────────────────────────────────────
    public static final String SERVER_QUEUE       = "paint.server";
    public static final String EXCHANGE_GLOBAL    = "paint.global";
    public static final String FILE_EXCHANGE_PREFIX = "paint.file.";
    public static final String CLIENT_QUEUE_PREFIX  = "paint.client.";

    // ── AMQP message header keys ──────────────────────────────────────────────
    public static final String HDR_SESSION_ID = "sessionId";
    public static final String HDR_REPLY_TO   = "replyTo";

    // ── Helpers ───────────────────────────────────────────────────────────────
    public static String fileExchange(int fileId) {
        return FILE_EXCHANGE_PREFIX + fileId;
    }

    public static String clientQueue(String clientId) {
        return CLIENT_QUEUE_PREFIX + clientId;
    }

    private MQConfig() {}
}
