package multiuserpaint.client.network;

import multiuserpaint.common.*;

import javax.swing.SwingUtilities;
import java.io.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daemon thread that reads frames from the server and dispatches them on the EDT.
 */
public class NetworkReader implements Runnable {
    private static final Logger LOG = Logger.getLogger(NetworkReader.class.getName());

    private final Consumer<Message> onMessage;
    private final Runnable onDisconnect;
    private volatile InputStream in;
    private volatile boolean running = false;
    private Thread thread;

    public NetworkReader(Consumer<Message> onMessage, Runnable onDisconnect) {
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
    }

    public void start(InputStream in) {
        this.in = in;
        this.running = true;
        thread = new Thread(this, "NetworkReader");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        DataInputStream dis = new DataInputStream(in);
        while (running) {
            try {
                Message msg = readNextFrame(dis);
                SwingUtilities.invokeLater(() -> onMessage.accept(msg));
            } catch (EOFException e) {
                break; // server closed connection
            } catch (IOException e) {
                if (running) LOG.log(Level.WARNING, "Read error", e);
                break;
            }
        }
        running = false;
        SwingUtilities.invokeLater(onDisconnect);
    }

    /**
     * Blocking read of one complete frame from the stream.
     * Mirrors MessageFramer but works on a blocking InputStream.
     */
    private Message readNextFrame(DataInputStream dis) throws IOException {
        // Read header (8 bytes)
        byte hi   = dis.readByte();
        byte lo   = dis.readByte();

        short magic = (short) (((hi & 0xFF) << 8) | (lo & 0xFF));
        if (magic != ProtocolConstants.MAGIC) {
            throw new IOException("Invalid magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }

        dis.readByte(); // version (consumed, not validated for forward compat)
        byte typeCode  = dis.readByte();
        int payloadLen = dis.readInt();

        if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new IOException("Payload too large: " + payloadLen);
        }

        byte[] payload = new byte[payloadLen];
        dis.readFully(payload);

        MessageType type;
        try {
            type = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown message type: 0x" + Integer.toHexString(typeCode & 0xFF));
        }

        return Message.of(type, payload);
    }
}
