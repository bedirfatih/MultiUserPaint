package multiuserpaint.client.gui;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog to collect server address, port, and username.
 */
public class LoginDialog extends JDialog {
    private final JTextField hostField    = new JTextField("localhost", 15);
    private final JTextField portField    = new JTextField("9090", 6);
    private final JTextField usernameField = new JTextField(15);
    private boolean confirmed = false;

    public LoginDialog(Frame parent) {
        super(parent, "Connect to Server", true);
        buildUI();
        pack();
        setLocationRelativeTo(parent);
    }

    private void buildUI() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Server Host:"), c);
        c.gridx = 1;               form.add(hostField, c);

        c.gridx = 0; c.gridy = 1; form.add(new JLabel("Port:"), c);
        c.gridx = 1;               form.add(portField, c);

        c.gridx = 0; c.gridy = 2; form.add(new JLabel("Username:"), c);
        c.gridx = 1;               form.add(usernameField, c);

        JButton connectBtn = new JButton("Connect");
        JButton cancelBtn  = new JButton("Cancel");
        connectBtn.addActionListener(e -> onConnect());
        cancelBtn.addActionListener(e -> dispose());

        // Press Enter to connect
        getRootPane().setDefaultButton(connectBtn);

        JPanel buttons = new JPanel();
        buttons.add(connectBtn);
        buttons.add(cancelBtn);

        setLayout(new BorderLayout(10, 10));
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    private void onConnect() {
        String user = usernameField.getText().trim();
        if (user.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a username.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (user.length() < 3 || user.length() > 16 || !user.matches("[a-zA-Z0-9_]+")) {
            JOptionPane.showMessageDialog(this,
                "Username must be 3-16 chars: letters, digits, underscore.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() { return confirmed; }
    public String getHost()      { return hostField.getText().trim(); }
    public int getPort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return 9090; }
    }
    public String getUsername()  { return usernameField.getText().trim(); }
}
