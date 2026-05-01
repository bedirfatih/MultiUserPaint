package multiuserpaint.server;

import multiuserpaint.common.FSMState;
import multiuserpaint.common.MessageEncoder;
import multiuserpaint.common.ProtocolConstants;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking NIO Selector loop.
 * Single thread handles all accept/read/write events.
 */
public class NIOSelector implements Runnable {
    private static final Logger LOG = Logger.getLogger(NIOSelector.class.getName());

    private final int port;
    private final MessageDispatcher dispatcher;
    private final SessionRegistry registry;
    private final AtomicInteger sessionIdCounter = new AtomicInteger(1);

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;

    public NIOSelector(int port, MessageDispatcher dispatcher, SessionRegistry registry) {
        this.port = port;
        this.dispatcher = dispatcher;
        this.registry = registry;
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;

        Thread t = new Thread(this, "NIO-Selector");
        t.setDaemon(false);
        t.start();
        LOG.info("Server listening on port " + port);
    }

    public void stop() {
        running = false;
        if (selector != null) selector.wakeup();
    }

    @Override
    public void run() {
        while (running) {
            try {
                int ready = selector.select(1000);
                if (ready == 0) {
                    checkKeepaliveTimeouts();
                    continue;
                }

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        acceptClient();
                    } else if (key.isReadable()) {
                        readFromClient(key);
                    } else if (key.isWritable()) {
                        writeToClient(key);
                    }
                }

                // Enable OP_WRITE for sessions with pending data
                flushPendingWrites();

            } catch (IOException e) {
                if (running) LOG.log(Level.SEVERE, "Selector error", e);
            }
        }

        closeAll();
    }

    // -------------------------------------------------------------------------
    // Accept
    // -------------------------------------------------------------------------

    private void acceptClient() throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        int sessionId = sessionIdCounter.getAndIncrement();
        ClientSession session = new ClientSession(sessionId, clientChannel);

        SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ);
        key.attach(session);

        registry.register(session);
        LOG.info("New connection accepted: session " + sessionId
            + " from " + clientChannel.getRemoteAddress());
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    private void readFromClient(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = session.getChannel();

        try {
            int bytesRead = channel.read(session.readBuffer);
            if (bytesRead == -1) {
                closeSession(key, session);
                return;
            }

            // Flip the read buffer so MessageFramer can consume it
            session.readBuffer.flip();

            // Feed all bytes into the framer; may produce multiple messages
            while (session.readBuffer.hasRemaining()) {
                multiuserpaint.common.Message msg;
                try {
                    msg = MessageFramer.feed(session, session.readBuffer);
                } catch (MessageFramer.ProtocolException e) {
                    LOG.warning("Protocol error from " + session + ": " + e.getMessage());
                    closeSession(key, session);
                    return;
                }

                if (msg == null) break; // need more bytes from the channel

                boolean keepOpen = dispatcher.dispatch(session, msg);
                if (!keepOpen) {
                    closeSession(key, session);
                    return;
                }
            }

            // Compact: move unread bytes to front, switch back to write mode
            session.readBuffer.compact();

        } catch (IOException e) {
            LOG.info("Client read error (" + session.getUsername() + "): " + e.getMessage());
            closeSession(key, session);
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    private void writeToClient(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = session.getChannel();

        try {
            ByteBuffer buf;
            while ((buf = session.writeQueue.peek()) != null) {
                channel.write(buf);
                if (buf.hasRemaining()) break; // channel buffer full, try later
                session.writeQueue.poll(); // fully written, remove
            }

            if (session.writeQueue.isEmpty()) {
                // No more data to write; stop listening for OP_WRITE
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            LOG.info("Client write error (" + session.getUsername() + "): " + e.getMessage());
            closeSession(key, session);
        }
    }

    private void flushPendingWrites() {
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.channel() instanceof SocketChannel)) continue;
            ClientSession session = (ClientSession) key.attachment();
            if (session != null && !session.writeQueue.isEmpty()) {
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private void closeSession(SelectionKey key, ClientSession session) {
        try {
            if (session.isLoggedIn() && session.getUsername() != null) {
                byte[] leaveMsg = MessageEncoder.encodeUserLeave(session.getUsername());
                registry.broadcastToAll(leaveMsg, session.getSessionId());
                LOG.info("User disconnected: " + session.getUsername());
            }
            session.transitionTo(FSMState.CLOSING);
            registry.unregister(session.getSessionId());
            key.cancel();
            session.getChannel().close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Error closing session", e);
        }
    }

    private void closeAll() {
        try {
            for (SelectionKey key : selector.keys()) {
                key.channel().close();
            }
            selector.close();
            serverChannel.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Error during shutdown", e);
        }
    }

    // -------------------------------------------------------------------------
    // Keepalive check
    // -------------------------------------------------------------------------

    private void checkKeepaliveTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = ProtocolConstants.KEEPALIVE_TIMEOUT_SECONDS * 1000L;

        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.channel() instanceof SocketChannel)) continue;
            ClientSession session = (ClientSession) key.attachment();
            if (session != null && session.isLoggedIn()) {
                if (now - session.getLastPingTime() > timeoutMs) {
                    LOG.warning("Keepalive timeout for " + session.getUsername());
                    closeSession(key, session);
                }
            }
        }
    }
}
