package multiuserpaint.server;

import multiuserpaint.common.ProtocolConstants;
import multiuserpaint.mq.MQConfig;
import multiuserpaint.server.handlers.*;
import multiuserpaint.server.mq.MQBroker;
import multiuserpaint.server.mq.MQServerTransport;
import multiuserpaint.server.store.*;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point for the MultiUserPaint server.
 *
 * Usage (socket mode – original):
 *   java multiuserpaint.server.PaintServer [port] [filesDir]
 *
 * Usage (MQ mode):
 *   java multiuserpaint.server.PaintServer --mq [mqHost] [filesDir]
 *
 * Examples:
 *   java -jar MultiUserPaintServer.jar                      # socket, port 9090
 *   java -jar MultiUserPaintServer.jar 9091                 # socket, port 9091
 *   java -jar MultiUserPaintServer.jar --mq                 # MQ, localhost
 *   java -jar MultiUserPaintServer.jar --mq 192.168.1.5     # MQ, remote broker
 */
public class PaintServer {
    private static final Logger LOG = Logger.getLogger(PaintServer.class.getName());

    private final int    port;
    private final String filesDirPath;
    private final boolean mqMode;
    private final String  mqHost;

    private SessionRegistry   registry;
    private FileStore         fileStore;
    private ExecutorService   diskPool;
    private NIOSelector       nioSelector;
    private MQBroker          mqBroker;
    private MQServerTransport mqTransport;
    private AutoSaveScheduler autoSaveScheduler;

    public PaintServer(int port, String filesDirPath) {
        this.port        = port;
        this.filesDirPath = filesDirPath;
        this.mqMode      = false;
        this.mqHost      = null;
    }

    public PaintServer(String mqHost, String filesDirPath) {
        this.port         = 0;
        this.filesDirPath = filesDirPath;
        this.mqMode       = true;
        this.mqHost       = mqHost;
    }

    public void start() throws Exception {
        // Shared infrastructure
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

        // Dispatcher now receives registry so USER_LIST_RESP is populated
        MessageDispatcher dispatcher = new MessageDispatcher(
                loginHandler, fileHandler, drawHandler, clipboardHandler, registry);

        // Auto-save
        autoSaveScheduler = new AutoSaveScheduler(
                registry, fileStore, fileHandler,
                ProtocolConstants.AUTO_SAVE_INTERVAL_SECONDS);
        autoSaveScheduler.start();

        if (mqMode) {
            startMQMode(dispatcher);
        } else {
            startSocketMode(dispatcher);
        }
    }

    // ── Socket mode ──────────────────────────────────────────────────────────

    private void startSocketMode(MessageDispatcher dispatcher) throws IOException {
        nioSelector = new NIOSelector(port, dispatcher, registry);
        nioSelector.start();
        LOG.info("PaintServer started in SOCKET mode on port " + port
                + " | files: " + filesDirPath);
    }

    // ── MQ mode ──────────────────────────────────────────────────────────────

    private void startMQMode(MessageDispatcher dispatcher) throws Exception {
        mqBroker = new MQBroker(mqHost, MQConfig.DEFAULT_AMQP_PORT);
        mqBroker.connect();

        registry.setMQBroker(mqBroker);

        mqTransport = new MQServerTransport(mqBroker, dispatcher, registry);
        mqTransport.start();

        LOG.info("PaintServer started in MQ mode | broker=" + mqHost
                + " | files: " + filesDirPath);
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    public void stop() {
        if (nioSelector != null)       nioSelector.stop();
        if (mqTransport != null)       mqTransport.stop();
        if (mqBroker != null)          mqBroker.close();
        if (autoSaveScheduler != null) autoSaveScheduler.stop();
        if (diskPool != null)          diskPool.shutdownNow();
        LOG.info("PaintServer stopped.");
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        PaintServer server;

        if (args.length > 0 && "--mq".equals(args[0])) {
            // MQ mode
            String host     = args.length > 1 ? args[1] : MQConfig.DEFAULT_HOST;
            String filesDir = args.length > 2 ? args[2] : "files";
            server = new PaintServer(host, filesDir);
        } else {
            // Socket mode
            int port = ProtocolConstants.DEFAULT_PORT;
            if (args.length > 0) {
                try { port = Integer.parseInt(args[0]); }
                catch (NumberFormatException ignored) {}
            }
            String filesDir = args.length > 1 ? args[1] : "files";
            server = new PaintServer(port, filesDir);
        }

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "Shutdown"));
        LOG.info("Press Ctrl+C to stop.");
    }
}
