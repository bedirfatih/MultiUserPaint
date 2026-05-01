package multiuserpaint.client;

import multiuserpaint.client.gui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.logging.Logger;

/**
 * Entry point for the MultiUserPaint client.
 */
public class PaintClient {
    private static final Logger LOG = Logger.getLogger(PaintClient.class.getName());

    public static void main(String[] args) {
        // Use system look-and-feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            // Show login dialog on startup
            frame.showLoginDialog();
        });
    }
}
