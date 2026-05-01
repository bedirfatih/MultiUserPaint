package multiuserpaint.client.gui;

import multiuserpaint.common.MessageDecoder;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Left sidebar showing shared files on the server.
 * Double-click a file to open it.
 */
public class FileListPanel extends JPanel {
    private final DefaultListModel<FileEntry> listModel = new DefaultListModel<>();
    private final JList<FileEntry> fileList = new JList<>(listModel);

    public FileListPanel(Consumer<FileEntry> onFileOpen) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Shared Files"));
        setPreferredSize(new Dimension(200, 0));

        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FileEntry selected = fileList.getSelectedValue();
                    if (selected != null) onFileOpen.accept(selected);
                }
            }
        });

        add(new JScrollPane(fileList), BorderLayout.CENTER);
    }

    /** Update the file list (must be called on EDT). */
    public void setFiles(List<MessageDecoder.FileListEntry> files) {
        listModel.clear();
        for (MessageDecoder.FileListEntry f : files) {
            listModel.addElement(new FileEntry(f.fileId, f.filename, f.owner, f.width, f.height));
        }
    }

    public static class FileEntry {
        public final int fileId;
        public final String filename, owner;
        public final int width, height;

        public FileEntry(int fileId, String filename, String owner, int width, int height) {
            this.fileId = fileId; this.filename = filename;
            this.owner = owner; this.width = width; this.height = height;
        }

        @Override
        public String toString() {
            return filename + " (" + owner + ")";
        }
    }
}
