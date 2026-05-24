package multiuserpaint.server;

import multiuserpaint.common.ProtocolConstants;
import multiuserpaint.server.handlers.*;
import multiuserpaint.server.store.*;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point for the MultiUserPaint server.
 * Usage: java multiuserpaint.server.PaintServer [port]
 */
public class PaintServer {
    private static final Logger LOG = Logger.getLogger(PaintServer.class.getName());

    private final int port;
    private final String filesDirPath;

    private SessionRegistry registry;
    private FileStore fileStore;
    private ExecutorService diskPool;
    private NIOSelector nioSelector;
    private AutoSaveScheduler autoSaveScheduler;

    public PaintServer(int port, String filesDirPath) {
        this.port = port;
        this.filesDirPath = filesDirPath;
    }

    public void start() throws IOException {
        // Infrastructure
        registry  = new SessionRegistry();
        fileStore = new FileStore(filesDirPath);
        diskPool  = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DiskPool");
            t.setDaemon(true);
            return t;
        });

        // Handlers
        LoginHandler     loginHandler     = new LoginHandler(registry);
        FileHandler      fileHandler      = new FileHandler(fileStore, registry, diskPool);
        DrawHandler      drawHandler      = new DrawHandler(registry);
        ClipboardHandler clipboardHandler = new ClipboardHandler(registry);

        MessageDispatcher dispatcher = new MessageDispatcher(
            loginHandler, fileHandler, drawHandler, clipboardHandler);

        // Auto-save
        autoSaveScheduler = new AutoSaveScheduler(
            registry, fileStore, fileHandler,
            ProtocolConstants.AUTO_SAVE_INTERVAL_SECONDS);
        autoSaveScheduler.start();

        // NIO loop
        nioSelector = new NIOSelector(port, dispatcher, registry);
        nioSelector.start();

        LOG.info("PaintServer started on port " + port + " | files: " + filesDirPath);
    }

    public void stop() {
        if (nioSelector != null)       nioSelector.stop();
        if (autoSaveScheduler != null) autoSaveScheduler.stop();
        if (diskPool != null)          diskPool.shutdownNow();
        LOG.info("PaintServer stopped.");
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        int port = ProtocolConstants.DEFAULT_PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { /* use default */ }
        }

        String filesDir = "files";
        if (args.length > 1) filesDir = args[1];

        PaintServer server = new PaintServer(port, filesDir);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "Shutdown"));
        LOG.info("Press Ctrl+C to stop.");
    }
}
