package io.github.paw.m65fs.fat32;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A file or directory as described by its directory entry (plus long-name entries, if any).
 *
 * @param name long name if present, otherwise the 8.3 name (lower-cased per the NT case flags)
 * @param shortName the 8.3 name as stored, e.g. {@code "README  TXT"} rendered as {@code "README.TXT"}
 * @param attributes FAT attribute bits ({@link #ATTR_READ_ONLY} ...)
 * @param firstCluster first cluster, 0 for empty files
 * @param size file size in bytes (0 for directories)
 * @param created creation time or {@code null} if not set
 * @param modified last write time or {@code null} if not set
 * @param accessed last access date or {@code null} if not set
 * @param location where the entry lives, needed to update it
 */
public record DirEntry(
    String name,
    String shortName,
    int attributes,
    int firstCluster,
    long size,
    LocalDateTime created,
    LocalDateTime modified,
    LocalDate accessed,
    Location location) {

  public static final int ATTR_READ_ONLY = 0x01;
  public static final int ATTR_HIDDEN = 0x02;
  public static final int ATTR_SYSTEM = 0x04;
  public static final int ATTR_VOLUME_ID = 0x08;
  public static final int ATTR_DIRECTORY = 0x10;
  public static final int ATTR_ARCHIVE = 0x20;
  static final int ATTR_LONG_NAME = 0x0F;

  /**
   * Position of the entry inside its parent directory.
   *
   * @param directoryCluster first cluster of the parent directory
   * @param firstSlot index (in 32-byte slots, counted over the whole directory chain) of the
   *     first long-name entry, or of the short entry if there are no long-name entries
   * @param slotCount number of slots used (long-name entries + 1)
   */
  public record Location(int directoryCluster, int firstSlot, int slotCount) {
    /** Slot index of the 8.3 entry. */
    public int shortSlot() {
      return firstSlot + slotCount - 1;
    }
  }

  public boolean isDirectory() {
    return (attributes & ATTR_DIRECTORY) != 0;
  }

  public boolean isRegularFile() {
    return (attributes & (ATTR_DIRECTORY | ATTR_VOLUME_ID)) == 0;
  }

  public boolean isReadOnly() {
    return (attributes & ATTR_READ_ONLY) != 0;
  }

  public boolean isHidden() {
    return (attributes & ATTR_HIDDEN) != 0;
  }

  public boolean isSystem() {
    return (attributes & ATTR_SYSTEM) != 0;
  }

  public boolean isArchive() {
    return (attributes & ATTR_ARCHIVE) != 0;
  }

  /** {@code "."} or {@code ".."}. */
  boolean isDotEntry() {
    return name.equals(".") || name.equals("..");
  }

  /** Case-insensitive match against the long or the short name (FAT semantics). */
  public boolean matches(String n) {
    return name.equalsIgnoreCase(n) || shortName.equalsIgnoreCase(n);
  }
}
