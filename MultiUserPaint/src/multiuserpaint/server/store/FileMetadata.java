package multiuserpaint.server.store;

public class FileMetadata {
    private final int fileId;
    private String filename;
    private final String owner;
    private int width;
    private int height;
    private long lastModified;

    public FileMetadata(int fileId, String filename, String owner, int width, int height) {
        this.fileId = fileId;
        this.filename = filename;
        this.owner = owner;
        this.width = width;
        this.height = height;
        this.lastModified = System.currentTimeMillis();
    }

    public int getFileId()       { return fileId; }
    public String getFilename()  { return filename; }
    public String getOwner()     { return owner; }
    public int getWidth()        { return width; }
    public int getHeight()       { return height; }
    public long getLastModified(){ return lastModified; }

    public void setLastModified(long t) { this.lastModified = t; }
    public void setFilename(String f)   { this.filename = f; }
}
