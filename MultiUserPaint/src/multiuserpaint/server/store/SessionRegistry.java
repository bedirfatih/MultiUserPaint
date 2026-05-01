package multiuserpaint.server.store;

import multiuserpaint.server.ClientSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all active client sessions.
 * Methods may be called from multiple threads; ConcurrentHashMap provides safety.
 */
public class SessionRegistry {
    private final Map<Integer, ClientSession> bySessionId = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> byUsername   = new ConcurrentHashMap<>();

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

    /** Enqueue a message to all logged-in sessions except the excluded one. */
    public void broadcastToAll(byte[] encoded, int excludeSessionId) {
        for (ClientSession s : bySessionId.values()) {
            if (s.getSessionId() != excludeSessionId && s.isLoggedIn()) {
                enqueue(s, encoded);
            }
        }
    }

    /** Enqueue a message to all sessions with the given file open, excluding sender. */
    public void broadcastToFileViewers(int fileId, byte[] encoded, int excludeSessionId) {
        for (ClientSession s : getSessionsWithFileOpen(fileId)) {
            if (s.getSessionId() != excludeSessionId) {
                enqueue(s, encoded);
            }
        }
    }

    private void enqueue(ClientSession s, byte[] data) {
        s.writeQueue.offer(java.nio.ByteBuffer.wrap(data));
    }
}
