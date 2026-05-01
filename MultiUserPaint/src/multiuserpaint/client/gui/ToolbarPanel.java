package multiuserpaint.client.gui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Toolbar with tool buttons, color picker, and stroke width selector.
 */
public class ToolbarPanel extends JPanel {
    private final Consumer<Byte> onToolSelected;
    private final Consumer<Color> onColorSelected;
    private final Consumer<Integer> onStrokeSelected;

    private JButton colorButton;
    private Color currentColor = Color.BLACK;

    public ToolbarPanel(Consumer<Byte> onToolSelected,
                        Consumer<Color> onColorSelected,
                        Consumer<Integer> onStrokeSelected) {
        this.onToolSelected   = onToolSelected;
        this.onColorSelected  = onColorSelected;
        this.onStrokeSelected = onStrokeSelected;
        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        setBorder(BorderFactory.createEtchedBorder());
        buildTools();
    }

    private void buildTools() {
        // Tool buttons
        addToolButton("Pencil",   CanvasPanel.TOOL_PENCIL);
        addToolButton("Line",     CanvasPanel.TOOL_LINE);
        addToolButton("Rect",     CanvasPanel.TOOL_RECT);
        addToolButton("Ellipse",  CanvasPanel.TOOL_ELLIPSE);
        addToolButton("Fill",     CanvasPanel.TOOL_FILL);
        addToolButton("Eraser",   CanvasPanel.TOOL_ERASER);

        add(new JSeparator(SwingConstants.VERTICAL));

        // Color picker button
        colorButton = new JButton("Color");
        colorButton.setBackground(currentColor);
        colorButton.setForeground(Color.WHITE);
        colorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Pick a Color", currentColor);
            if (chosen != null) {
                currentColor = chosen;
                colorButton.setBackground(chosen);
                colorButton.setForeground(chosen.getRed() + chosen.getGreen() + chosen.getBlue() < 382
                    ? Color.WHITE : Color.BLACK);
                onColorSelected.accept(chosen);
            }
        });
        add(colorButton);

        // Stroke width
        add(new JLabel("  Size:"));
        Integer[] sizes = {1, 2, 3, 5, 8, 12, 20};
        JComboBox<Integer> strokeBox = new JComboBox<>(sizes);
        strokeBox.setSelectedItem(3);
        strokeBox.addActionListener(e -> onStrokeSelected.accept((Integer) strokeBox.getSelectedItem()));
        add(strokeBox);
    }

    private void addToolButton(String label, byte toolCode) {
        JButton btn = new JButton(label);
        btn.addActionListener(e -> onToolSelected.accept(toolCode));
        add(btn);
    }
}
