package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Fat32VolumeReadTest {

  @TempDir static Path tmp;

  static FatImages.Image image;
  static byte[] big;   // multi-cluster file
  static byte[] small;

  @BeforeAll
  static void createImage() throws IOException {
    image = FatImages.partitioned(tmp, 64);
    big = new byte[300_000 + 123];
    new Random(1).nextBytes(big);
    small = "hello mega65\n".getBytes(StandardCharsets.US_ASCII);

    image.put(big, "/BIG.BIN");
    image.put(small, "/README.TXT");
    image.put(small, "/A long file name with spaces.txt");
    image.put(small, "/Grüße aus Wien.txt");
    image.mkdir("/GAMES");
    image.mkdir("/GAMES/Arcade Classics");
    image.put(big, "/GAMES/Arcade Classics/game.d81");
    image.mkdir("/MANY");
    for (int i = 0; i < 100; i++) {
      image.put(new byte[] {(byte) i}, "/MANY/file number " + i + ".dat");
    }
  }

  static Fat32Volume open() throws IOException {
    return Fat32Volume.open(ImageFileBlockDevice.open(image.file(), true));
  }

  @Test
  void findsPartitionAndParsesBootSector() throws IOException {
    try (var vol = open()) {
      assertEquals(FatImages.PARTITION_LBA, vol.volumeLba());
      var bs = vol.bootSector();
      assertEquals(512, bs.bytesPerSector());
      assertEquals(1, bs.sectorsPerCluster());
      assertEquals(2, bs.numberOfFats());
      assertEquals("M65TEST", bs.volumeLabel());
      assertTrue(bs.clusterCount() > 0);
    }
  }

  @Test
  void opensBareVolume(@TempDir Path dir) throws IOException {
    var bare = FatImages.bareVolume(dir, 40);
    bare.put(small, "/X.TXT");
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(bare.file(), true))) {
      assertEquals(0, vol.volumeLba());
      assertEquals(List.of("X.TXT"), names(vol.list(vol.root())));
    }
  }

  @Test
  void listsRootWithLongAndShortNames() throws IOException {
    try (var vol = open()) {
      assertEquals(
          Set.of("BIG.BIN", "README.TXT", "A long file name with spaces.txt", "Grüße aus Wien.txt", "GAMES", "MANY"),
          Set.copyOf(names(vol.list(vol.root()))));
    }
  }

  @Test
  void entriesCarryTypeSizeAndTimes() throws IOException {
    try (var vol = open()) {
      var games = vol.find(vol.root(), "games").orElseThrow();
      assertTrue(games.isDirectory());
      assertFalse(games.isRegularFile());
      var bigEntry = vol.find(vol.root(), "big.bin").orElseThrow();
      assertTrue(bigEntry.isRegularFile());
      assertEquals(big.length, bigEntry.size());
      assertTrue(bigEntry.modified() != null);
    }
  }

  @Test
  void longNamesHaveAShortAlias() throws IOException {
    try (var vol = open()) {
      var e = vol.find(vol.root(), "A long file name with spaces.txt").orElseThrow();
      assertTrue(e.shortName().matches("[A-Z0-9_~]{1,8}\\.TXT"), e.shortName());
      assertTrue(e.location().slotCount() > 1, "long name entries + short entry");
      // the alias resolves too
      assertEquals(e, vol.find(vol.root(), e.shortName()).orElseThrow());
    }
  }

  @Test
  void lookupIsCaseInsensitive() throws IOException {
    try (var vol = open()) {
      assertTrue(vol.find(vol.root(), "readme.txt").isPresent());
      assertTrue(vol.find(vol.root(), "GRÜSSE AUS WIEN.TXT").isEmpty(), "no Unicode case folding beyond equalsIgnoreCase");
      assertTrue(vol.find(vol.root(), "grüße AUS wien.TXT").isPresent());
    }
  }

  @Test
  void readsWholeFiles() throws IOException {
    try (var vol = open()) {
      assertArrayEquals(big, readAll(vol, vol.resolve(List.of("BIG.BIN"))));
      assertArrayEquals(small, readAll(vol, vol.resolve(List.of("README.TXT"))));
      assertArrayEquals(big, readAll(vol, vol.resolve(List.of("games", "arcade classics", "GAME.D81"))));
    }
  }

  @Test
  void readsAtArbitraryOffsetsAndLengths() throws IOException {
    try (var vol = open()) {
      var e = vol.resolve(List.of("BIG.BIN"));
      var rnd = new Random(3);
      for (int i = 0; i < 200; i++) {
        long pos = rnd.nextInt(big.length + 10);
        int len = 1 + rnd.nextInt(5000);
        ByteBuffer buf = ByteBuffer.allocate(len);
        int n = vol.read(e, pos, buf);
        if (pos >= big.length) {
          assertEquals(-1, n);
          continue;
        }
        int expected = (int) Math.min(len, big.length - pos);
        assertEquals(expected, n);
        byte[] want = new byte[expected];
        System.arraycopy(big, (int) pos, want, 0, expected);
        assertArrayEquals(want, java.util.Arrays.copyOf(buf.array(), n), "pos=" + pos + " len=" + len);
      }
    }
  }

  @Test
  void listsDirectorySpanningManyClusters() throws IOException {
    try (var vol = open()) {
      var many = vol.resolve(List.of("MANY"));
      assertTrue(vol.extents(many).stream().mapToInt(Extent::length).sum() > 1);
      var expected = IntStream.range(0, 100).mapToObj(i -> "file number " + i + ".dat").collect(Collectors.toSet());
      assertEquals(expected, Set.copyOf(names(vol.list(many))));
      var e42 = vol.find(many, "FILE NUMBER 42.DAT").orElseThrow();
      ByteBuffer one = ByteBuffer.allocate(4);
      assertEquals(1, vol.read(e42, 0, one));
      assertEquals(42, one.get(0));
    }
  }

  @Test
  void filesWrittenByMtoolsAreContiguous() throws IOException {
    try (var vol = open()) {
      var e = vol.resolve(List.of("BIG.BIN"));
      assertTrue(vol.isContiguous(e));
      assertEquals(1, vol.extents(e).size());
      assertEquals((big.length + 511) / 512, vol.extents(e).getFirst().length());
    }
  }

  @Test
  void resolveErrors() throws IOException {
    try (var vol = open()) {
      assertThrows(NoSuchFileException.class, () -> vol.resolve(List.of("nope")));
      assertThrows(NotDirectoryException.class, () -> vol.resolve(List.of("README.TXT", "x")));
      assertEquals(vol.root(), vol.resolve(List.of()));
    }
  }

  private static List<String> names(List<DirEntry> entries) {
    return entries.stream().map(DirEntry::name).toList();
  }

  private static byte[] readAll(Fat32Volume vol, DirEntry e) throws IOException {
    var out = new ByteArrayOutputStream();
    ByteBuffer buf = ByteBuffer.allocate(7000); // deliberately not sector aligned
    long pos = 0;
    int n;
    while ((n = vol.read(e, pos, buf.clear())) > 0) {
      out.write(buf.array(), 0, n);
      pos += n;
    }
    return out.toByteArray();
  }
}
