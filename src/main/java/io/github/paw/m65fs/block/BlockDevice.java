package io.github.paw.m65fs.block;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A device of 512-byte sectors addressed by absolute LBA: the SD card as seen through
 * remotesd_eth, or an image file.
 *
 * <p>Transfers always cover whole sectors: {@code buf.remaining()} must be a multiple of
 * {@link #SECTOR_SIZE}. On return the buffer's position has advanced by the bytes transferred.
 */
public interface BlockDevice extends AutoCloseable {

  int SECTOR_SIZE = 512;

  /** Number of sectors, or {@code -1} if unknown (the remote SD card does not report it). */
  long sectorCount() throws IOException;

  /** Reads {@code buf.remaining() / 512} consecutive sectors starting at {@code lba}. */
  void read(long lba, ByteBuffer buf) throws IOException;

  /** Writes {@code buf.remaining() / 512} consecutive sectors starting at {@code lba}. */
  void write(long lba, ByteBuffer buf) throws IOException;

  /** Makes previous writes durable (flushes caches / outstanding batches). */
  void flush() throws IOException;

  boolean isReadOnly();

  @Override
  void close() throws IOException;

  /** A view of {@code dev} that rejects writes; closing it closes {@code dev}. */
  static BlockDevice readOnly(BlockDevice dev) {
    return new BlockDevice() {
      @Override public long sectorCount() throws IOException { return dev.sectorCount(); }
      @Override public void read(long lba, ByteBuffer buf) throws IOException { dev.read(lba, buf); }
      @Override public void write(long lba, ByteBuffer buf) throws IOException { throw new IOException("read-only device"); }
      @Override public void flush() {}
      @Override public boolean isReadOnly() { return true; }
      @Override public void close() throws IOException { dev.close(); }
    };
  }

  /** Validates a transfer and returns the number of sectors. */
  static int sectors(long lba, ByteBuffer buf) {
    if (lba < 0) {
      throw new IllegalArgumentException("negative LBA: " + lba);
    }
    if (buf.remaining() % SECTOR_SIZE != 0) {
      throw new IllegalArgumentException("transfer of " + buf.remaining() + " bytes is not a multiple of 512");
    }
    return buf.remaining() / SECTOR_SIZE;
  }
}
