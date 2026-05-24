package multiuserpaint.server.handlers;

import multiuserpaint.common.*;
import multiuserpaint.server.ClientSession;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Handles clipboard operations (copy, cut, paste).
 * Clipboard data is stored per-session on the server.
 * Paste is broadcast to all other viewers of the same file.
 */
public class ClipboardHandler {
    private static final Logger LOG = Logger.getLogger(ClipboardHandler.class.getName());

    private final SessionRegistry registry;

    public ClipboardHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    public void handleCopyOrCut(ClientSession session, Message msg) throws IOException {
        MessageDecoder.ClipboardRegionPayload req = MessageDecoder.decodeClipboardRegion(msg.getPayload());
        session.setClipboard(req.pixelData, req.rw, req.rh);
        LOG.fine("Clipboard set for " + session.getUsername()
            + " (" + req.rw + "x" + req.rh + " region)");
    }

    public void handlePasteReq(ClientSession session, Message msg) throws IOException {
        if (!session.hasClipboard()) {
            enqueue(session, MessageEncoder.encodeError("Clipboard is empty"));
            return;
        }

        MessageDecoder.ClipboardPasteReqPayload req =
            MessageDecoder.decodeClipboardPasteReq(msg.getPayload());

        byte[] clipboardMsg = MessageEncoder.encodeClipboardData(
            req.fileId, req.x, req.y,
            session.getClipboardWidth(),
            session.getClipboardHeight(),
            session.getClipboardData()
        );

        // Send to requester
        enqueue(session, clipboardMsg);

        // Broadcast paste to all other viewers of the same file
        registry.broadcastToFileViewers(req.fileId, clipboardMsg, session.getSessionId());
        LOG.fine("Paste broadcast for file " + req.fileId + " at ("
            + req.x + "," + req.y + ") by " + session.getUsername());
    }

    private void enqueue(ClientSession session, byte[] data) {
        session.writeQueue.offer(ByteBuffer.wrap(data));
    }
}
