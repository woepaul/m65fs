package io.github.paw.m65fs.fat32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Decodes raw directory contents (32-byte slots) into {@link DirEntry}s. */
final class DirectoryParser {

  static final int SLOT_SIZE = 32;
  static final int DELETED = 0xE5;
  private static final int NT_LOWER_BASE = 0x08;
  private static final int NT_LOWER_EXT = 0x10;
  private static final int LFN_LAST = 0x40;
  private static final int LFN_CHARS = 13;
  private static final int[] LFN_OFFSETS = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};

  private DirectoryParser() {}

  /**
   * Parses all live entries of a directory, including {@code .}/{@code ..} and the volume label.
   * Stops at the first slot whose first byte is 0 (end-of-directory marker).
   *
   * @param directoryCluster first cluster of the directory (recorded in each entry's location)
   */
  static List<DirEntry> parse(ByteBuffer raw, int directoryCluster) {
    ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int slots = b.remaining() / SLOT_SIZE;
    int base = b.position();
    List<DirEntry> result = new ArrayList<>();

    char[] lfn = null;     // collected long-name characters
    int lfnCount = 0;      // number of LFN slots announced by the "last" entry
    int lfnNext = 0;       // next expected ordinal (counting down)
    int lfnChecksum = 0;
    int lfnFirstSlot = -1;

    for (int i = 0; i < slots; i++) {
      int off = base + i * SLOT_SIZE;
      int first = b.get(off) & 0xff;
      if (first == 0x00) {
        break;
      }
      int attr = b.get(off + 11) & 0xff;
      if (first == DELETED) {
        lfn = null;
        continue;
      }
      if ((attr & 0x3F) == DirEntry.ATTR_LONG_NAME) {
        int ord = first & 0x1F;
        if ((first & LFN_LAST) != 0) {
          lfnCount = ord;
          lfnNext = ord;
          lfn = new char[ord * LFN_CHARS];
          lfnChecksum = b.get(off + 13) & 0xff;
          lfnFirstSlot = i;
        }
        if (lfn == null || ord != lfnNext || ord == 0 || (b.get(off + 13) & 0xff) != lfnChecksum) {
          lfn = null; // orphaned or inconsistent long-name entry
          continue;
        }
        for (int k = 0; k < LFN_CHARS; k++) {
          lfn[(ord - 1) * LFN_CHARS + k] = b.getChar(off + LFN_OFFSETS[k]);
        }
        lfnNext--;
        continue;
      }

      // 8.3 entry
      byte[] raw11 = new byte[11];
      b.get(off, raw11);
      String shortName = shortName(raw11, 0);
      String displayShort = shortName(raw11, b.get(off + 12) & 0xff);
      String longName = null;
      int firstSlot = i;
      if (lfn != null && lfnNext == 0 && lfnChecksum == checksum(raw11)) {
        longName = decodeLfn(lfn);
        firstSlot = lfnFirstSlot;
      }
      lfn = null;
      int cluster = Short.toUnsignedInt(b.getShort(off + 20)) << 16 | Short.toUnsignedInt(b.getShort(off + 26));
      long size = Integer.toUnsignedLong(b.getInt(off + 28));
      LocalDateTime created = dateTime(b.getShort(off + 16), b.getShort(off + 14), b.get(off + 13) & 0xff);
      LocalDateTime modified = dateTime(b.getShort(off + 24), b.getShort(off + 22), 0);
      LocalDate accessed = date(b.getShort(off + 18));
      result.add(new DirEntry(longName != null ? longName : displayShort, shortName, attr, cluster, size,
          created, modified, accessed, new DirEntry.Location(directoryCluster, firstSlot, i - firstSlot + 1)));
    }
    return result;
  }

  /** Renders an 11-byte 8.3 name, applying the NT lower-case flags if given. */
  static String shortName(byte[] raw11, int ntFlags) {
    byte[] n = raw11.clone();
    if ((n[0] & 0xff) == 0x05) {
      n[0] = (byte) DELETED; // 0x05 escapes a real 0xE5 first character
    }
    String base = new String(n, 0, 8, StandardCharsets.ISO_8859_1).stripTrailing();
    String ext = new String(n, 8, 3, StandardCharsets.ISO_8859_1).stripTrailing();
    if ((ntFlags & NT_LOWER_BASE) != 0) {
      base = base.toLowerCase(java.util.Locale.ROOT);
    }
    if ((ntFlags & NT_LOWER_EXT) != 0) {
      ext = ext.toLowerCase(java.util.Locale.ROOT);
    }
    return ext.isEmpty() ? base : base + "." + ext;
  }

  /** The VFAT checksum of an 11-byte short name, stored in every long-name entry. */
  static int checksum(byte[] raw11) {
    int sum = 0;
    for (int i = 0; i < 11; i++) {
      sum = (((sum & 1) << 7) | ((sum & 0xff) >>> 1)) + (raw11[i] & 0xff);
      sum &= 0xff;
    }
    return sum;
  }

  private static String decodeLfn(char[] chars) {
    int len = 0;
    while (len < chars.length && chars[len] != 0x0000 && chars[len] != 0xFFFF) {
      len++;
    }
    return new String(chars, 0, len);
  }

  static LocalDate date(short d) {
    int v = Short.toUnsignedInt(d);
    if (v == 0) {
      return null;
    }
    try {
      return LocalDate.of(1980 + (v >>> 9), (v >>> 5) & 0x0F, v & 0x1F);
    } catch (DateTimeException e) {
      return null;
    }
  }

  static LocalDateTime dateTime(short d, short t, int tenthsOf10ms) {
    LocalDate date = date(d);
    if (date == null) {
      return null;
    }
    int v = Short.toUnsignedInt(t);
    try {
      int seconds = (v & 0x1F) * 2 + tenthsOf10ms / 100;
      int nanos = (tenthsOf10ms % 100) * 10_000_000;
      return LocalDateTime.of(date, LocalTime.of(v >>> 11, (v >>> 5) & 0x3F, Math.min(seconds, 59), nanos));
    } catch (DateTimeException e) {
      return LocalDateTime.of(date, LocalTime.MIDNIGHT);
    }
  }
}
