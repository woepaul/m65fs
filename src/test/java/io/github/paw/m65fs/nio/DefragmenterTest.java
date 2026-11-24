package io.github.paw.m65fs.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.fat32.FatImages;
import io.github.paw.m65fs.fat32.FatSurgery;
import io.github.paw.m65fs.tools.Defrag;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scan + defragment workflow and the command line tool on an image. */
class DefragmenterTest {

  @TempDir Path tmp;
  FatImages.Image img;
  byte[] a = bytes(70_000, 1);
  byte[] b = bytes(200_000, 2);

  static byte[] bytes(int n, long seed) {
    byte[] x = new byte[n];
    new Random(seed).nextBytes(x);
    return x;
  }

  /** Two fragmented files, a contiguous one, and a fragmented directory. */
  @BeforeEach
  void image() throws IOException {
    img = FatImages.bareVolume(tmp, 40);
    try (var fs = Mega65FileSystem.openImage(img.file(), Map.of())) {
      Files.createDirectories(fs.getPath("/games/disks"));
      Files.write(fs.getPath("/games/disks/A.D81"), a);
      Files.write(fs.getPath("/games/B.PRG"), b);
      Files.write(fs.getPath("/ok.txt"), bytes(10_000, 3));
      Files.createDirectory(fs.getPath("/many"));
      Files.write(fs.getPath("/after.bin"), new byte[3000]); // takes the clusters after /many
      for (int i = 0; i < 40; i++) {
        Files.createFile(fs.getPath("/many/file" + i)); // /many grows elsewhere: fragmented directory
      }
    }
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false))) {
      FatSurgery.split(vol, vol.resolve(List.of("games", "disks", "A.D81")), 30, 10);
      FatSurgery.split(vol, vol.resolve(List.of("games", "B.PRG")), 100, 10);
      FatSurgery.split(vol, vol.resolve(List.of("games", "B.PRG")), 200, 10);
    }
    img.fsck();
  }

  @Test
  void scanReportsThenDefragmentFixes() throws IOException {
    try (var fs = (Mega65FileSystem) Mega65FileSystem.openImage(img.file(), Map.of())) {
      var report = Defragmenter.scan(fs);
      assertFalse(report.isClean());
      assertEquals(List.of(fs.getPath("/games/disks/A.D81"), fs.getPath("/games/B.PRG")), report.paths());
      assertEquals(2, report.files().get(0).fragments());
      assertEquals(3, report.files().get(1).fragments());
      assertEquals(200_000, report.files().get(1).size());
      assertEquals(List.of(fs.getPath("/many")), report.directories().stream().map(Defragmenter.Fragmented::path).toList());
      assertEquals(4 + 40, report.filesScanned());
      assertEquals(4, report.directoriesScanned());
      assertEquals(List.of(fs.getPath("/games/B.PRG")), Defragmenter.scan(fs, fs.getPath("/games")).paths().subList(1, 2));

      long free = fs.fileStore().getUnallocatedSpace();
      var seen = new java.util.ArrayList<Defragmenter.Result>();
      var results = Defragmenter.defragment(fs, report.paths(), seen::add);
      assertEquals(results, seen);
      assertEquals(List.of(Defragmenter.Outcome.DEFRAGMENTED, Defragmenter.Outcome.DEFRAGMENTED),
          results.stream().map(Defragmenter.Result::outcome).toList());
      assertTrue(Defragmenter.scan(fs).isClean());
      assertArrayEquals(a, Files.readAllBytes(fs.getPath("/games/disks/A.D81")));
      assertArrayEquals(b, Files.readAllBytes(fs.getPath("/games/B.PRG")));
      assertEquals(free, fs.fileStore().getUnallocatedSpace());

      var again = Defragmenter.defragment(fs, List.of(fs.getPath("/games/B.PRG"), fs.getPath("/gone"),
          fs.getPath("/games")), null);
      assertEquals(List.of(Defragmenter.Outcome.ALREADY_CONTIGUOUS, Defragmenter.Outcome.NOT_FOUND,
          Defragmenter.Outcome.NOT_A_FILE).stream().sorted().toList(),
          again.stream().map(Defragmenter.Result::outcome).sorted().toList());
    }
    img.fsck();
  }

  @Test
  void readOnlyFileSystemsCanOnlyBeScanned() throws IOException {
    try (var fs = (Mega65FileSystem) Mega65FileSystem.openImage(img.file(), Map.of("readOnly", true))) {
      var report = Defragmenter.scan(fs);
      assertEquals(2, report.files().size());
      assertThrows(ReadOnlyFileSystemException.class, () -> Defragmenter.defragment(fs, report.paths(), null));
    }
  }

  @Test
  void commandLineToolReportsFirstAndFixesWithFix() throws IOException {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    String image = img.file().toString();

    assertEquals(1, Defrag.run(new String[] {"--image", image}, new PrintStream(out), new PrintStream(err)));
    String report = out.toString();
    assertTrue(report.contains("2 fragmented file(s):"), report);
    assertTrue(report.contains("/games/B.PRG"), report);
    assertTrue(report.contains("fragmented directory (fine, not changed): /many"), report);
    assertTrue(report.contains("--fix"), report);
    img.fsck();

    out.reset();
    assertEquals(0, Defrag.run(new String[] {"--image", image, "--fix"}, new PrintStream(out), new PrintStream(err)));
    assertTrue(out.toString().contains("2 defragmented, 0 still fragmented"), out.toString());
    img.fsck();

    out.reset();
    assertEquals(0, Defrag.run(new String[] {"--image", image}, new PrintStream(out), new PrintStream(err)));
    assertTrue(out.toString().contains("no fragmented files"), out.toString());

    assertEquals(2, Defrag.run(new String[] {"--bogus"}, new PrintStream(out), new PrintStream(err)));
    assertEquals(2, Defrag.run(new String[] {"--image", tmp.resolve("missing.img").toString()}, new PrintStream(out),
        new PrintStream(err)));
    assertEquals(0, err.toString().isEmpty() ? 1 : 0);
  }
}
