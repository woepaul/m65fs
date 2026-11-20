package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Snapshot of a {@link DirEntry} as NIO attributes. Contiguity is computed on first use. */
final class FatAttributes implements Mega65FileAttributes {

  /** Identity of a file: the position of its 8.3 entry. */
  record FileKey(int directoryCluster, int slot) {}

  private static final FileTime EPOCH = FileTime.fromMillis(0);

  private final DirEntry entry;
  private final Fat32Volume volume;
  private final ZoneId zone;
  private Boolean contiguous;

  FatAttributes(DirEntry entry, Fat32Volume volume, ZoneId zone) {
    this.entry = entry;
    this.volume = volume;
    this.zone = zone;
  }

  DirEntry entry() {
    return entry;
  }

  private FileTime time(LocalDateTime t) {
    return t == null ? null : FileTime.from(t.atZone(zone).toInstant());
  }

  private FileTime time(LocalDate d) {
    return d == null ? null : FileTime.from(d.atStartOfDay(zone).toInstant());
  }

  @Override
  public FileTime lastModifiedTime() {
    FileTime t = time(entry.modified());
    return t != null ? t : EPOCH;
  }

  @Override
  public FileTime lastAccessTime() {
    FileTime t = time(entry.accessed());
    return t != null ? t : lastModifiedTime();
  }

  @Override
  public FileTime creationTime() {
    FileTime t = time(entry.created());
    return t != null ? t : lastModifiedTime();
  }

  @Override
  public boolean isRegularFile() {
    return entry.isRegularFile();
  }

  @Override
  public boolean isDirectory() {
    return entry.isDirectory();
  }

  @Override
  public boolean isSymbolicLink() {
    return false;
  }

  @Override
  public boolean isOther() {
    return false;
  }

  @Override
  public long size() {
    return entry.size();
  }

  @Override
  public Object fileKey() {
    return new FileKey(entry.location().directoryCluster(), entry.location().shortSlot());
  }

  @Override
  public boolean isReadOnly() {
    return entry.isReadOnly();
  }

  @Override
  public boolean isHidden() {
    return entry.isHidden();
  }

  @Override
  public boolean isArchive() {
    return entry.isArchive();
  }

  @Override
  public boolean isSystem() {
    return entry.isSystem();
  }

  @Override
  public String shortName() {
    return entry.shortName();
  }

  @Override
  public int firstCluster() {
    return entry.firstCluster();
  }

  @Override
  public long startSector() {
    return entry.firstCluster() == 0 ? -1 : volume.clusterLba(entry.firstCluster());
  }

  @Override
  public synchronized boolean isContiguous() {
    if (contiguous == null) {
      try {
        contiguous = volume.isContiguous(entry);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return contiguous;
  }

  /** Attribute map for {@code readAttributes(path, "view:names")}. */
  Map<String, Object> toMap(String view, String names) {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put("lastModifiedTime", lastModifiedTime());
    all.put("lastAccessTime", lastAccessTime());
    all.put("creationTime", creationTime());
    all.put("size", size());
    all.put("isRegularFile", isRegularFile());
    all.put("isDirectory", isDirectory());
    all.put("isSymbolicLink", false);
    all.put("isOther", false);
    all.put("fileKey", fileKey());
    if (!view.equals("basic")) {
      all.put("readonly", isReadOnly());
      all.put("hidden", isHidden());
      all.put("system", isSystem());
      all.put("archive", isArchive());
    }
    if (view.equals("mega65")) {
      all.put("shortName", shortName());
      all.put("firstCluster", firstCluster());
      all.put("startSector", startSector());
      all.put("contiguous", isContiguous());
    }
    if (names.equals("*")) {
      return all;
    }
    Map<String, Object> selected = new LinkedHashMap<>();
    for (String n : names.split(",")) {
      String name = n.strip();
      if (name.equals("*")) {
        selected.putAll(all);
      } else if (all.containsKey(name)) {
        selected.put(name, all.get(name));
      } else {
        throw new IllegalArgumentException("unknown attribute '" + name + "' for view " + view);
      }
    }
    return selected;
  }
}
