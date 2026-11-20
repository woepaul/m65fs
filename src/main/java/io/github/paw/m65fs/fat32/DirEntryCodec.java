package io.github.paw.m65fs.fat32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/** Encodes directory slots: 8.3 entries, VFAT long-name entries and field patches. */
final class DirEntryCodec {

  static final int SLOT = DirectoryParser.SLOT_SIZE;
  private static final int[] LFN_OFFSETS = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};

  private DirEntryCodec() {}

  /**
   * Encodes the slots for a new entry: long-name entries (if {@code longName != null}) followed
   * by the 8.3 entry.
   */
  static byte[] encode(String longName, byte[] short11, int attributes, int firstCluster, long size,
      LocalDateTime now) {
    int lfnSlots = longName == null ? 0 : (longName.length() + 12) / 13;
    byte[] slots = new byte[(lfnSlots + 1) * SLOT];
    ByteBuffer b = ByteBuffer.wrap(slots).order(ByteOrder.LITTLE_ENDIAN);
    if (longName != null) {
      int checksum = DirectoryParser.checksum(short11);
      for (int k = 0; k < lfnSlots; k++) {
        int ordinal = lfnSlots - k; // highest ordinal first on disk
        int off = k * SLOT;
        b.put(off, (byte) (ordinal | (k == 0 ? 0x40 : 0)));
        b.put(off + 11, (byte) DirEntry.ATTR_LONG_NAME);
        b.put(off + 12, (byte) 0);
        b.put(off + 13, (byte) checksum);
        b.putShort(off + 26, (short) 0);
        for (int i = 0; i < 13; i++) {
          int idx = (ordinal - 1) * 13 + i;
          char ch;
          if (idx < longName.length()) {
            ch = longName.charAt(idx);
          } else if (idx == longName.length()) {
            ch = 0x0000;
          } else {
            ch = 0xFFFF;
          }
          b.putChar(off + LFN_OFFSETS[i], ch);
        }
      }
    }
    int off = lfnSlots * SLOT;
    b.put(off, short11);
    b.put(off + 11, (byte) attributes);
    b.put(off + 12, (byte) 0);
    setTimes(b, off, now, now, now);
    setCluster(b, off, firstCluster);
    b.putInt(off + 28, (int) size);
    return slots;
  }

  /** Sets first cluster (split hi/lo) of the 8.3 entry at {@code off}. */
  static void setCluster(ByteBuffer b, int off, int cluster) {
    b.putShort(off + 20, (short) (cluster >>> 16));
    b.putShort(off + 26, (short) cluster);
  }

  /** Sets creation, modification and access time; {@code null} leaves a field unchanged. */
  static void setTimes(ByteBuffer b, int off, LocalDateTime created, LocalDateTime modified, LocalDateTime accessed) {
    if (created != null) {
      b.put(off + 13, (byte) ((created.getSecond() % 2) * 100 + created.getNano() / 10_000_000));
      b.putShort(off + 14, time(created));
      b.putShort(off + 16, date(created));
    }
    if (accessed != null) {
      b.putShort(off + 18, date(accessed));
    }
    if (modified != null) {
      b.putShort(off + 22, time(modified));
      b.putShort(off + 24, date(modified));
    }
  }

  static short date(LocalDateTime t) {
    int year = Math.clamp(t.getYear(), 1980, 2107);
    return (short) ((year - 1980) << 9 | t.getMonthValue() << 5 | t.getDayOfMonth());
  }

  static short time(LocalDateTime t) {
    return (short) (t.getHour() << 11 | t.getMinute() << 5 | t.getSecond() / 2);
  }
}
