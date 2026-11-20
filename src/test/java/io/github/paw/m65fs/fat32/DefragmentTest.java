package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefragmentTest {

  @TempDir Path tmp;

  static byte[] read(Fat32Volume vol, DirEntry e) throws IOException {
    ByteBuffer b = ByteBuffer.allocate((int) e.size());
    vol.read(e, 0, b);
    return b.array();
  }

  @Test
  void makesAFragmentedFileContiguousAndKeepsEverythingElse() throws IOException {
    var img = FatImages.bareVolume(tmp, 40);
    byte[] data = new byte[300_000]; // 586 clusters of 512 bytes, last one partial
    new Random(3).nextBytes(data);
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false))) {
      DirEntry f = vol.writeContent(vol.createFile(vol.root(), "Fragmented File.bin"), data);
      f = vol.setAttributes(f, DirEntry.ATTR_READ_ONLY | DirEntry.ATTR_HIDDEN);
      vol.writeContent(vol.createFile(vol.root(), "after.bin"), new byte[5000]);
      FatSurgery.split(vol, f, 100, 20);
      FatSurgery.split(vol, vol.resolve(List.of("Fragmented File.bin")), 400, 50);
    }
    img.fsck();

    long free;
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false))) {
      DirEntry before = vol.resolve(List.of("Fragmented File.bin"));
      assertEquals(3, vol.extents(before).size());
      free = vol.freeClusters();
      DirEntry after = vol.defragment(before);
      assertEquals(1, vol.extents(after).size());
      assertArrayEquals(data, read(vol, after));
      assertEquals(before.size(), after.size());
      assertEquals(before.modified(), after.modified(), "times unchanged");
      assertEquals(before.created(), after.created());
      assertEquals(before.attributes(), after.attributes(), "attributes unchanged");
      assertEquals(free, vol.freeClusters(), "same number of clusters in use");
      assertSame(after, vol.defragment(after), "contiguous files are left alone");
    }
    img.fsck();
    assertArrayEquals(data, img.get("/Fragmented File.bin"), "mtools reads the moved file");
  }

  @Test
  void refusesWhenNoFreeRunIsLargeEnough() throws IOException {
    var img = FatImages.bareVolume(tmp, 40);
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false))) {
      DirEntry f = vol.writeContent(vol.createFile(vol.root(), "BIG.BIN"), new byte[4_000_000]);
      FatSurgery.split(vol, f, 10, 5);
      // fill the free space until no run of 7813 clusters (4 MB / 512 B) is left
      int i = 0;
      for (long largest; (largest = vol.largestFreeRun()) >= 7813; ) {
        vol.writeContent(vol.createFile(vol.root(), "FILL" + i++), new byte[(int) (largest - 100) * 512]);
      }
      DirEntry big = vol.resolve(List.of("BIG.BIN"));
      var e = assertThrows(NoContiguousSpaceException.class, () -> vol.defragment(big));
      assertEquals(2, vol.extents(vol.resolve(List.of("BIG.BIN"))).size(), "unchanged");
      assertEquals(big, vol.resolve(List.of("BIG.BIN")));
      assertEquals(true, e.getMessage().contains("BIG.BIN"), e.getMessage());
    }
    img.fsck();
  }
}
