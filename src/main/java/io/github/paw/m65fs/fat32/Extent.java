package io.github.paw.m65fs.fat32;

/** A run of consecutive clusters {@code first .. first+length-1} in a cluster chain. */
public record Extent(int first, int length) {

  public Extent {
    if (first < 2 || length < 1) {
      throw new IllegalArgumentException("invalid extent " + first + "+" + length);
    }
  }

  /** Cluster number following the last cluster of this extent. */
  public int end() {
    return first + length;
  }
}
