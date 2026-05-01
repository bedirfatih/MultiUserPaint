package multiuserpaint.common;

/**
 * FSM states used by both server (per-ClientSession) and client (ConnectionManager).
 */
public enum FSMState {
    // Server-side states
    HANDSHAKE,   // TCP connected, waiting for LOGIN_REQ
    ACTIVE,      // Logged in, normal operation
    SAVING,      // Disk write in progress
    CLOSING,     // Graceful disconnect
    ERROR,       // Unrecoverable, will be purged

    // Client-side states
    DISCONNECTED,
    CONNECTING,
    LOGGING_IN,
    CONNECTED,
    RECONNECTING
}
