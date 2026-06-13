package multiuserpaint.client.network;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import multiuserpaint.mq.MQConfig;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side AMQP publisher.
 *
 * Mirrors the original NetworkWriter interface: thread-safe {@code send(byte[])}
 * enqueues wire-format frames, a daemon thread dequeues and publishes each
 * frame to {@code paint.server} with AMQP headers:
 *   "sessionId"  – int   (absent before LOGIN_OK)
 *   "replyTo"    – String (client's exclusive queue name)
 *
 * The daemon thread is started via {@code start()} and stopped via {@code stop()}.
 */
public class MQNetworkWriter implements Runnable {
    private static final Logger LOG = Logger.getLogger(MQNetworkWriter.class.getName());

    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = false;
    private Thread thread;

    private Channel channel;
    private String  clientQueueName; // replyTo sent with every message
    private int     sessionId = -1;  // -1 = not yet logged in

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start(Channel channel, String clientQueueName) {
        this.channel         = channel;
        this.clientQueueName = clientQueueName;
        this.running         = true;

        thread = new Thread(this, "MQNetworkWriter");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** Called by ConnectionManager after LOGIN_OK is received. */
    public void setSessionId(int id) {
        this.sessionId = id;
    }

    // ── Send API ──────────────────────────────────────────────────────────────

    /** Thread-safe: enqueue a wire-format frame for publishing. */
    public void send(byte[] data) {
        if (running) writeQueue.offer(data);
    }

    // ── Publisher loop ────────────────────────────────────────────────────────

    @Override
    public void run() {
        while (running) {
            try {
                byte[] frame = writeQueue.take();
                publish(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "MQ publish error", e);
                    running = false;
                }
                break;
            }
        }
    }

    private void publish(byte[] frame) throws IOException {
        Map<String, Object> headers = new HashMap<>();
        headers.put(MQConfig.HDR_REPLY_TO, clientQueueName);
        if (sessionId >= 0) {
            headers.put(MQConfig.HDR_SESSION_ID, sessionId);
        }

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .build();

        channel.basicPublish("", MQConfig.SERVER_QUEUE, props, frame);
    }
}
