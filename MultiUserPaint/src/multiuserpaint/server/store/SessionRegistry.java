package multiuserpaint.server.store;

import multiuserpaint.server.ClientSession;
import multiuserpaint.server.mq.MQBroker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all active client sessions.
 *
 * Socket mode: enqueue / broadcast write to each session's writeQueue;
 *              the NIO thread drains those queues.
 *
 * MQ mode: set a MQBroker via setMQBroker().
 *   - enqueue()                → session.enqueue() → mqSink → broker.sendToClient()
 *   - broadcastToAll()         → broker.broadcastGlobal()  (fanout, all connected clients)
 *   - broadcastToFileViewers() → per-session enqueue()     (exclude sender, point-to-point)
 */
public class SessionRegistry {
    private final Map<Integer, ClientSession> bySessionId = new ConcurrentHashMap<>();
    private final Map<String,  ClientSession> byUsername  = new ConcurrentHashMap<>();

    /** Set by PaintServer when running in MQ mode. */
    private MQBroker mqBroker = null;

    public void setMQBroker(MQBroker broker) {
        this.mqBroker = broker;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public void register(ClientSession session) {
        bySessionId.put(session.getSessionId(), session);
        if (session.getUsername() != null) {
            byUsername.put(session.getUsername(), session);
        }
    }

    public void registerUsername(ClientSession session) {
        byUsername.put(session.getUsername(), session);
    }

    public void unregister(int sessionId) {
        ClientSession session = bySessionId.remove(sessionId);
        if (session != null && session.getUsername() != null) {
            byUsername.remove(session.getUsername());
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    public boolean isUsernameTaken(String username) {
        return byUsername.containsKey(username);
    }

    public ClientSession getBySessionId(int sessionId) {
        return bySessionId.get(sessionId);
    }

    public ClientSession getByUsername(String username) {
        return byUsername.get(username);
    }

    public Collection<ClientSession> getAllSessions() {
        return bySessionId.values();
    }

    public List<String> getAllUsernames() {
        return new ArrayList<>(byUsername.keySet());
    }

    /** Returns all logged-in sessions that have the given file open. */
    public List<ClientSession> getSessionsWithFileOpen(int fileId) {
        List<ClientSession> result = new ArrayList<>();
        for (ClientSession s : bySessionId.values()) {
            if (s.isLoggedIn() && s.hasFileOpen(fileId)) result.add(s);
        }
        return result;
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Enqueue a message to all logged-in sessions except the excluded one.
     *
     * MQ mode: publishes to the global fanout exchange so every connected
     * client receives the message without iterating sessions.
     * (The excludeSessionId hint is unused in MQ mode; the client receives
     * USER_JOIN for itself, which the GUI can safely ignore.)
     */
    public void broadcastToAll(byte[] encoded, int excludeSessionId) {
        if (mqBroker != null) {
            mqBroker.broadcastGlobal(encoded);
            return;
        }
        // Socket mode: direct per-session delivery
        for (ClientSession s : bySessionId.values()) {
            if (s.getSessionId() != excludeSessionId && s.isLoggedIn()) {
                enqueue(s, encoded);
            }
        }
    }

    /**
     * Enqueue a message to all sessions with the given file open, excluding sender.
     *
     * MQ mode: still uses per-session point-to-point delivery so the sender
     * is correctly excluded (fan-out exchange cannot exclude individual consumers).
     */
    public void broadcastToFileViewers(int fileId, byte[] encoded, int excludeSessionId) {
        for (ClientSession s : getSessionsWithFileOpen(fileId)) {
            if (s.getSessionId() != excludeSessionId) {
                enqueue(s, encoded);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Send one frame to one session.
     * Delegates to session.enqueue() which routes through the installed sink
     * (writeQueue in socket mode, AMQP publish in MQ mode).
     */
    private void enqueue(ClientSession s, byte[] data) {
        s.enqueue(data);
    }
}
