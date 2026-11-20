package io.github.paw.m65fs.fat32;

import java.io.Serial;
import java.nio.file.FileSystemException;

/**
 * There is no run of free clusters large enough to store a file contiguously. Files on the
 * MEGA65 SD card must never be fragmented (cores and Hyppo address them by start sector), so the
 * library refuses instead of fragmenting. The original file is left untouched.
 */
public final class NoContiguousSpaceException extends FileSystemException {
  @Serial private static final long serialVersionUID = 1L;

  private final long clustersNeeded;
  private final long largestFreeRun;

  public NoContiguousSpaceException(String file, long clustersNeeded, long largestFreeRun, int bytesPerCluster) {
    super(file, null, String.format(
        "no contiguous free space: need %d clusters (%d KiB), largest free run is %d clusters (%d KiB)",
        clustersNeeded, clustersNeeded * bytesPerCluster / 1024, largestFreeRun, largestFreeRun * bytesPerCluster / 1024));
    this.clustersNeeded = clustersNeeded;
    this.largestFreeRun = largestFreeRun;
  }

  public long clustersNeeded() {
    return clustersNeeded;
  }

  public long largestFreeRun() {
    return largestFreeRun;
  }
}
