package multiuserpaint.client.gui;

import multiuserpaint.client.network.ConnectionManager;
import multiuserpaint.common.*;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application window.
 * Houses toolbar, file list sidebar, tabbed canvas area, and status bar.
 * All methods must be called on the EDT.
 */
public class MainFrame extends JFrame {
    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());

    private final ConnectionManager connection;

    private final JTabbedPane tabPane    = new JTabbedPane();
    private final FileListPanel fileListPanel;
    private final JLabel statusBar       = new JLabel(" Ready");
    private final Map<Integer, CanvasPanel> openCanvases = new HashMap<>(); // fileId -> canvas

    // Currently selected tool/color/stroke (shared across tabs)
    private byte currentTool   = CanvasPanel.TOOL_PENCIL;
    private Color currentColor = Color.BLACK;
    private int strokeWidth    = 3;

    public MainFrame() {
        super("MultiUserPaint");
        connection = new ConnectionManager(this::onMessage, this::onDisconnect);

        fileListPanel = new FileListPanel(this::openFile);

        buildMenuBar();
        buildUI();

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem    = new JMenuItem("New Canvas...");
        JMenuItem saveItem   = new JMenuItem("Save Current");
        JMenuItem refreshItem = new JMenuItem("Refresh File List");
        newItem.addActionListener(e -> showNewFileDialog());
        saveItem.addActionListener(e -> saveCurrentCanvas());
        refreshItem.addActionListener(e -> connection.sendFileListReq());
        fileMenu.add(newItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(refreshItem);
        mb.add(fileMenu);

        JMenu editMenu = new JMenu("Edit");
        JMenuItem copyItem   = new JMenuItem("Copy Selection");
        JMenuItem cutItem    = new JMenuItem("Cut Selection");
        JMenuItem pasteItem  = new JMenuItem("Paste");
        JMenuItem selectItem = new JMenuItem("Select Mode");
        copyItem.addActionListener(e -> copySelection(false));
        cutItem.addActionListener(e -> copySelection(true));
        pasteItem.addActionListener(e -> connection.sendClipboardPasteReq());
        selectItem.addActionListener(e -> {
            CanvasPanel c = getActiveCanvas();
            if (c != null) c.setSelectMode(true);
        });
        editMenu.add(selectItem);
        editMenu.addSeparator();
        editMenu.add(copyItem);
        editMenu.add(cutItem);
        editMenu.add(pasteItem);
        mb.add(editMenu);

        JMenu serverMenu = new JMenu("Server");
        JMenuItem connectItem    = new JMenuItem("Connect...");
        JMenuItem disconnectItem = new JMenuItem("Disconnect");
        connectItem.addActionListener(e -> showLoginDialog());
        disconnectItem.addActionListener(e -> connection.disconnect());
        serverMenu.add(connectItem);
        serverMenu.add(disconnectItem);
        mb.add(serverMenu);

        setJMenuBar(mb);
    }

    private void buildUI() {
        ToolbarPanel toolbar = new ToolbarPanel(
            tool  -> { currentTool = tool; applyToolToActiveCanvas(); },
            color -> { currentColor = color; applyColorToActiveCanvas(); },
            size  -> { strokeWidth = size; applyStrokeToActiveCanvas(); }
        );

        JPanel center = new JPanel(new BorderLayout());
        center.add(fileListPanel, BorderLayout.WEST);
        center.add(tabPane, BorderLayout.CENTER);

        statusBar.setBorder(BorderFactory.createEtchedBorder());

        add(toolbar, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
    }

    // -------------------------------------------------------------------------
    // Tab management
    // -------------------------------------------------------------------------

    public void addFileTab(int fileId, String filename, byte[] pixelData, int width, int height) {
        if (openCanvases.containsKey(fileId)) {
            // Tab already open; switch to it
            int idx = tabPane.indexOfTab(filename);
            if (idx >= 0) tabPane.setSelectedIndex(idx);
            return;
        }

        CanvasPanel canvas = new CanvasPanel(width, height);
        canvas.fromPixelBytes(pixelData, width, height);
        canvas.setCurrentTool(currentTool);
        canvas.setCurrentColor(currentColor);
        canvas.setStrokeWidth(strokeWidth);
        canvas.setOnDrawFinished(event -> {
            if (connection.isConnected()) {
                connection.sendDrawEvent(fileId, event.tool, event.colorArgb,
                    event.strokeWidth, event.x1, event.y1, event.x2, event.y2);
            }
        });

        JScrollPane scroll = new JScrollPane(canvas);
        tabPane.addTab(filename, scroll);
        tabPane.setSelectedComponent(scroll);
        openCanvases.put(fileId, canvas);
        setStatus("Opened: " + filename);
    }

    public CanvasPanel getActiveCanvas() {
        Component selected = tabPane.getSelectedComponent();
        if (!(selected instanceof JScrollPane)) return null;
        Component view = ((JScrollPane) selected).getViewport().getView();
        return view instanceof CanvasPanel ? (CanvasPanel) view : null;
    }

    private Integer getActiveFileId() {
        CanvasPanel active = getActiveCanvas();
        if (active == null) return null;
        for (Map.Entry<Integer, CanvasPanel> e : openCanvases.entrySet()) {
            if (e.getValue() == active) return e.getKey();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Tool/color/stroke propagation
    // -------------------------------------------------------------------------

    private void applyToolToActiveCanvas() {
        CanvasPanel c = getActiveCanvas();
        if (c != null) c.setCurrentTool(currentTool);
    }

    private void applyColorToActiveCanvas() {
        CanvasPanel c = getActiveCanvas();
        if (c != null) c.setCurrentColor(currentColor);
    }

    private void applyStrokeToActiveCanvas() {
        CanvasPanel c = getActiveCanvas();
        if (c != null) c.setStrokeWidth(strokeWidth);
    }

    // -------------------------------------------------------------------------
    // Network actions
    // -------------------------------------------------------------------------

    private void openFile(FileListPanel.FileEntry entry) {
        if (!connection.isConnected()) {
            JOptionPane.showMessageDialog(this, "Not connected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        connection.sendFileOpenReq(entry.fileId);
        setStatus("Opening: " + entry.filename + "...");
    }

    private void showNewFileDialog() {
        if (!connection.isConnected()) {
            JOptionPane.showMessageDialog(this, "Connect to server first.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JTextField nameField = new JTextField("canvas", 15);
        JTextField widthField = new JTextField("800", 6);
        JTextField heightField = new JTextField("600", 6);
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.add(new JLabel("File name:")); panel.add(nameField);
        panel.add(new JLabel("Width:"));    panel.add(widthField);
        panel.add(new JLabel("Height:"));   panel.add(heightField);

        int result = JOptionPane.showConfirmDialog(this, panel, "New Canvas",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String name = nameField.getText().trim();
        if (name.isEmpty()) name = "canvas";
        try {
            int w = Integer.parseInt(widthField.getText().trim());
            int h = Integer.parseInt(heightField.getText().trim());
            connection.sendFileCreateReq(name, w, h);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid size.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveCurrentCanvas() {
        Integer fileId = getActiveFileId();
        CanvasPanel canvas = getActiveCanvas();
        if (fileId == null || canvas == null) {
            JOptionPane.showMessageDialog(this, "No canvas open.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        connection.sendFileSaveReq(fileId, canvas.toPixelBytes());
        setStatus("Saving...");
    }

    private void copySelection(boolean cut) {
        Integer fileId = getActiveFileId();
        CanvasPanel canvas = getActiveCanvas();
        if (fileId == null || canvas == null) return;

        BufferedImage sel = canvas.getSelectionImage();
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "No selection. Use Edit → Select Mode first.", "Info",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Rectangle r = canvas.getSelectionRect();
        byte[] pixels = multiuserpaint.server.store.FileStore.imageToPixelBytes(sel, sel.getWidth(), sel.getHeight());

        if (cut) {
            connection.sendClipboardCut(fileId, r.x, r.y, r.width, r.height, pixels);
            canvas.clearSelection();
        } else {
            connection.sendClipboardCopy(fileId, r.x, r.y, r.width, r.height, pixels);
        }
        canvas.setSelectMode(false);
        setStatus(cut ? "Cut region" : "Copied region");
    }

    // -------------------------------------------------------------------------
    // Incoming message handler (called on EDT via SwingUtilities.invokeLater)
    // -------------------------------------------------------------------------

    private void onMessage(Message msg) {
        try {
            switch (msg.getType()) {
                case LOGIN_OK: {
                    MessageDecoder.LoginOkPayload p = MessageDecoder.decodeLoginOk(msg.getPayload());
                    connection.onLoginOk(p.sessionId);
                    setStatus("Connected as " + connection.getUsername());
                    connection.sendFileListReq();
                    break;
                }
                case LOGIN_ERR: {
                    MessageDecoder.LoginErrPayload p = MessageDecoder.decodeLoginErr(msg.getPayload());
                    JOptionPane.showMessageDialog(this, "Login failed: " + p.message, "Error",
                        JOptionPane.ERROR_MESSAGE);
                    connection.disconnect();
                    break;
                }
                case FILE_LIST_RESP: {
                    List<MessageDecoder.FileListEntry> files =
                        MessageDecoder.decodeFileListResp(msg.getPayload());
                    fileListPanel.setFiles(files);
                    break;
                }
                case FILE_CREATE_OK: {
                    MessageDecoder.FileCreateOkPayload p = MessageDecoder.decodeFileCreateOk(msg.getPayload());
                    setStatus("Created: " + p.filename + " (id=" + p.fileId + ")");
                    connection.sendFileListReq();
                    connection.sendFileOpenReq(p.fileId);
                    break;
                }
                case FILE_OPEN_DATA: {
                    MessageDecoder.FileOpenDataPayload p = MessageDecoder.decodeFileOpenData(msg.getPayload());
                    addFileTab(p.fileId, p.filename, p.pixelData, p.width, p.height);
                    break;
                }
                case FILE_SAVE_OK:
                    setStatus("Saved successfully.");
                    break;
                case FILE_DELETE_OK:
                    setStatus("File deleted.");
                    connection.sendFileListReq();
                    break;
                case FILE_DELETE_ERR:
                    setStatus("Delete failed: " + MessageDecoder.decodeError(msg.getPayload()));
                    break;
                case DRAW_BROADCAST: {
                    MessageDecoder.DrawBroadcastPayload p =
                        MessageDecoder.decodeDrawBroadcast(msg.getPayload());
                    CanvasPanel canvas = openCanvases.get(p.fileId);
                    if (canvas != null) canvas.applyDrawCommand(p);
                    break;
                }
                case CLIPBOARD_DATA: {
                    MessageDecoder.ClipboardDataPayload p =
                        MessageDecoder.decodeClipboardData(msg.getPayload());
                    CanvasPanel canvas = getActiveCanvas();
                    if (canvas != null) {
                        BufferedImage img = multiuserpaint.server.store.FileStore
                            .pixelBytesToImage(p.pixelData, p.width, p.height);
                        // Paste at center for simplicity
                        canvas.pasteImage(img, 50, 50);
                        setStatus("Pasted clipboard (" + p.width + "x" + p.height + ")");
                    }
                    break;
                }
                case USER_JOIN: {
                    MessageDecoder.UserEventPayload p = MessageDecoder.decodeUserEvent(msg.getPayload());
                    setStatus("User joined: " + p.username);
                    break;
                }
                case USER_LEAVE: {
                    MessageDecoder.UserEventPayload p = MessageDecoder.decodeUserEvent(msg.getPayload());
                    setStatus("User left: " + p.username);
                    break;
                }
                case AUTOSAVE_NOTIFY: {
                    MessageDecoder.AutosaveNotifyPayload p =
                        MessageDecoder.decodeAutosaveNotify(msg.getPayload());
                    setStatus("Auto-saved file " + p.fileId + " at "
                        + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(p.timestamp)));
                    break;
                }
                case PONG:
                    // keepalive ack, no UI action needed
                    break;
                case ERROR:
                    JOptionPane.showMessageDialog(this,
                        "Server error: " + MessageDecoder.decodeError(msg.getPayload()),
                        "Error", JOptionPane.ERROR_MESSAGE);
                    break;
                default:
                    LOG.warning("Unhandled message: " + msg.getType());
                    break;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Message decode error: " + msg.getType(), e);
        }
    }

    private void onDisconnect() {
        setStatus("Disconnected from server.");
        JOptionPane.showMessageDialog(this, "Connection to server lost.", "Disconnected",
            JOptionPane.WARNING_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Login dialog
    // -------------------------------------------------------------------------

    public void showLoginDialog() {
        LoginDialog dlg = new LoginDialog(this);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;

        try {
            connection.connect(dlg.getHost(), dlg.getPort());
            connection.sendLoginReq(dlg.getUsername());
            setStatus("Logging in as " + dlg.getUsername() + "...");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Cannot connect: " + e.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------------------------------
    // Status bar
    // -------------------------------------------------------------------------

    public void setStatus(String text) {
        statusBar.setText(" " + text);
    }
}
