package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.NoSuchFileException;

/** The byte channels handed out by the provider. */
final class Channels {

  private Channels() {}

  /** Sequential reader of a file's current content (used by copy). */
  static ReadableByteChannel reader(Fat32Volume volume, DirEntry entry) {
    return new ReadableByteChannel() {
      private long pos;
      private boolean open = true;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        int n = volume.read(entry, pos, dst);
        if (n > 0) {
          pos += n;
        }
        return n;
      }

      @Override
      public boolean isOpen() {
        return open;
      }

      @Override
      public void close() {
        open = false;
      }
    };
  }

  /** Read-only channel on a snapshot of the file. */
  static final class ReadChannel implements SeekableByteChannel {
    private final Mega65FileSystem fs;
    private final DirEntry entry;
    private long position;
    private boolean open = true;

    ReadChannel(Mega65FileSystem fs, DirEntry entry) {
      this.fs = fs;
      this.entry = entry;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
      ensureOpen();
      if (!dst.hasRemaining()) {
        return 0;
      }
      int n = fs.volume().read(entry, position, dst);
      if (n > 0) {
        position += n;
      }
      return n;
    }

    @Override
    public int write(ByteBuffer src) {
      throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() throws IOException {
      ensureOpen();
      return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
      ensureOpen();
      if (newPosition < 0) {
        throw new IllegalArgumentException("negative position");
      }
      position = newPosition;
      return this;
    }

    @Override
    public synchronized long size() throws IOException {
      ensureOpen();
      return entry.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) {
      throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
      return open;
    }

    @Override
    public synchronized void close() {
      open = false;
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new ClosedChannelException();
      }
      fs.ensureOpen();
    }
  }

  /**
   * Writable channel. All changes go to a host-side {@link Shadow}; on {@link #close()} the
   * complete content is written to the card in one contiguous run (see
   * {@link Fat32Volume#writeContent}). Errors such as running out of contiguous space surface
   * from {@code close()}, and the file on the card then keeps its previous content.
   */
  static final class WriteChannel implements SeekableByteChannel {
    private final Mega65FileSystem fs;
    private final Mega65Path path;
    private final Shadow shadow;
    private final boolean readable;
    private final boolean append;
    private final boolean deleteOnClose;
    private long position;
    private boolean dirty;
    private boolean open = true;

    /**
     * @param existing the file (already created if it did not exist)
     * @param truncate start empty instead of loading the current content
     */
    WriteChannel(Mega65FileSystem fs, Mega65Path path, DirEntry existing, boolean truncate, boolean readable,
        boolean append, boolean deleteOnClose) throws IOException {
      this.fs = fs;
      this.path = path;
      this.readable = readable;
      this.append = append;
      this.deleteOnClose = deleteOnClose;
      this.shadow = new Shadow(fs.shadowMemoryLimit());
      try {
        if (truncate) {
          dirty = existing.size() > 0; // TRUNCATE_EXISTING must take effect even without writes
        } else if (existing.size() > 0) {
          ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
          long pos = 0;
          int n;
          while ((n = fs.volume().read(existing, pos, buf.clear())) > 0) {
            buf.flip();
            shadow.write(pos, buf);
            pos += n;
          }
        }
      } catch (IOException | RuntimeException e) {
        shadow.close();
        throw e;
      }
      fs.opened(this);
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
      ensureOpen();
      if (!readable) {
        throw new java.nio.channels.NonReadableChannelException();
      }
      int n = shadow.read(position, dst);
      if (n > 0) {
        position += n;
      }
      return n;
    }

    @Override
    public synchronized int write(ByteBuffer src) throws IOException {
      ensureOpen();
      if (append) {
        position = shadow.size();
      }
      int n = shadow.write(position, src);
      position += n;
      dirty = true;
      return n;
    }

    @Override
    public synchronized long position() throws IOException {
      ensureOpen();
      return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
      ensureOpen();
      if (newPosition < 0) {
        throw new IllegalArgumentException("negative position");
      }
      position = newPosition;
      return this;
    }

    @Override
    public synchronized long size() throws IOException {
      ensureOpen();
      return shadow.size();
    }

    @Override
    public synchronized SeekableByteChannel truncate(long size) throws IOException {
      ensureOpen();
      if (size < 0) {
        throw new IllegalArgumentException("negative size");
      }
      if (size < shadow.size()) {
        shadow.truncate(size);
        dirty = true;
      }
      position = Math.min(position, size);
      return this;
    }

    @Override
    public synchronized boolean isOpen() {
      return open;
    }

    /** Writes the current content to the card (if changed since the last write); stays open. */
    synchronized void commit() throws IOException {
      ensureOpen();
      if (dirty) {
        fs.ensureOpen();
        // re-resolve: the entry may have changed (e.g. another channel rewrote the file)
        DirEntry current = fs.entry(path).orElseThrow(() -> new NoSuchFileException(path.toString(), null,
            "file was deleted or moved while open; content not written"));
        fs.volume().writeContent(current, shadow.reader(), shadow.size());
        dirty = false;
      }
    }

    /** Writes the content to the card (if changed). */
    @Override
    public synchronized void close() throws IOException {
      if (!open) {
        return;
      }
      try (shadow) {
        commit();
      } finally {
        open = false;
        fs.closed(this);
      }
      if (deleteOnClose) {
        var e = fs.entry(path);
        if (e.isPresent()) {
          fs.volume().delete(e.get());
        }
      }
    }

    private void ensureOpen() throws IOException {
      if (!open) {
        throw new ClosedChannelException();
      }
    }
  }
}
