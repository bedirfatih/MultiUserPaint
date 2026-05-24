package multiuserpaint.client.gui;

import multiuserpaint.common.MessageDecoder;
import multiuserpaint.server.store.FileStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The drawing surface. Uses dual-buffer rendering:
 * - backBuffer:   committed pixel state (received from server / confirmed draws)
 * - overlayBuffer: current in-progress stroke (transparent)
 */
public class CanvasPanel extends JPanel {

    // Tool codes matching the protocol
    public static final byte TOOL_PENCIL  = 0x01;
    public static final byte TOOL_LINE    = 0x02;
    public static final byte TOOL_RECT    = 0x03;
    public static final byte TOOL_ELLIPSE = 0x04;
    public static final byte TOOL_FILL    = 0x05;
    public static final byte TOOL_ERASER  = 0x06;

    private BufferedImage backBuffer;
    private BufferedImage overlayBuffer;

    private final int canvasWidth;
    private final int canvasHeight;

    // Current tool state
    private byte currentTool = TOOL_PENCIL;
    private Color currentColor = Color.BLACK;
    private int strokeWidth = 3;

    // Mouse drag tracking
    private int startX, startY, lastX, lastY;
    private boolean dragging = false;

    // Last known mouse position (for paste-at-cursor)
    private int lastMouseX = 0, lastMouseY = 0;

    // Undo stack
    private final java.util.Deque<BufferedImage> undoStack = new java.util.ArrayDeque<>();
    private static final int MAX_UNDO = 20;

    // Selection for clipboard
    private Rectangle selectionRect = null;
    private boolean selectMode = false;

    // Callbacks
    private Consumer<DrawEvent> onDrawFinished; // called when a stroke is committed

    public CanvasPanel(int width, int height) {
        this.canvasWidth = width;
        this.canvasHeight = height;
        setPreferredSize(new Dimension(width, height));

        backBuffer    = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        overlayBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // Fill white background
        Graphics2D g = backBuffer.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { onPressed(e); }
            @Override public void mouseReleased(MouseEvent e) { onReleased(e); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { lastMouseX = e.getX(); lastMouseY = e.getY(); onDragged(e); }
            @Override public void mouseMoved(MouseEvent e)   { lastMouseX = e.getX(); lastMouseY = e.getY(); }
        });
    }

    // -------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(backBuffer, 0, 0, null);
        g.drawImage(overlayBuffer, 0, 0, null);

        // Draw selection rectangle if active
        if (selectionRect != null) {
            g.setColor(new Color(0, 120, 215, 100));
            g.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            g.setColor(new Color(0, 120, 215));
            g.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
        }
    }

    // -------------------------------------------------------------------------
    // Mouse events
    // -------------------------------------------------------------------------

    private void onPressed(MouseEvent e) {
        pushUndo(); // snapshot before any draw operation
        startX = lastX = e.getX();
        startY = lastY = e.getY();
        dragging = true;

        if (selectMode) {
            selectionRect = new Rectangle(startX, startY, 0, 0);
        } else if (currentTool == TOOL_FILL) {
            floodFill(startX, startY, currentColor);
            repaint();
        }
    }

    private void onDragged(MouseEvent e) {
        if (!dragging) return;
        int cx = e.getX(), cy = e.getY();

        if (selectMode) {
            int x = Math.min(startX, cx);
            int y = Math.min(startY, cy);
            int w = Math.abs(cx - startX);
            int h = Math.abs(cy - startY);
            selectionRect = new Rectangle(x, y, w, h);
            repaint();
            return;
        }

        clearOverlay();
        Graphics2D g = overlayBuffer.createGraphics();
        applyRenderingHints(g);
        g.setColor(currentTool == TOOL_ERASER ? Color.WHITE : currentColor);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (currentTool) {
            case TOOL_PENCIL:
            case TOOL_ERASER:
                // Draw directly on backBuffer for smooth freehand
                clearOverlay();
                Graphics2D bg = backBuffer.createGraphics();
                applyRenderingHints(bg);
                bg.setColor(currentTool == TOOL_ERASER ? Color.WHITE : currentColor);
                bg.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                bg.drawLine(lastX, lastY, cx, cy);
                bg.dispose();
                // Also notify server for each segment
                if (onDrawFinished != null) {
                    onDrawFinished.accept(new DrawEvent(currentTool, currentColor.getRGB(),
                        (short) strokeWidth, lastX, lastY, cx, cy));
                }
                break;
            case TOOL_LINE:
                g.drawLine(startX, startY, cx, cy);
                break;
            case TOOL_RECT:
                int rx = Math.min(startX, cx), ry = Math.min(startY, cy);
                g.drawRect(rx, ry, Math.abs(cx - startX), Math.abs(cy - startY));
                break;
            case TOOL_ELLIPSE:
                int ex = Math.min(startX, cx), ey = Math.min(startY, cy);
                g.drawOval(ex, ey, Math.abs(cx - startX), Math.abs(cy - startY));
                break;
        }
        g.dispose();
        lastX = cx; lastY = cy;
        repaint();
    }

    private void onReleased(MouseEvent e) {
        if (!dragging) return;
        dragging = false;
        int cx = e.getX(), cy = e.getY();

        if (selectMode) return; // selection stays visible

        if (currentTool != TOOL_PENCIL && currentTool != TOOL_ERASER && currentTool != TOOL_FILL) {
            // Merge overlay into backBuffer
            Graphics2D bg = backBuffer.createGraphics();
            bg.drawImage(overlayBuffer, 0, 0, null);
            bg.dispose();
            clearOverlay();

            // Notify server
            if (onDrawFinished != null) {
                onDrawFinished.accept(new DrawEvent(currentTool, currentColor.getRGB(),
                    (short) strokeWidth, startX, startY, cx, cy));
            }
        }
        repaint();
    }

    // -------------------------------------------------------------------------
    // Network-driven draw (from DRAW_BROADCAST)
    // -------------------------------------------------------------------------

    /** Apply a draw command received from server. Must be called on EDT. */
    public void applyDrawCommand(MessageDecoder.DrawBroadcastPayload cmd) {
        Graphics2D g = backBuffer.createGraphics();
        applyRenderingHints(g);

        Color c = new Color(cmd.colorArgb, true);
        g.setColor(cmd.toolType == TOOL_ERASER ? Color.WHITE : c);
        g.setStroke(new BasicStroke(cmd.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (cmd.toolType) {
            case TOOL_PENCIL:
            case TOOL_ERASER:
                g.drawLine(cmd.x1, cmd.y1, cmd.x2, cmd.y2);
                break;
            case TOOL_LINE:
                g.drawLine(cmd.x1, cmd.y1, cmd.x2, cmd.y2);
                break;
            case TOOL_RECT:
                int rx = Math.min(cmd.x1, cmd.x2), ry = Math.min(cmd.y1, cmd.y2);
                g.drawRect(rx, ry, Math.abs(cmd.x2 - cmd.x1), Math.abs(cmd.y2 - cmd.y1));
                break;
            case TOOL_ELLIPSE:
                int ex = Math.min(cmd.x1, cmd.x2), ey = Math.min(cmd.y1, cmd.y2);
                g.drawOval(ex, ey, Math.abs(cmd.x2 - cmd.x1), Math.abs(cmd.y2 - cmd.y1));
                break;
            case TOOL_FILL:
                floodFill(cmd.x1, cmd.y1, c);
                break;
        }
        g.dispose();
        repaint();
    }

    // -------------------------------------------------------------------------
    // Clipboard support
    // -------------------------------------------------------------------------

    public BufferedImage getSelectionImage() {
        if (selectionRect == null || selectionRect.width == 0 || selectionRect.height == 0) return null;
        Rectangle r = selectionRect.intersection(new Rectangle(0, 0, canvasWidth, canvasHeight));
        return backBuffer.getSubimage(r.x, r.y, r.width, r.height);
    }

    public Rectangle getSelectionRect() { return selectionRect; }

    public void clearSelection() {
        if (selectionRect != null) {
            Graphics2D g = backBuffer.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            g.dispose();
            selectionRect = null;
            repaint();
        }
    }

    public void pasteImage(BufferedImage img, int x, int y) {
        Graphics2D g = backBuffer.createGraphics();
        g.drawImage(img, x, y, null);
        g.dispose();
        repaint();
    }

    // -------------------------------------------------------------------------
    // File data in/out
    // -------------------------------------------------------------------------

    public byte[] toPixelBytes() {
        return FileStore.imageToPixelBytes(backBuffer, canvasWidth, canvasHeight);
    }

    public void fromPixelBytes(byte[] data, int width, int height) {
        backBuffer = FileStore.pixelBytesToImage(data, width, height);
        undoStack.clear();
        repaint();
    }

    // -------------------------------------------------------------------------
    // Tool / color setters
    // -------------------------------------------------------------------------

    public void setCurrentTool(byte tool)      { this.currentTool = tool; selectMode = false; }
    public void setSelectMode(boolean on)       { this.selectMode = on; }
    public boolean isSelectMode()               { return selectMode; }
    public void cancelSelection()               { selectMode = false; selectionRect = null; repaint(); }
    public void setCurrentColor(Color color)    { this.currentColor = color; }
    public void setStrokeWidth(int width)       { this.strokeWidth = width; }
    public Color getCurrentColor()              { return currentColor; }
    public int getLastMouseX()                  { return lastMouseX; }
    public int getLastMouseY()                  { return lastMouseY; }
    public int getCanvasWidth()                 { return canvasWidth; }
    public int getCanvasHeight()                { return canvasHeight; }

    public void undo() {
        if (undoStack.isEmpty()) return;
        backBuffer = undoStack.pop();
        repaint();
    }

    public void selectAll() {
        selectMode = true;
        selectionRect = new Rectangle(0, 0, canvasWidth, canvasHeight);
        repaint();
    }

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    public void setOnDrawFinished(Consumer<DrawEvent> cb) { this.onDrawFinished = cb; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void pushUndo() {
        BufferedImage copy = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(backBuffer, 0, 0, null);
        g.dispose();
        undoStack.push(copy);
        if (undoStack.size() > MAX_UNDO) undoStack.pollLast();
    }

    private void clearOverlay() {
        Graphics2D g = overlayBuffer.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, canvasWidth, canvasHeight);
        g.dispose();
    }

    private void applyRenderingHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private void floodFill(int x, int y, Color fillColor) {
        if (x < 0 || x >= canvasWidth || y < 0 || y >= canvasHeight) return;
        int targetArgb = backBuffer.getRGB(x, y);
        int fillArgb = fillColor.getRGB();
        if (targetArgb == fillArgb) return;

        // BFS flood fill
        java.util.Queue<Point> queue = new java.util.ArrayDeque<>();
        queue.add(new Point(x, y));
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            if (p.x < 0 || p.x >= canvasWidth || p.y < 0 || p.y >= canvasHeight) continue;
            if (backBuffer.getRGB(p.x, p.y) != targetArgb) continue;
            backBuffer.setRGB(p.x, p.y, fillArgb);
            queue.add(new Point(p.x + 1, p.y));
            queue.add(new Point(p.x - 1, p.y));
            queue.add(new Point(p.x, p.y + 1));
            queue.add(new Point(p.x, p.y - 1));
        }
    }

    // -------------------------------------------------------------------------
    // DrawEvent record (for callback)
    // -------------------------------------------------------------------------

    public static class DrawEvent {
        public final byte tool;
        public final int colorArgb;
        public final short strokeWidth;
        public final int x1, y1, x2, y2;

        public DrawEvent(byte tool, int colorArgb, short strokeWidth,
                         int x1, int y1, int x2, int y2) {
            this.tool = tool; this.colorArgb = colorArgb;
            this.strokeWidth = strokeWidth;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }
}
