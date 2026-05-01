package multiuserpaint.server.store;

import multiuserpaint.server.ClientSession;
import multiuserpaint.server.handlers.FileHandler;

import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically triggers auto-save for all open files.
 * Uses ScheduledExecutorService; disk I/O delegated to FileHandler.
 */
public class AutoSaveScheduler {
    private static final Logger LOG = Logger.getLogger(AutoSaveScheduler.class.getName());

    private final SessionRegistry registry;
    private final FileStore fileStore;
    private final FileHandler fileHandler;
    private final int intervalSeconds;
    private ScheduledExecutorService scheduler;

    public AutoSaveScheduler(SessionRegistry registry, FileStore fileStore,
                             FileHandler fileHandler, int intervalSeconds) {
        this.registry = registry;
        this.fileStore = fileStore;
        this.fileHandler = fileHandler;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoSave");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runAutoSave,
            intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("AutoSave scheduler started (interval=" + intervalSeconds + "s)");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void runAutoSave() {
        // Collect all file IDs currently open across all sessions
        java.util.Set<Integer> openFileIds = new java.util.HashSet<>();
        for (ClientSession session : registry.getAllSessions()) {
            openFileIds.addAll(session.getOpenFileIds());
        }

        for (int fileId : openFileIds) {
            if (!fileStore.exists(fileId)) continue;
            try {
                byte[] pixelData = fileStore.readFile(fileId);
                fileHandler.performAutoSave(fileId, pixelData);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "AutoSave read failed for file " + fileId, e);
            }
        }
    }
}
