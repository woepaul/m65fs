package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a file whose cluster chain is split in two runs (as other tools may leave behind). */
class FragmentedFileReadTest {

  @Test
  void readsFragmentedFileAndReportsIt(@TempDir Path dir) throws IOException {
    var img = FatImages.bareVolume(dir, 40);
    byte[] data = new byte[4 * 512 - 100]; // 4 clusters of 512 bytes
    new Random(5).nextBytes(data);
    img.put(data, "/D.BIN");

    int first;
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), true))) {
      var e = vol.resolve(List.of("D.BIN"));
      assertEquals(List.of(new Extent(e.firstCluster(), 4)), vol.extents(e));
      first = e.firstCluster();
    }

    // Move clusters first+2, first+3 to first+10, first+11 and relink the chain.
    try (var dev = ImageFileBlockDevice.open(img.file(), false)) {
      var vol = Fat32Volume.open(dev);
      var bs = vol.bootSector();
      for (int k = 2; k < 4; k++) {
        ByteBuffer sector = ByteBuffer.allocate(512);
        dev.read(vol.clusterLba(first + k), sector);
        dev.write(vol.clusterLba(first + 8 + k), sector.flip());
      }
      for (int fat = 0; fat < 2; fat++) {
        long fatLba = bs.fatStart() + fat * bs.sectorsPerFat();
        setEntry(dev, fatLba, first + 1, first + 10);
        setEntry(dev, fatLba, first + 2, 0);
        setEntry(dev, fatLba, first + 3, 0);
        setEntry(dev, fatLba, first + 10, first + 11);
        setEntry(dev, fatLba, first + 11, 0x0FFFFFFF);
      }
    }
    img.fsck(); // fails (non-zero exit) if the hand-made chain is inconsistent

    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), true))) {
      var e = vol.resolve(List.of("D.BIN"));
      assertEquals(List.of(new Extent(first, 2), new Extent(first + 10, 2)), vol.extents(e));
      assertFalse(vol.isContiguous(e));
      ByteBuffer buf = ByteBuffer.allocate(data.length + 50);
      assertEquals(data.length, vol.read(e, 0, buf));
      assertArrayEquals(data, java.util.Arrays.copyOf(buf.array(), data.length));
      // a read spanning the gap between the two runs
      ByteBuffer mid = ByteBuffer.allocate(600);
      assertEquals(600, vol.read(e, 2 * 512 - 300, mid));
      assertArrayEquals(java.util.Arrays.copyOfRange(data, 2 * 512 - 300, 2 * 512 + 300), mid.array());
    }
  }

  private static void setEntry(ImageFileBlockDevice dev, long fatLba, int cluster, int value) throws IOException {
    long lba = fatLba + cluster / 128;
    ByteBuffer s = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    dev.read(lba, s);
    int off = (cluster % 128) * 4;
    s.putInt(off, (s.getInt(off) & 0xF0000000) | value);
    dev.write(lba, s.clear());
  }
}
