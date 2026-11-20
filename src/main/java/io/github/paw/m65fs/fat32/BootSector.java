package io.github.paw.m65fs.fat32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;

/**
 * The FAT32 BIOS parameter block, restricted to what mega65_ftp accepts: 512-byte sectors and
 * exactly two FATs.
 */
public record BootSector(
    int bytesPerSector,
    int sectorsPerCluster,
    int reservedSectors,
    int numberOfFats,
    long totalSectors,
    long sectorsPerFat,
    int rootCluster,
    int fsInfoSector,
    int volumeId,
    String volumeLabel) {

  /** Parses and validates a FAT32 boot sector. */
  public static BootSector parse(ByteBuffer sector) throws FileSystemException {
    ByteBuffer b = sector.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    if ((b.get(510) & 0xff) != 0x55 || (b.get(511) & 0xff) != 0xAA) {
      throw corrupt("invalid boot sector signature");
    }
    int bps = Short.toUnsignedInt(b.getShort(0x0B));
    int spc = b.get(0x0D) & 0xff;
    int reserved = Short.toUnsignedInt(b.getShort(0x0E));
    int fats = b.get(0x10) & 0xff;
    int rootEntries = Short.toUnsignedInt(b.getShort(0x11));
    long total = Short.toUnsignedInt(b.getShort(0x13));
    if (total == 0) {
      total = Integer.toUnsignedLong(b.getInt(0x20));
    }
    int fatSize16 = Short.toUnsignedInt(b.getShort(0x16));
    long fatSize = Integer.toUnsignedLong(b.getInt(0x24));
    int root = b.getInt(0x2C);
    int fsInfo = Short.toUnsignedInt(b.getShort(0x30));
    int volId = b.getInt(0x43);
    byte[] label = new byte[11];
    b.get(0x47, label);

    if (bps != 512) {
      throw corrupt("FAT32 file system uses a sector size other than 512 bytes (" + bps + ")");
    }
    if (fats != 2) {
      throw corrupt("FAT32 file system has " + fats + " FATs, expected 2");
    }
    if (spc == 0 || Integer.bitCount(spc) != 1) {
      throw corrupt("invalid sectors per cluster: " + spc);
    }
    if (rootEntries != 0 || fatSize16 != 0 || fatSize == 0) {
      throw corrupt("not a FAT32 file system (FAT12/16 layout)");
    }
    var bs = new BootSector(bps, spc, reserved, fats, total, fatSize, root, fsInfo, volId,
        new String(label, StandardCharsets.ISO_8859_1).stripTrailing());
    // No minimum cluster count: like Linux, mtools, mega65_ftp and Hyppo we go by the FAT32 BPB
    // layout, so small FAT32 volumes (< 65525 clusters, e.g. from mkfs.fat -F 32) are accepted.
    if (bs.clusterCount() < 1) {
      throw corrupt("volume has no data clusters");
    }
    if (root < 2 || root > bs.maxCluster()) {
      throw corrupt("root cluster out of range: " + root);
    }
    return bs;
  }

  /** Sector (relative to the volume start) of the first FAT. */
  public long fatStart() {
    return reservedSectors;
  }

  /** Sector (relative to the volume start) of cluster #2. */
  public long dataStart() {
    return reservedSectors + numberOfFats * sectorsPerFat;
  }

  public int bytesPerCluster() {
    return sectorsPerCluster * bytesPerSector;
  }

  /** Number of data clusters; valid cluster numbers are {@code 2..maxCluster()}. */
  public long clusterCount() {
    return (totalSectors - dataStart()) / sectorsPerCluster;
  }

  public int maxCluster() {
    // FAT entries beyond the FAT's capacity cannot be used either.
    long byFat = sectorsPerFat * (bytesPerSector / 4) - 1;
    return (int) Math.min(clusterCount() + 1, byFat);
  }

  private static FileSystemException corrupt(String msg) {
    return new FileSystemException(null, null, msg);
  }
}
