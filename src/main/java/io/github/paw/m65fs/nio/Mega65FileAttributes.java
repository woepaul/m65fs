package io.github.paw.m65fs.nio;

import java.nio.file.attribute.DosFileAttributes;

/**
 * FAT attributes plus MEGA65-specific information. Read via
 * {@code Files.readAttributes(path, Mega65FileAttributes.class)} or the {@code "mega65:*"}
 * attribute names ({@code shortName, firstCluster, startSector, contiguous} in addition to the
 * {@code dos} ones).
 */
public interface Mega65FileAttributes extends DosFileAttributes {

  /** The 8.3 name as stored, e.g. {@code GAMEDI~1.D81}. */
  String shortName();

  /** First cluster of the data, 0 for empty files. */
  int firstCluster();

  /**
   * Absolute SD card sector of the first data byte (what cores and Hyppo use to access a
   * mounted image), or -1 for empty files.
   */
  long startSector();

  /** Whether the data occupies one run of clusters. Always true for files written by this library. */
  boolean isContiguous();
}
