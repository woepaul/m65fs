package io.github.paw.m65fs.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sector cache with read-ahead in front of a slow {@link BlockDevice} (the network).
 *
 * <ul>
 *   <li>Small reads (up to {@code readAhead} sectors) that miss the cache fetch
 *       {@code readAhead} sectors at once, like mega65_ftp's 16-sector batches; that makes
 *       walking FAT and directory sectors cheap.
 *   <li>Large reads (bulk file data) go straight to the device and are not cached, so they
 *       do not evict metadata.
 *   <li>Writes are write-through: the device is updated immediately and cached copies are
 *       replaced.
 * </ul>
 *
 * Thread-safe.
 */
public final class CachingBlockDevice implements BlockDevice {

  public static final int DEFAULT_READ_AHEAD = 16;
  public static final int DEFAULT_CAPACITY = 8192; // sectors = 4 MiB

  private final BlockDevice dev;
  private final int readAhead;
  private final Map<Long, byte[]> cache;
  private long hits;
  private long misses;

  public CachingBlockDevice(BlockDevice dev) {
    this(dev, DEFAULT_READ_AHEAD, DEFAULT_CAPACITY);
  }

  public CachingBlockDevice(BlockDevice dev, int readAhead, int capacitySectors) {
    this.dev = Objects.requireNonNull(dev);
    if (readAhead < 1 || capacitySectors < readAhead) {
      throw new IllegalArgumentException("invalid cache geometry");
    }
    this.readAhead = readAhead;
    this.cache = new LinkedHashMap<>(capacitySectors * 4 / 3 + 1, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
        return size() > capacitySectors;
      }
    };
  }

  @Override
  public long sectorCount() throws IOException {
    return dev.sectorCount();
  }

  @Override
  public synchronized void read(long lba, ByteBuffer buf) throws IOException {
    int n = BlockDevice.sectors(lba, buf);
    if (n > readAhead) {
      // Bulk transfer straight from the device. Safe because the cache is write-through and
      // therefore never holds data that differs from the device.
      dev.read(lba, buf);
      return;
    }
    for (int i = 0; i < n; i++) {
      byte[] s = cache.get(lba + i);
      if (s == null) {
        misses++;
        fill(lba + i);
        s = cache.get(lba + i);
      } else {
        hits++;
      }
      buf.put(s);
    }
  }

  /** Reads {@code readAhead} sectors starting at {@code lba} into the cache. */
  private void fill(long lba) throws IOException {
    long end = lba + readAhead;
    long count = dev.sectorCount();
    if (count > 0) {
      end = Math.min(end, count);
    }
    int n = (int) (end - lba);
    ByteBuffer b = ByteBuffer.allocate(n * SECTOR_SIZE);
    dev.read(lba, b);
    for (int i = 0; i < n; i++) {
      byte[] s = new byte[SECTOR_SIZE];
      b.get(i * SECTOR_SIZE, s);
      cache.putIfAbsent(lba + i, s);
    }
  }

  @Override
  public synchronized void write(long lba, ByteBuffer buf) throws IOException {
    int n = BlockDevice.sectors(lba, buf);
    int start = buf.position();
    dev.write(lba, buf);
    for (int i = 0; i < n; i++) {
      byte[] cached = cache.get(lba + i);
      if (cached != null) {
        buf.get(start + i * SECTOR_SIZE, cached);
      }
    }
  }

  /** Drops all cached sectors (e.g. after something else changed the card). */
  public synchronized void invalidate() {
    cache.clear();
  }

  public synchronized long hits() {
    return hits;
  }

  public synchronized long misses() {
    return misses;
  }

  @Override
  public void flush() throws IOException {
    dev.flush();
  }

  @Override
  public boolean isReadOnly() {
    return dev.isReadOnly();
  }

  @Override
  public void close() throws IOException {
    dev.close();
  }
}
