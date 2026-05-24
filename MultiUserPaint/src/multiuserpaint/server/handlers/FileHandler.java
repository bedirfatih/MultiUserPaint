package multiuserpaint.server.handlers;

import multiuserpaint.common.*;
import multiuserpaint.server.ClientSession;
import multiuserpaint.server.store.FileMetadata;
import multiuserpaint.server.store.FileStore;
import multiuserpaint.server.store.SessionRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles all file-related messages.
 * Disk I/O is submitted to the provided ExecutorService; results are returned
 * via the session's writeQueue so the NIO thread never blocks.
 */
public class FileHandler {
    private static final Logger LOG = Logger.getLogger(FileHandler.class.getName());

    private final FileStore fileStore;
    private final SessionRegistry registry;
    private final ExecutorService diskPool;

    // fileId -> sessions waiting for canvas snapshot before FILE_OPEN_DATA can be sent
    private final Map<Integer, List<PendingOpenRequest>> pendingOpens = new ConcurrentHashMap<>();

    private static class PendingOpenRequest {
        final ClientSession waitingSession;
        final String filename;
        final String owner;
        final int width, height;
        PendingOpenRequest(ClientSession s, String filename, String owner, int w, int h) {
            this.waitingSession = s; this.filename = filename; this.owner = owner;
            this.width = w; this.height = h;
        }
    }

    public FileHandler(FileStore fileStore, SessionRegistry registry, ExecutorService diskPool) {
        this.fileStore = fileStore;
        this.registry = registry;
        this.diskPool = diskPool;
    }

    public void handleCreate(ClientSession session, Message msg) throws IOException {
        MessageDecoder.FileCreateReqPayload req = MessageDecoder.decodeFileCreateReq(msg.getPayload());

        diskPool.submit(() -> {
            try {
                int fileId = fileStore.createFile(session.getUsername(), req.filename, req.width, req.height);
                enqueue(session, MessageEncoder.encodeFileCreateOk(fileId, req.filename));
                // Notify all clients of updated file list
                broadcastFileList();
                LOG.info("File created: " + req.filename + " id=" + fileId + " by " + session.getUsername());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "File create failed", e);
                enqueue(session, MessageEncoder.encodeError("File creation failed: " + e.getMessage()));
            }
        });
    }

    public void handleOpen(ClientSession session, Message msg) throws IOException {
        int fileId = MessageDecoder.decodeFileOpenReq(msg.getPayload());
        FileMetadata meta = fileStore.getMetadata(fileId);
        if (meta == null) {
            enqueue(session, MessageEncoder.encodeError("File not found: " + fileId));
            return;
        }
        session.addOpenFile(fileId);

        // Check for other active viewers of this file
        List<ClientSession> activeViewers = new ArrayList<>();
        for (ClientSession s : registry.getSessionsWithFileOpen(fileId)) {
            if (s.getSessionId() != session.getSessionId()) activeViewers.add(s);
        }

        if (!activeViewers.isEmpty()) {
            // Ask one active viewer for their current canvas state
            pendingOpens.computeIfAbsent(fileId, k -> new ArrayList<>())
                .add(new PendingOpenRequest(session, meta.getFilename(), meta.getOwner(),
                    meta.getWidth(), meta.getHeight()));
            enqueue(activeViewers.get(0), MessageEncoder.encodeCanvasSnapshotReq(fileId));
            LOG.info("Requested canvas snapshot for file " + fileId
                + " from " + activeViewers.get(0).getUsername()
                + " for new user " + session.getUsername());
        } else {
            // No active viewers — read from disk
            diskPool.submit(() -> {
                try {
                    byte[] pixelData = fileStore.readFile(fileId);
                    enqueue(session, MessageEncoder.encodeFileOpenData(
                        fileId, meta.getFilename(), meta.getOwner(),
                        meta.getWidth(), meta.getHeight(), pixelData
                    ));
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "File open failed: " + fileId, e);
                    enqueue(session, MessageEncoder.encodeError("File open failed: " + e.getMessage()));
                }
            });
        }
    }

    public void handleSnapshotData(ClientSession session, Message msg) throws IOException {
        MessageDecoder.CanvasSnapshotDataPayload snap =
            MessageDecoder.decodeCanvasSnapshotData(msg.getPayload());
        int fileId = snap.fileId;

        // Serve any sessions waiting for this file to open
        List<PendingOpenRequest> pending = pendingOpens.remove(fileId);
        if (pending != null) {
            for (PendingOpenRequest req : pending) {
                enqueue(req.waitingSession, MessageEncoder.encodeFileOpenData(
                    fileId, req.filename, req.owner, req.width, req.height, snap.pixelData
                ));
                LOG.info("Served live canvas snapshot for file " + fileId
                    + " to " + req.waitingSession.getUsername());
            }
        }

        // Broadcast updated canvas to all other viewers (handles undo sync etc.)
        byte[] update = MessageEncoder.encodeCanvasUpdate(
            fileId, snap.width, snap.height, snap.pixelData);
        registry.broadcastToFileViewers(fileId, update, session.getSessionId());
        LOG.fine("CANVAS_UPDATE broadcast for file " + fileId + " by " + session.getUsername());
    }

    public void handleSave(ClientSession session, Message msg) {
        // Transition to SAVING happens in dispatcher before calling here
        diskPool.submit(() -> {
            try {
                MessageDecoder.FileSaveReqPayload req = MessageDecoder.decodeFileSaveReq(msg.getPayload());
                fileStore.writeFile(req.fileId, req.pixelData);
                enqueue(session, MessageEncoder.encodeFileSaveOk(req.fileId));
                LOG.info("File saved: " + req.fileId + " by " + session.getUsername());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "File save failed", e);
                enqueue(session, MessageEncoder.encodeError("File save failed: " + e.getMessage()));
            } finally {
                // Return to ACTIVE state regardless of success
                session.transitionTo(multiuserpaint.common.FSMState.ACTIVE);
            }
        });
    }

    public void handleDelete(ClientSession session, Message msg) throws IOException {
        int fileId = MessageDecoder.decodeFileDeleteReq(msg.getPayload());

        if (!fileStore.isOwner(fileId, session.getUsername())) {
            enqueue(session, MessageEncoder.encodeFileDeleteErr(fileId, "Not the owner"));
            return;
        }

        diskPool.submit(() -> {
            try {
                fileStore.deleteFile(fileId);
                enqueue(session, MessageEncoder.encodeFileDeleteOk(fileId));
                broadcastFileList();
                LOG.info("File deleted: " + fileId + " by " + session.getUsername());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "File delete failed", e);
                enqueue(session, MessageEncoder.encodeError("File delete failed: " + e.getMessage()));
            }
        });
    }

    public void handleList(ClientSession session, Message msg) throws IOException {
        List<FileMetadata> files = fileStore.listAll();
        enqueue(session, MessageEncoder.encodeFileListResp(files));
    }

    public void performAutoSave(int fileId, byte[] pixelData) {
        diskPool.submit(() -> {
            try {
                fileStore.writeFile(fileId, pixelData);
                long ts = System.currentTimeMillis();
                byte[] notify = MessageEncoder.encodeAutosaveNotify(fileId, ts);
                for (ClientSession s : registry.getSessionsWithFileOpen(fileId)) {
                    enqueue(s, notify);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Auto-save failed for file " + fileId, e);
            }
        });
    }

    private void broadcastFileList() {
        try {
            List<FileMetadata> files = fileStore.listAll();
            byte[] listMsg = MessageEncoder.encodeFileListResp(files);
            registry.broadcastToAll(listMsg, -1); // -1 = send to everyone
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Broadcast file list failed", e);
        }
    }

    private void enqueue(ClientSession session, byte[] data) {
        session.writeQueue.offer(ByteBuffer.wrap(data));
    }
}
