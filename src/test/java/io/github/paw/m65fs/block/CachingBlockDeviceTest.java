package io.github.paw.m65fs.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CachingBlockDeviceTest {

  /** In-memory device that records device reads as (lba, sectors). */
  static final class MemDevice implements BlockDevice {
    final byte[] data;
    final List<long[]> reads = new ArrayList<>();

    MemDevice(int sectors) {
      data = new byte[sectors * SECTOR_SIZE];
      for (int i = 0; i < data.length; i++) {
        data[i] = (byte) (i / SECTOR_SIZE * 7 + i);
      }
    }

    @Override public long sectorCount() { return data.length / SECTOR_SIZE; }

    @Override
    public void read(long lba, ByteBuffer buf) {
      int n = BlockDevice.sectors(lba, buf);
      reads.add(new long[] {lba, n});
      buf.put(data, (int) lba * SECTOR_SIZE, n * SECTOR_SIZE);
    }

    @Override
    public void write(long lba, ByteBuffer buf) {
      int n = BlockDevice.sectors(lba, buf);
      buf.get(data, (int) lba * SECTOR_SIZE, n * SECTOR_SIZE);
    }

    @Override public void flush() {}
    @Override public boolean isReadOnly() { return false; }
    @Override public void close() {}
  }

  @Test
  void smallReadsTriggerReadAheadAndHitTheCache() throws IOException {
    var mem = new MemDevice(100);
    var c = new CachingBlockDevice(mem, 16, 64);
    for (int lba = 10; lba < 26; lba++) {
      assertArrayEquals(sector(mem, lba), read(c, lba, 1));
    }
    assertEquals(1, mem.reads.size(), "one 16-sector read-ahead serves all 16 reads");
    assertArrayEquals(new long[] {10, 16}, mem.reads.getFirst());
    assertEquals(15, c.hits());
  }

  @Test
  void readAheadIsClippedAtDeviceEnd() throws IOException {
    var mem = new MemDevice(20);
    var c = new CachingBlockDevice(mem, 16, 64);
    assertArrayEquals(sector(mem, 18), read(c, 18, 1));
    assertArrayEquals(new long[] {18, 2}, mem.reads.getFirst());
  }

  @Test
  void bulkReadsBypassTheCache() throws IOException {
    var mem = new MemDevice(100);
    var c = new CachingBlockDevice(mem, 16, 64);
    byte[] got = read(c, 0, 40);
    assertArrayEquals(Arrays.copyOf(mem.data, 40 * 512), got);
    read(c, 5, 1);
    assertEquals(2, mem.reads.size(), "bulk read did not populate the cache");
  }

  @Test
  void writesAreWriteThroughAndUpdateCache() throws IOException {
    var mem = new MemDevice(100);
    var c = new CachingBlockDevice(mem, 16, 64);
    read(c, 0, 1); // caches 0..15
    byte[] fresh = new byte[2 * 512];
    Arrays.fill(fresh, (byte) 0x5a);
    c.write(3, ByteBuffer.wrap(fresh));
    assertArrayEquals(Arrays.copyOf(fresh, 512), sector(mem, 3), "device updated");
    assertArrayEquals(Arrays.copyOf(fresh, 512), read(c, 4, 1), "cache updated");
    assertEquals(1, mem.reads.size());
  }

  private static byte[] sector(MemDevice m, int lba) {
    return Arrays.copyOfRange(m.data, lba * 512, (lba + 1) * 512);
  }

  private static byte[] read(BlockDevice d, long lba, int n) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(n * 512);
    d.read(lba, b);
    return b.array();
  }
}
