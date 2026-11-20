package io.github.paw.m65fs.fat32;

import io.github.paw.m65fs.block.BlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The FAT32 FSInfo sector: a hint of the free cluster count and of the next free cluster. Both
 * values may be "unknown" ({@code 0xFFFFFFFF}); then they are -1 here.
 */
final class FsInfo {

  private static final int LEAD_SIG = 0x41615252;
  private static final int STRUCT_SIG = 0x61417272;
  private static final int TRAIL_SIG = 0xAA550000;
  private static final long UNKNOWN = 0xFFFFFFFFL;

  private final long lba;
  private final ByteBuffer sector;
  private final boolean valid;
  private long freeCount;
  private long nextFree;

  private FsInfo(long lba, ByteBuffer sector) {
    this.lba = lba;
    this.sector = sector;
    this.valid = sector.getInt(0) == LEAD_SIG && sector.getInt(484) == STRUCT_SIG && sector.getInt(508) == TRAIL_SIG;
    long fc = Integer.toUnsignedLong(sector.getInt(488));
    long nf = Integer.toUnsignedLong(sector.getInt(492));
    this.freeCount = valid && fc != UNKNOWN ? fc : -1;
    this.nextFree = valid && nf != UNKNOWN ? nf : -1;
  }

  /** Reads the FSInfo sector at absolute {@code lba}; {@code lba < 0} means the volume has none. */
  static FsInfo read(BlockDevice dev, long lba) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(BlockDevice.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    if (lba > 0) {
      dev.read(lba, b);
    }
    return new FsInfo(lba, b.clear());
  }

  boolean isValid() {
    return valid;
  }

  /** Free cluster count or -1 if unknown. */
  long freeCount() {
    return freeCount;
  }

  void setFreeCount(long count) {
    this.freeCount = count;
  }

  /** Hint where to start looking for free clusters, or -1. */
  long nextFree() {
    return nextFree;
  }

  void setNextFree(long cluster) {
    this.nextFree = cluster;
  }

  /** Writes the values back (only if the sector is a valid FSInfo sector). */
  void write(BlockDevice dev) throws IOException {
    if (!valid) {
      return;
    }
    sector.putInt(488, (int) (freeCount < 0 ? UNKNOWN : freeCount));
    sector.putInt(492, (int) (nextFree < 0 ? UNKNOWN : nextFree));
    dev.write(lba, sector.duplicate().clear());
  }
}
