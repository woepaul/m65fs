package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Write support, verified three ways after every test: our own reader, mtools, and
 * {@code fsck.fat -n}; plus the invariant that every regular file is contiguous.
 */
class Fat32VolumeWriteTest {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T16:30:41.37Z"), ZoneId.of("Europe/Vienna"));

  @TempDir Path tmp;
  FatImages.Image img;
  Fat32Volume vol;
  int bpc;

  @BeforeEach
  void setUp() throws IOException {
    img = FatImages.bareVolume(tmp, 40); // ~80k clusters of 512 bytes
    vol = open();
    bpc = vol.bootSector().bytesPerCluster();
  }

  @AfterEach
  void verify() throws IOException {
    if (vol != null) {
      assertAllFilesContiguous(vol, vol.root(), "");
      vol.close();
      img.fsck(); // throws if fsck.fat -n reports anything
    }
  }

  Fat32Volume open() throws IOException {
    return Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false), CLOCK);
  }

  Fat32Volume reopen() throws IOException {
    vol.close();
    vol = open();
    return vol;
  }

  // ---------------------------------------------------------------- files

  @Test
  void createAndWriteFilesOfVariousSizes() throws IOException {
    var rnd = new Random(1);
    int[] sizes = {0, 1, 511, 512, 513, bpc * 3, 100_000, 1_000_003};
    for (int size : sizes) {
      byte[] data = new byte[size];
      rnd.nextBytes(data);
      String name = "F" + size + ".BIN";
      var e = vol.writeContent(vol.createFile(vol.root(), name), data);
      assertEquals(size, e.size());
      assertArrayEquals(data, readAll(vol, vol.resolve(List.of(name))), name);
      assertArrayEquals(data, img.get("/" + name), "mtools sees " + name);
    }
    reopen();
    for (int size : sizes) {
      assertEquals(size, vol.resolve(List.of("F" + size + ".BIN")).size());
    }
  }

  @Test
  void entriesGetTimestampsAndArchiveBit() throws IOException {
    var e = vol.writeContent(vol.createFile(vol.root(), "T.TXT"), new byte[] {1});
    LocalDateTime expected = LocalDateTime.of(2026, 11, 20, 17, 30, 40); // Vienna local, 2s resolution
    assertEquals(expected, e.modified());
    assertEquals(LocalDateTime.of(2026, 11, 20, 17, 30, 41, 370_000_000), e.created());
    assertTrue(e.isArchive());
  }

  @Test
  void longAndUnicodeNamesAreReadableByMtools() throws IOException {
    List<String> names = List.of("readme.txt", "A long file name with spaces.txt", "Grüße aus Wien.txt",
        "x".repeat(200) + ".d81", "UPPER.TXT", "mIxEd.Prg", "no_extension", "two.dots.here");
    for (String n : names) {
      vol.writeContent(vol.createFile(vol.root(), n), n.getBytes());
    }
    assertEquals(Set.copyOf(names), img.list("/"));
    for (String n : names) {
      assertArrayEquals(n.getBytes(), img.get("/" + n));
    }
    // 8.3 names that are already upper case need no long-name entries
    assertEquals(1, vol.resolve(List.of("UPPER.TXT")).location().slotCount());
    assertTrue(vol.resolve(List.of("readme.txt")).location().slotCount() > 1);
  }

  @Test
  void shortAliasesAreUnique() throws IOException {
    for (int i = 0; i < 12; i++) {
      vol.createFile(vol.root(), "Long file name number " + i + ".txt");
    }
    var shorts = vol.list(vol.root()).stream().map(DirEntry::shortName).collect(Collectors.toSet());
    assertEquals(12, shorts.size());
    assertTrue(shorts.contains("LONGFI~1.TXT"), shorts.toString());
    assertTrue(shorts.contains("LONGF~10.TXT"), shorts.toString());
  }

  @Test
  void rejectsDuplicateAndInvalidNames() throws IOException {
    vol.createFile(vol.root(), "Game.prg");
    assertThrows(FileAlreadyExistsException.class, () -> vol.createFile(vol.root(), "GAME.PRG"));
    assertThrows(FileAlreadyExistsException.class, () -> vol.createDirectory(vol.root(), "game.prg"));
    // the 8.3 alias of an existing long name counts as taken, too
    vol.createFile(vol.root(), "A long name.txt");
    String alias = vol.resolve(List.of("A long name.txt")).shortName();
    assertThrows(FileAlreadyExistsException.class, () -> vol.createFile(vol.root(), alias));
    for (String bad : List.of("a/b", "a:b", "x?", "trailing.", "trailing ", "", "..", "x".repeat(256))) {
      assertThrows(InvalidPathException.class, () -> vol.createFile(vol.root(), bad), bad);
    }
  }

  // ---------------------------------------------------------------- contiguous rewrite policy

  @Test
  void rewriteSameSizeGoesToANewRunAndFreesTheOld() throws IOException {
    var e = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), random(10 * bpc, 1));
    int first = e.firstCluster();
    long freeBefore = vol.freeClusters();
    byte[] data = random(10 * bpc - 7, 2);
    e = vol.writeContent(e, data);
    assertNotEquals(first, e.firstCluster(), "never rewritten in place");
    assertTrue(vol.isContiguous(e));
    assertEquals(freeBefore, vol.freeClusters());
    assertArrayEquals(data, img.get("/A.BIN"));
  }

  @Test
  void shrinkFreesAllOldClusters() throws IOException {
    var e = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), random(10 * bpc, 1));
    long freeBefore = vol.freeClusters();
    byte[] data = random(3 * bpc, 2);
    e = vol.writeContent(e, data);
    assertEquals(3, vol.extents(e).getFirst().length());
    assertEquals(freeBefore + 7, vol.freeClusters());
    assertArrayEquals(data, img.get("/A.BIN"));
  }

  @Test
  void anInterruptedRewriteLeavesTheOldContent() throws IOException {
    byte[] old = random(10 * bpc, 1);
    var e = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), old);
    // the new content breaks off half way (Ctrl-C, lost connection)
    byte[] half = random(5 * bpc, 2);
    var broken = new java.nio.channels.ReadableByteChannel() {
      int pos;

      @Override
      public int read(java.nio.ByteBuffer dst) throws IOException {
        if (pos >= half.length) {
          throw new IOException("interrupted");
        }
        int n = Math.min(dst.remaining(), half.length - pos);
        dst.put(half, pos, n);
        pos += n;
        return n;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {}
    };
    var entry = e;
    assertThrows(IOException.class, () -> vol.writeContent(entry, broken, old.length));
    assertArrayEquals(old, img.get("/A.BIN"), "the old content is untouched");
  }

  @Test
  void growRelocatesWhenBlockedAndFreesOldRun() throws IOException {
    var a = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), random(2 * bpc, 1));
    vol.writeContent(vol.createFile(vol.root(), "B.BIN"), random(bpc, 2)); // directly behind A
    int oldFirst = a.firstCluster();
    long freeBefore = vol.freeClusters();
    byte[] data = random(5 * bpc, 3);
    a = vol.writeContent(a, data);
    assertNotEquals(oldFirst, a.firstCluster());
    assertTrue(vol.isContiguous(a));
    assertEquals(freeBefore - 3, vol.freeClusters(), "5 new clusters, 2 old ones freed");
    assertTrue(vol.extents(vol.root()).getFirst().length() >= 1);
    assertArrayEquals(data, img.get("/A.BIN"));
  }

  @Test
  void truncateToZeroFreesEverything() throws IOException {
    var e = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), random(4 * bpc, 1));
    long freeBefore = vol.freeClusters();
    e = vol.writeContent(e, new byte[0]);
    assertEquals(0, e.firstCluster());
    assertEquals(0, e.size());
    assertEquals(freeBefore + 4, vol.freeClusters());
    assertArrayEquals(new byte[0], img.get("/A.BIN"));
  }

  @Test
  void fragmentedFileIsDefragmentedOnWrite() throws IOException {
    var a = vol.writeContent(vol.createFile(vol.root(), "A.BIN"), random(bpc, 1));
    vol.writeContent(vol.createFile(vol.root(), "C.BIN"), random(bpc, 3));
    vol.close();
    // Other tools may leave fragmented files behind: link A's cluster to a far away free
    // cluster (both FATs + FSInfo free count), making A two extents long.
    int far = a.firstCluster() + 100;
    try (var dev = ImageFileBlockDevice.open(img.file(), false)) {
      var bs = Fat32Volume.open(dev).bootSector();
      for (int f = 0; f < 2; f++) {
        setFat(dev, bs.fatStart() + f * bs.sectorsPerFat(), a.firstCluster(), far);
        setFat(dev, bs.fatStart() + f * bs.sectorsPerFat(), far, 0x0FFFFFFF);
      }
      ByteBuffer fsinfo = ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      dev.read(bs.fsInfoSector(), fsinfo);
      fsinfo.putInt(488, fsinfo.getInt(488) - 1);
      dev.write(bs.fsInfoSector(), fsinfo.clear());
    }
    vol = open();
    a = vol.resolve(List.of("A.BIN"));
    assertEquals(List.of(new Extent(a.firstCluster(), 1), new Extent(far, 1)), vol.extents(a));

    byte[] data = random(2 * bpc, 4);
    a = vol.writeContent(a, data);
    assertTrue(vol.isContiguous(a), "rewrite relocated the fragmented file into one run");
    assertArrayEquals(data, img.get("/A.BIN"));
  }

  @Test
  void noContiguousSpaceLeavesOriginalUntouched() throws IOException {
    long free = vol.freeClusters();
    int n = 10;
    int each = (int) (free / n) - 2;
    List<DirEntry> files = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      files.add(vol.writeContent(vol.createFile(vol.root(), "F" + i + ".BIN"), new byte[each * bpc]));
    }
    for (int i = 1; i < n; i += 2) {
      vol.delete(files.get(i)); // holes of `each` clusters
    }
    assertTrue(vol.freeClusters() > 4L * each, "plenty of free space in total");
    byte[] original = random(bpc, 9);
    var victim = vol.writeContent(vol.createFile(vol.root(), "VICTIM.BIN"), original);
    var ex = assertThrows(NoContiguousSpaceException.class,
        () -> vol.writeContent(vol.resolve(List.of("VICTIM.BIN")), new byte[(each + 50) * bpc]));
    assertEquals(each + 50, ex.clustersNeeded());
    assertTrue(ex.largestFreeRun() < each + 50);
    assertEquals(victim, vol.resolve(List.of("VICTIM.BIN")));
    assertArrayEquals(original, img.get("/VICTIM.BIN"));
  }

  // ---------------------------------------------------------------- directories

  @Test
  void createNestedDirectories() throws IOException {
    var games = vol.createDirectory(vol.root(), "GAMES");
    var arcade = vol.createDirectory(games, "Arcade Classics");
    vol.writeContent(vol.createFile(arcade, "pacman.prg"), "waka".getBytes());
    assertArrayEquals("waka".getBytes(), img.get("/GAMES/Arcade Classics/pacman.prg"));
    assertEquals(Set.of("Arcade Classics"), img.list("/GAMES"));
    assertTrue(vol.resolve(List.of("games", "arcade classics")).isDirectory());
  }

  @Test
  void directoryGrowsAcrossManyClusters() throws IOException {
    var d = vol.createDirectory(vol.root(), "MANY");
    for (int i = 0; i < 300; i++) {
      vol.createFile(d, "a file with a long name " + i + ".dat");
    }
    assertTrue(vol.extents(d).stream().mapToInt(Extent::length).sum() > 50);
    var expected = IntStream.range(0, 300).mapToObj(i -> "a file with a long name " + i + ".dat")
        .collect(Collectors.toSet());
    assertEquals(expected, img.list("/MANY"));
    reopen();
    assertEquals(300, vol.list(vol.resolve(List.of("MANY"))).size());
  }

  @Test
  void deletedSlotsAreReused() throws IOException {
    var d = vol.createDirectory(vol.root(), "D");
    for (int i = 0; i < 20; i++) {
      vol.createFile(d, "FILE" + i + ".TXT");
    }
    int clusters = vol.extents(vol.resolve(List.of("D"))).stream().mapToInt(Extent::length).sum();
    for (int i = 0; i < 20; i++) {
      vol.delete(vol.resolve(List.of("D", "FILE" + i + ".TXT")));
    }
    for (int i = 0; i < 20; i++) {
      vol.createFile(vol.resolve(List.of("D")), "NEW" + i + ".TXT");
    }
    assertEquals(clusters, vol.extents(vol.resolve(List.of("D"))).stream().mapToInt(Extent::length).sum());
  }

  @Test
  void deleteFilesAndEmptyDirectories() throws IOException {
    long free = vol.freeClusters();
    var d = vol.createDirectory(vol.root(), "D");
    var f = vol.writeContent(vol.createFile(d, "x.bin"), random(5 * bpc, 1));
    assertThrows(DirectoryNotEmptyException.class, () -> vol.delete(vol.resolve(List.of("D"))));
    vol.delete(f);
    vol.delete(vol.resolve(List.of("D")));
    assertEquals(free, vol.freeClusters());
    assertThrows(NoSuchFileException.class, () -> vol.resolve(List.of("D")));
    assertEquals(Set.of(), img.list("/"));
  }

  @Test
  void renameAndMove() throws IOException {
    var src = vol.createDirectory(vol.root(), "SRC");
    var dst = vol.createDirectory(vol.root(), "DST");
    byte[] data = random(3 * bpc, 1);
    var f = vol.writeContent(vol.createFile(src, "old name.bin"), data);
    var renamed = vol.move(f, src, "New Name.bin");
    assertEquals(f.firstCluster(), renamed.firstCluster());
    assertEquals(Set.of("New Name.bin"), img.list("/SRC"));
    var moved = vol.move(renamed, dst, "FINAL.BIN");
    assertEquals(Set.of(), img.list("/SRC"));
    assertEquals(Set.of("FINAL.BIN"), img.list("/DST"));
    assertArrayEquals(data, img.get("/DST/FINAL.BIN"));
    assertEquals(f.modified(), moved.modified());
    // case-only rename of the same entry is allowed
    vol.move(vol.resolve(List.of("DST", "FINAL.BIN")), dst, "final.bin");
    assertEquals(Set.of("final.bin"), img.list("/DST"));
    // existing target
    vol.createFile(dst, "taken.bin");
    assertThrows(FileAlreadyExistsException.class,
        () -> vol.move(vol.resolve(List.of("DST", "final.bin")), dst, "TAKEN.BIN"));
  }

  @Test
  void movingDirectoryUpdatesDotDot() throws IOException {
    var a = vol.createDirectory(vol.root(), "A");
    var b = vol.createDirectory(a, "B");
    vol.createFile(b, "inside.txt");
    vol.move(b, vol.root(), "B");          // to root: '..' must become 0
    var b2 = vol.resolve(List.of("B"));
    var c = vol.createDirectory(vol.root(), "C");
    vol.move(b2, c, "B");                  // into C: '..' must point to C
    assertEquals(Set.of("inside.txt"), img.list("/C/B"));
    assertThrows(FileSystemException.class,
        () -> vol.move(vol.resolve(List.of("C")), vol.resolve(List.of("C", "B")), "C"));
  }

  @Test
  void attributesAndTimes() throws IOException {
    var f = vol.createFile(vol.root(), "A.TXT");
    f = vol.setAttributes(f, DirEntry.ATTR_READ_ONLY | DirEntry.ATTR_HIDDEN);
    assertTrue(f.isReadOnly());
    assertTrue(f.isHidden());
    assertFalse(f.isArchive());
    var t = LocalDateTime.of(1999, 12, 31, 23, 59, 58);
    f = vol.setTimes(f, null, t, null);
    assertEquals(t, vol.resolve(List.of("A.TXT")).modified());
  }

  @Test
  void freeSpaceAccountingMatchesFatScan() throws IOException {
    var rnd = new Random(8);
    for (int i = 0; i < 40; i++) {
      var e = vol.writeContent(vol.createFile(vol.root(), "R" + i), random(rnd.nextInt(40 * bpc), i));
      if (i % 3 == 0) {
        vol.writeContent(e, random(rnd.nextInt(60 * bpc), i + 100));
      }
      if (i % 5 == 0) {
        vol.delete(vol.resolve(List.of("R" + i)));
      }
    }
    long fsinfo = vol.freeClusters();
    reopen();
    // fsck (in @AfterEach) checks the FSInfo free count against the FAT, too
    assertEquals(fsinfo, vol.freeClusters());
  }

  @Test
  void readOnlyDeviceRejectsWrites() throws IOException {
    vol.close();
    try (var ro = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), true))) {
      assertThrows(java.nio.file.ReadOnlyFileSystemException.class, () -> ro.createFile(ro.root(), "X"));
    }
    vol = open();
  }

  // ---------------------------------------------------------------- helpers

  static void assertAllFilesContiguous(Fat32Volume v, DirEntry dir, String path) throws IOException {
    for (DirEntry e : v.list(dir)) {
      if (e.isDirectory()) {
        assertAllFilesContiguous(v, e, path + "/" + e.name());
      } else {
        assertTrue(v.isContiguous(e), "fragmented: " + path + "/" + e.name() + " " + v.extents(e));
      }
    }
  }

  static byte[] random(int n, long seed) {
    byte[] b = new byte[n];
    new Random(seed).nextBytes(b);
    return b;
  }

  static byte[] readAll(Fat32Volume vol, DirEntry e) throws IOException {
    var out = new ByteArrayOutputStream();
    ByteBuffer buf = ByteBuffer.allocate(70_000);
    long pos = 0;
    int n;
    while ((n = vol.read(e, pos, buf.clear())) > 0) {
      out.write(buf.array(), 0, n);
      pos += n;
    }
    return out.toByteArray();
  }

  private static void setFat(ImageFileBlockDevice dev, long fatLba, int cluster, int value) throws IOException {
    long lba = fatLba + cluster / 128;
    ByteBuffer s = ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    dev.read(lba, s);
    int off = (cluster % 128) * 4;
    s.putInt(off, (s.getInt(off) & 0xF0000000) | value);
    dev.write(lba, s.clear());
  }
}
