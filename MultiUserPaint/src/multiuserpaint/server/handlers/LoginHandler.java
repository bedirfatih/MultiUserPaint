package multiuserpaint.server.handlers;

import multiuserpaint.common.*;
import multiuserpaint.server.ClientSession;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Handles LOGIN_REQ messages.
 * Valid transition: HANDSHAKE → ACTIVE
 */
public class LoginHandler {
    private static final Logger LOG = Logger.getLogger(LoginHandler.class.getName());
    private static final Pattern VALID_USERNAME = Pattern.compile("[a-zA-Z0-9_]{3,16}");

    private final SessionRegistry registry;

    public LoginHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    public void handle(ClientSession session, Message msg) throws IOException {
        MessageDecoder.LoginReqPayload req = MessageDecoder.decodeLoginReq(msg.getPayload());
        String username = req.username;

        if (!VALID_USERNAME.matcher(username).matches()) {
            enqueue(session, MessageEncoder.encodeLoginErr((byte) 0x02,
                "Username must be 3-16 chars: letters, digits, underscore"));
            LOG.info("Login rejected (invalid chars): " + username);
            return;
        }

        if (registry.isUsernameTaken(username)) {
            enqueue(session, MessageEncoder.encodeLoginErr((byte) 0x01,
                "Username already taken: " + username));
            LOG.info("Login rejected (taken): " + username);
            return;
        }

        // Accept login
        session.setUsername(username);
        session.transitionTo(FSMState.ACTIVE);
        registry.registerUsername(session);

        enqueue(session, MessageEncoder.encodeLoginOk(session.getSessionId()));
        LOG.info("User logged in: " + username + " (session " + session.getSessionId() + ")");

        // Notify all other clients
        byte[] joinMsg = MessageEncoder.encodeUserJoin(username);
        registry.broadcastToAll(joinMsg, session.getSessionId());
    }

    private void enqueue(ClientSession session, byte[] data) {
        session.writeQueue.offer(java.nio.ByteBuffer.wrap(data));
    }
}
