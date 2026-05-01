package multiuserpaint.server.handlers;

import multiuserpaint.common.*;
import multiuserpaint.server.ClientSession;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles DRAW_EVENT: broadcasts to all other sessions viewing the same file.
 */
public class DrawHandler {
    private static final Logger LOG = Logger.getLogger(DrawHandler.class.getName());

    private final SessionRegistry registry;

    public DrawHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    public void handle(ClientSession session, Message msg) throws IOException {
        MessageDecoder.DrawEventPayload event = MessageDecoder.decodeDrawEvent(msg.getPayload());

        // Mark file as open for this session if not already
        session.addOpenFile(event.fileId);

        byte[] broadcast = MessageEncoder.encodeDrawBroadcast(
            event.fileId, session.getUsername(),
            event.toolType, event.colorArgb, event.strokeWidth,
            event.x1, event.y1, event.x2, event.y2
        );

        registry.broadcastToFileViewers(event.fileId, broadcast, session.getSessionId());
    }
}
