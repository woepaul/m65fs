package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.block.BlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** The MEGA65 SD card, accessed through a {@link RemoteSdSession}. Closing closes the session. */
public final class RemoteSdBlockDevice implements BlockDevice {

  private final RemoteSdSession session;

  public RemoteSdBlockDevice(RemoteSdSession session) {
    this.session = Objects.requireNonNull(session);
  }

  public RemoteSdSession session() {
    return session;
  }

  /** The helper does not report the card size. */
  @Override
  public long sectorCount() {
    return -1;
  }

  @Override
  public void read(long lba, ByteBuffer buf) throws IOException {
    session.readSectors(lba, buf);
  }

  @Override
  public void write(long lba, ByteBuffer buf) throws IOException {
    session.writeSectors(lba, buf);
  }

  /** Writes are acknowledged (and verified by the helper) before {@link #write} returns. */
  @Override
  public void flush() {}

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public void close() throws IOException {
    session.close();
  }
}
