package multiuserpaint.client.network;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daemon thread that drains the write queue and sends bytes to the server.
 */
public class NetworkWriter implements Runnable {
    private static final Logger LOG = Logger.getLogger(NetworkWriter.class.getName());

    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private volatile OutputStream out;
    private volatile boolean running = false;
    private Thread thread;

    public void start(OutputStream out) {
        this.out = out;
        this.running = true;
        thread = new Thread(this, "NetworkWriter");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** Thread-safe: enqueue a message to be sent. */
    public void send(byte[] data) {
        if (running) writeQueue.offer(data);
    }

    @Override
    public void run() {
        while (running) {
            try {
                byte[] data = writeQueue.take();
                out.write(data);
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Write error", e);
                    running = false;
                }
                break;
            }
        }
    }
}
