package io.github.paw.m65fs.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Remembers the first I/O error of the wrapped device and refuses all later transfers.
 *
 * <p>A failed transfer leaves the caller's in-memory view (cached FAT, directory snapshots) out of
 * step with the medium, so carrying on would build on state that may not be on the card. After
 * the first failure every {@code read}, {@code write} and {@code flush} throws an
 * {@link IOException} whose cause is the original error. {@link #close()} always closes the
 * wrapped device.
 */
public final class FailFastBlockDevice implements BlockDevice {

  private final BlockDevice dev;
  private volatile IOException failure;

  public FailFastBlockDevice(BlockDevice dev) {
    this.dev = Objects.requireNonNull(dev);
  }

  /** The first error, or {@code null} while the device is healthy. */
  public IOException failure() {
    return failure;
  }

  /** Throws if an earlier transfer failed. */
  public void check() throws IOException {
    IOException f = failure;
    if (f != null) {
      throw new IOException("device unusable after an earlier failure (" + f.getMessage()
          + "); close and reopen the file system", f);
    }
  }

  @Override
  public long sectorCount() throws IOException {
    return dev.sectorCount();
  }

  @Override
  public void read(long lba, ByteBuffer buf) throws IOException {
    check();
    try {
      dev.read(lba, buf);
    } catch (IOException e) {
      throw failed(e);
    }
  }

  @Override
  public void write(long lba, ByteBuffer buf) throws IOException {
    check();
    try {
      dev.write(lba, buf);
    } catch (IOException e) {
      throw failed(e);
    }
  }

  @Override
  public void flush() throws IOException {
    check();
    try {
      dev.flush();
    } catch (IOException e) {
      throw failed(e);
    }
  }

  private IOException failed(IOException e) {
    if (failure == null) {
      failure = e;
    }
    return e;
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
