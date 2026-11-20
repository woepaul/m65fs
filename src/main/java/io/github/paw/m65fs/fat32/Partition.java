package io.github.paw.m65fs.fat32;

import io.github.paw.m65fs.block.BlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A primary MBR partition entry.
 *
 * @param index slot 0..3 in the partition table
 * @param type partition type byte ({@code 0x0B}/{@code 0x0C} = FAT32, {@code 0x41} = MEGA65 system partition)
 */
public record Partition(int index, int type, long startLba, long sectorCount) {

  public static final int TYPE_FAT32_CHS = 0x0B;
  public static final int TYPE_FAT32_LBA = 0x0C;
  public static final int TYPE_MEGA65_SYSTEM = 0x41;

  public boolean isFat32() {
    return type == TYPE_FAT32_CHS || type == TYPE_FAT32_LBA;
  }

  /** Parses the four primary entries of an MBR sector; empty slots are omitted. */
  public static List<Partition> parse(ByteBuffer mbr) {
    ByteBuffer b = mbr.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    List<Partition> result = new ArrayList<>(4);
    if ((b.get(510) & 0xff) != 0x55 || (b.get(511) & 0xff) != 0xAA) {
      return result;
    }
    for (int i = 0; i < 4; i++) {
      int e = 0x1BE + i * 16;
      int type = b.get(e + 4) & 0xff;
      long start = Integer.toUnsignedLong(b.getInt(e + 8));
      long count = Integer.toUnsignedLong(b.getInt(e + 12));
      if (type != 0 && count != 0) {
        result.add(new Partition(i, type, start, count));
      }
    }
    return result;
  }

  /** Reads sector 0 of {@code dev} and parses its partition table. */
  public static List<Partition> read(BlockDevice dev) throws IOException {
    ByteBuffer mbr = ByteBuffer.allocate(BlockDevice.SECTOR_SIZE);
    dev.read(0, mbr);
    return parse(mbr.flip());
  }
}
