package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.fat32.Fat32Volume;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/** The FAT32 partition of the SD card. */
public final class Mega65FileStore extends FileStore {

  private final Mega65FileSystem fs;

  Mega65FileStore(Mega65FileSystem fs) {
    this.fs = fs;
  }

  private Fat32Volume volume() throws IOException {
    fs.ensureOpen();
    return fs.volume();
  }

  /** The volume label. */
  @Override
  public String name() {
    return fs.volume().bootSector().volumeLabel();
  }

  @Override
  public String type() {
    return "fat32";
  }

  @Override
  public boolean isReadOnly() {
    return fs.isReadOnly();
  }

  @Override
  public long getTotalSpace() throws IOException {
    var bs = volume().bootSector();
    return bs.clusterCount() * bs.bytesPerCluster();
  }

  @Override
  public long getUsableSpace() throws IOException {
    return getUnallocatedSpace();
  }

  @Override
  public long getUnallocatedSpace() throws IOException {
    return volume().freeClusters() * volume().bootSector().bytesPerCluster();
  }

  /**
   * Size of the largest file that can currently be written: files are always stored
   * contiguously, so this is the largest run of free clusters (scans the whole FAT).
   */
  public long getLargestContiguousSpace() throws IOException {
    return volume().largestFreeRun() * volume().bootSector().bytesPerCluster();
  }

  @Override
  public long getBlockSize() {
    return fs.volume().bootSector().bytesPerCluster();
  }

  @Override
  public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
    return type == BasicFileAttributeView.class || type == DosFileAttributeView.class
        || type == Mega65FileAttributeView.class;
  }

  @Override
  public boolean supportsFileAttributeView(String name) {
    return Mega65FileSystem.VIEWS.contains(name);
  }

  @Override
  public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
    return null;
  }

  @Override
  public Object getAttribute(String attribute) throws IOException {
    return switch (attribute) {
      case "totalSpace" -> getTotalSpace();
      case "usableSpace" -> getUsableSpace();
      case "unallocatedSpace" -> getUnallocatedSpace();
      case "largestContiguousSpace" -> getLargestContiguousSpace();
      default -> throw new UnsupportedOperationException("unknown attribute " + attribute);
    };
  }

  @Override
  public String toString() {
    return name() + " (fat32)";
  }
}
