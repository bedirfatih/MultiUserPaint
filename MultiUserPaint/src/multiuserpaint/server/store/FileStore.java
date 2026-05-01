package multiuserpaint.server.store;

import multiuserpaint.common.ProtocolConstants;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Manages file metadata and pixel data on disk.
 * Thread-safe: read/write may be called from the disk thread pool.
 */
public class FileStore {
    private final Path filesDir;
    private final Map<Integer, FileMetadata> files = new ConcurrentHashMap<>();
    private final AtomicInteger nextFileId = new AtomicInteger(1);

    public FileStore(String filesDirPath) throws IOException {
        this.filesDir = Paths.get(filesDirPath);
        Files.createDirectories(filesDir);
    }

    public synchronized int createFile(String owner, String filename, int width, int height) throws IOException {
        int fileId = nextFileId.getAndIncrement();
        // Create a blank white canvas
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        writeImageToDisk(fileId, img);
        FileMetadata meta = new FileMetadata(fileId, filename, owner, width, height);
        files.put(fileId, meta);
        return fileId;
    }

    public byte[] readFile(int fileId) throws IOException {
        FileMetadata meta = files.get(fileId);
        if (meta == null) throw new IOException("File not found: " + fileId);
        Path path = filePath(fileId);
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) throw new IOException("Cannot read image: " + path);
        return imageToPixelBytes(img, meta.getWidth(), meta.getHeight());
    }

    public void writeFile(int fileId, byte[] pixelData) throws IOException {
        FileMetadata meta = files.get(fileId);
        if (meta == null) throw new IOException("File not found: " + fileId);
        BufferedImage img = pixelBytesToImage(pixelData, meta.getWidth(), meta.getHeight());
        writeImageToDisk(fileId, img);
        meta.setLastModified(System.currentTimeMillis());
    }

    public void deleteFile(int fileId) throws IOException {
        FileMetadata meta = files.remove(fileId);
        if (meta == null) return;
        Files.deleteIfExists(filePath(fileId));
    }

    public boolean isOwner(int fileId, String username) {
        FileMetadata meta = files.get(fileId);
        return meta != null && meta.getOwner().equals(username);
    }

    public FileMetadata getMetadata(int fileId) {
        return files.get(fileId);
    }

    public List<FileMetadata> listAll() {
        return new ArrayList<>(files.values());
    }

    public boolean exists(int fileId) {
        return files.containsKey(fileId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Path filePath(int fileId) {
        return filesDir.resolve("file_" + fileId + ".png");
    }

    private void writeImageToDisk(int fileId, BufferedImage img) throws IOException {
        ImageIO.write(img, "PNG", filePath(fileId).toFile());
    }

    /** Convert ARGB pixel bytes (width*height*4) to BufferedImage */
    public static BufferedImage pixelBytesToImage(byte[] data, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int base = i * 4;
            int a = data[base]     & 0xFF;
            int r = data[base + 1] & 0xFF;
            int g = data[base + 2] & 0xFF;
            int b = data[base + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    /** Convert BufferedImage to raw ARGB bytes */
    public static byte[] imageToPixelBytes(BufferedImage img, int width, int height) {
        int[] pixels = img.getRGB(0, 0, width, height, null, 0, width);
        byte[] data = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            int base = i * 4;
            data[base]     = (byte) ((pixels[i] >> 24) & 0xFF); // A
            data[base + 1] = (byte) ((pixels[i] >> 16) & 0xFF); // R
            data[base + 2] = (byte) ((pixels[i] >> 8)  & 0xFF); // G
            data[base + 3] = (byte) (pixels[i]          & 0xFF); // B
        }
        return data;
    }
}
