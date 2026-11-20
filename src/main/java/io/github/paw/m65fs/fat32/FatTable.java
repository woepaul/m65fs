package io.github.paw.m65fs.fat32;

import io.github.paw.m65fs.block.BlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The file allocation table. Entries are 28-bit; the upper 4 bits are reserved and preserved.
 *
 * <p>Reads come from FAT #1 through a sector cache that loads {@value #PREFETCH} sectors per miss
 * (cheap sequential scans over the network). Modified sectors are kept until {@link #flush()},
 * which writes them to <em>every</em> FAT copy in contiguous runs.
 *
 * <p>Not thread-safe; guarded by the volume lock.
 */
final class FatTable {

  static final int FREE = 0;
  static final int BAD = 0x0FFFFFF7;
  static final int END_OF_CHAIN_MIN = 0x0FFFFFF8;
  static final int END_OF_CHAIN = 0x0FFFFFFF;
  static final int MASK = 0x0FFFFFFF;

  static final int ENTRIES_PER_SECTOR = BlockDevice.SECTOR_SIZE / 4;
  private static final int PREFETCH = 32;
  private static final int CACHE_SECTORS = 2048;

  /** A run of free clusters, or the largest one seen when a search fails. */
  record FreeRun(int start, int length) {}

  private final BlockDevice dev;
  private final long fatLba;
  private final long sectorsPerFat;
  private final int numberOfFats;
  private final int maxCluster;
  private final Map<Long, ByteBuffer> clean = new LinkedHashMap<>(256, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, ByteBuffer> eldest) {
      return size() > CACHE_SECTORS;
    }
  };
  private final TreeMap<Long, ByteBuffer> dirty = new TreeMap<>();
  /** Change of the number of free clusters since the last flush. */
  private long freeDelta;

  /**
   * @param fatLba absolute LBA of FAT #1
   * @param sectorsPerFat size of one FAT copy
   * @param numberOfFats number of FAT copies (consecutive)
   * @param maxCluster highest valid cluster number
   */
  FatTable(BlockDevice dev, long fatLba, long sectorsPerFat, int numberOfFats, int maxCluster) {
    this.dev = dev;
    this.fatLba = fatLba;
    this.sectorsPerFat = sectorsPerFat;
    this.numberOfFats = numberOfFats;
    this.maxCluster = maxCluster;
  }

  int maxCluster() {
    return maxCluster;
  }

  /** The (masked, 28-bit) FAT entry for {@code cluster}. */
  int get(int cluster) throws IOException {
    checkCluster(cluster);
    return sector(cluster / ENTRIES_PER_SECTOR).getInt((cluster % ENTRIES_PER_SECTOR) * 4) & MASK;
  }

  /** Sets the entry for {@code cluster}, preserving the reserved upper 4 bits. */
  void set(int cluster, int value) throws IOException {
    checkCluster(cluster);
    long index = cluster / ENTRIES_PER_SECTOR;
    ByteBuffer s = sector(index);
    int off = (cluster % ENTRIES_PER_SECTOR) * 4;
    int old = s.getInt(off);
    int oldValue = old & MASK;
    int newValue = value & MASK;
    if (oldValue == newValue) {
      return;
    }
    if (oldValue == FREE) {
      freeDelta--;
    } else if (newValue == FREE) {
      freeDelta++;
    }
    s.putInt(off, (old & ~MASK) | newValue);
    clean.remove(index);
    dirty.put(index, s);
  }

  boolean isFree(int cluster) throws IOException {
    return get(cluster) == FREE;
  }

  static boolean isEndOfChain(int entry) {
    return entry >= END_OF_CHAIN_MIN;
  }

  /** Links {@code first .. first+length-1} into one chain ending with end-of-chain. */
  void linkRun(int first, int length) throws IOException {
    for (int c = first; c < first + length - 1; c++) {
      set(c, c + 1);
    }
    set(first + length - 1, END_OF_CHAIN);
  }

  /** Marks every cluster of the given extents free. */
  void free(List<Extent> extents) throws IOException {
    for (Extent e : extents) {
      for (int c = e.first(); c < e.end(); c++) {
        set(c, FREE);
      }
    }
  }

  /**
   * First-fit search for {@code n} consecutive free clusters, starting at {@code hint} and
   * wrapping around once. Runs do not wrap past the last cluster.
   *
   * @return the run found (length {@code n}), or – if none exists – a run with the start and
   *     length of the largest free run seen ({@code length < n})
   */
  FreeRun findFreeRun(int n, int hint) throws IOException {
    if (n < 1) {
      throw new IllegalArgumentException("n must be positive");
    }
    if (hint < 2 || hint > maxCluster) {
      hint = 2;
    }
    FreeRun largest = new FreeRun(0, 0);
    for (int[] range : new int[][] {{hint, maxCluster}, {2, hint - 1}}) {
      int runStart = -1;
      int runLen = 0;
      for (int c = range[0]; c <= range[1]; c++) {
        // fast path: work directly on the sector buffer
        ByteBuffer s = sector(c / ENTRIES_PER_SECTOR);
        int last = Math.min(range[1], (c / ENTRIES_PER_SECTOR + 1) * ENTRIES_PER_SECTOR - 1);
        for (; c <= last; c++) {
          if ((s.getInt((c % ENTRIES_PER_SECTOR) * 4) & MASK) == FREE) {
            if (runLen == 0) {
              runStart = c;
            }
            if (++runLen == n) {
              return new FreeRun(runStart, n);
            }
          } else {
            if (runLen > largest.length()) {
              largest = new FreeRun(runStart, runLen);
            }
            runLen = 0;
          }
        }
        c = last; // loop increment moves to the next sector
      }
      if (runLen > largest.length()) {
        largest = new FreeRun(runStart, runLen);
      }
    }
    return largest;
  }

  /** Counts all free clusters (full FAT scan). */
  long countFree() throws IOException {
    long free = 0;
    for (int c = 2; c <= maxCluster; c++) {
      if (get(c) == FREE) {
        free++;
      }
    }
    return free;
  }

  /**
   * The chain starting at {@code first} as runs of consecutive clusters. An empty file
   * ({@code first == 0}) has no extents.
   *
   * @throws FileSystemException if the chain is broken (free/bad/out-of-range entry or a loop)
   */
  List<Extent> extents(int first) throws IOException {
    List<Extent> result = new ArrayList<>();
    if (first == 0) {
      return result;
    }
    int runStart = first;
    int runLen = 1;
    int cluster = first;
    long visited = 1;
    while (true) {
      int next = get(cluster);
      if (isEndOfChain(next)) {
        break;
      }
      if (next == FREE || next == BAD || next < 2 || next > maxCluster) {
        throw new FileSystemException(null, null,
            String.format("corrupt cluster chain starting at %d: cluster %d -> 0x%08x", first, cluster, next));
      }
      if (++visited > maxCluster) {
        throw new FileSystemException(null, null, "cluster chain starting at " + first + " contains a loop");
      }
      if (next == cluster + 1) {
        runLen++;
      } else {
        result.add(new Extent(runStart, runLen));
        runStart = next;
        runLen = 1;
      }
      cluster = next;
    }
    result.add(new Extent(runStart, runLen));
    return result;
  }

  /** Returns and resets the change in free clusters since the last call. */
  long takeFreeDelta() {
    long d = freeDelta;
    freeDelta = 0;
    return d;
  }

  boolean isDirty() {
    return !dirty.isEmpty();
  }

  /** Writes modified sectors to all FAT copies, coalescing consecutive sectors. */
  void flush() throws IOException {
    if (dirty.isEmpty()) {
      return;
    }
    List<long[]> runs = new ArrayList<>(); // {firstIndex, count}
    long runStart = -1;
    long prev = -2;
    for (long idx : dirty.keySet()) {
      if (idx != prev + 1) {
        if (runStart >= 0) {
          runs.add(new long[] {runStart, prev - runStart + 1});
        }
        runStart = idx;
      }
      prev = idx;
    }
    runs.add(new long[] {runStart, prev - runStart + 1});
    for (long[] run : runs) {
      ByteBuffer data = ByteBuffer.allocate((int) run[1] * BlockDevice.SECTOR_SIZE);
      for (long i = 0; i < run[1]; i++) {
        data.put(dirty.get(run[0] + i).duplicate().clear());
      }
      for (int fat = 0; fat < numberOfFats; fat++) {
        dev.write(fatLba + fat * sectorsPerFat + run[0], data.clear());
      }
    }
    for (var e : dirty.entrySet()) {
      clean.put(e.getKey(), e.getValue());
    }
    dirty.clear();
  }

  private ByteBuffer sector(long index) throws IOException {
    ByteBuffer s = dirty.get(index);
    if (s == null) {
      s = clean.get(index);
    }
    if (s == null) {
      int n = (int) Math.min(PREFETCH, sectorsPerFat - index);
      ByteBuffer bulk = ByteBuffer.allocate(n * BlockDevice.SECTOR_SIZE);
      dev.read(fatLba + index, bulk);
      for (int i = 0; i < n; i++) {
        if (!dirty.containsKey(index + i)) {
          clean.put(index + i, bulk.slice(i * BlockDevice.SECTOR_SIZE, BlockDevice.SECTOR_SIZE)
              .order(ByteOrder.LITTLE_ENDIAN));
        }
      }
      s = dirty.containsKey(index) ? dirty.get(index) : clean.get(index);
    }
    return s;
  }

  private void checkCluster(int cluster) {
    if (cluster < 2 || cluster > maxCluster) {
      throw new IllegalArgumentException("cluster out of range: " + cluster);
    }
  }
}
