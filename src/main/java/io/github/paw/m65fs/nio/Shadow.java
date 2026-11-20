package io.github.paw.m65fs.nio;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Host-side copy of a file being written: in memory up to {@link #DEFAULT_MEMORY_LIMIT}, then
 * spilled to a temporary file. Gaps created by writing beyond the end read as zeros.
 */
final class Shadow implements Closeable {

  static final int DEFAULT_MEMORY_LIMIT = 8 << 20;
  static final long MAX_FAT_FILE = 0xFFFF_FFFFL;

  private final int memoryLimit;
  private byte[] mem = new byte[0];
  private FileChannel spill;
  private Path spillFile;
  private long size;

  Shadow(int memoryLimit) {
    this.memoryLimit = memoryLimit;
  }

  long size() {
    return size;
  }

  int read(long pos, ByteBuffer dst) throws IOException {
    if (pos >= size) {
      return -1;
    }
    int n = (int) Math.min(dst.remaining(), size - pos);
    if (spill == null) {
      dst.put(mem, (int) pos, n);
      return n;
    }
    ByteBuffer slice = dst.slice(dst.position(), n);
    int total = 0;
    while (slice.hasRemaining()) {
      int r = spill.read(slice, pos + total);
      if (r < 0) {
        break;
      }
      total += r;
    }
    dst.position(dst.position() + total);
    return total;
  }

  int write(long pos, ByteBuffer src) throws IOException {
    int n = src.remaining();
    long end = pos + n;
    if (end > MAX_FAT_FILE) {
      throw new IOException("file would exceed the FAT32 limit of 4 GiB - 1");
    }
    ensureCapacity(end);
    if (spill == null) {
      if (pos > size) {
        Arrays.fill(mem, (int) size, (int) pos, (byte) 0);
      }
      src.get(mem, (int) pos, n);
    } else {
      if (pos > size) {
        zeroFill(size, pos);
      }
      long p = pos;
      while (src.hasRemaining()) {
        p += spill.write(src, p);
      }
    }
    size = Math.max(size, end);
    return n;
  }

  void truncate(long newSize) throws IOException {
    if (newSize < size) {
      size = newSize;
      if (spill != null) {
        spill.truncate(newSize);
      }
    }
  }

  /** Sequential reader over the current content. */
  ReadableByteChannel reader() {
    return new ReadableByteChannel() {
      private long pos;
      private boolean open = true;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        int n = Shadow.this.read(pos, dst);
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

  private void ensureCapacity(long end) throws IOException {
    if (spill != null) {
      return;
    }
    if (end > memoryLimit) {
      spillFile = Files.createTempFile("m65fs-", ".shadow");
      spill = FileChannel.open(spillFile, StandardOpenOption.READ, StandardOpenOption.WRITE,
          StandardOpenOption.DELETE_ON_CLOSE);
      ByteBuffer b = ByteBuffer.wrap(mem, 0, (int) size);
      long p = 0;
      while (b.hasRemaining()) {
        p += spill.write(b, p);
      }
      mem = null;
      return;
    }
    if (end > mem.length) {
      long cap = Math.max(end, Math.min((long) memoryLimit, Math.max(4096L, (long) mem.length * 2)));
      mem = Arrays.copyOf(mem, (int) cap);
    }
  }

  private void zeroFill(long from, long to) throws IOException {
    ByteBuffer zeros = ByteBuffer.allocate(64 * 1024);
    long p = from;
    while (p < to) {
      zeros.clear().limit((int) Math.min(zeros.capacity(), to - p));
      while (zeros.hasRemaining()) {
        p += spill.write(zeros, p);
      }
    }
  }

  @Override
  public void close() throws IOException {
    mem = null;
    if (spill != null) {
      spill.close(); // DELETE_ON_CLOSE removes the temp file
      Files.deleteIfExists(spillFile);
    }
  }
}
