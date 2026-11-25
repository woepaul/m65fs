package io.github.paw.m65fs.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the bytes read from and written to a device (e.g. for progress displays). Placed
 * directly above the card or image, below any cache, it counts what really moves.
 */
public final class CountingBlockDevice implements BlockDevice {

  private final BlockDevice dev;
  private final AtomicLong read = new AtomicLong();
  private final AtomicLong written = new AtomicLong();

  public CountingBlockDevice(BlockDevice dev) {
    this.dev = dev;
  }

  /** Bytes read so far. */
  public long bytesRead() {
    return read.get();
  }

  /** Bytes written so far. */
  public long bytesWritten() {
    return written.get();
  }

  @Override
  public long sectorCount() throws IOException {
    return dev.sectorCount();
  }

  @Override
  public void read(long lba, ByteBuffer buf) throws IOException {
    int n = buf.remaining();
    dev.read(lba, buf);
    read.addAndGet(n);
  }

  @Override
  public void write(long lba, ByteBuffer buf) throws IOException {
    int n = buf.remaining();
    dev.write(lba, buf);
    written.addAndGet(n);
  }

  @Override
  public void flush() throws IOException {
    dev.flush();
  }

  @Override
  public boolean isReadOnly() {
    return dev.isReadOnly();
  }

  @Override
  public void close() throws IOException {
    dev.close();
  }
}
