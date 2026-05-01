package multiuserpaint.server.handlers;

import multiuserpaint.common.*;
import multiuserpaint.server.ClientSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Handles clipboard operations (copy, cut, paste).
 * Clipboard data is stored per-session on the server.
 */
public class ClipboardHandler {
    private static final Logger LOG = Logger.getLogger(ClipboardHandler.class.getName());

    public void handleCopyOrCut(ClientSession session, Message msg) throws IOException {
        MessageDecoder.ClipboardRegionPayload req = MessageDecoder.decodeClipboardRegion(msg.getPayload());
        session.setClipboard(req.pixelData, req.rw, req.rh);
        LOG.fine("Clipboard set for " + session.getUsername()
            + " (" + req.rw + "x" + req.rh + " region)");
        // CUT: client is responsible for clearing the region locally and sending a DRAW_EVENT to erase
    }

    public void handlePasteReq(ClientSession session, Message msg) throws IOException {
        if (!session.hasClipboard()) {
            enqueue(session, MessageEncoder.encodeError("Clipboard is empty"));
            return;
        }
        enqueue(session, MessageEncoder.encodeClipboardData(
            session.getClipboardWidth(),
            session.getClipboardHeight(),
            session.getClipboardData()
        ));
    }

    private void enqueue(ClientSession session, byte[] data) {
        session.writeQueue.offer(ByteBuffer.wrap(data));
    }
}
