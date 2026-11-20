package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.fat32.FatImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The m65.* tools on an SD card image (M65_IMAGE). */
class M65fsCliTest {

  @TempDir Path tmp;
  FatImages.Image img;
  final ByteArrayOutputStream out = new ByteArrayOutputStream();
  final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @BeforeEach
  void image() throws IOException {
    img = FatImages.partitioned(Files.createDirectories(tmp.resolve("img")), 64);
  }

  /** {@code m65(cmd, args...)} as if {@code m65.cmd args...} was run. */
  int m65(String... args) {
    out.reset();
    err.reset();
    return M65fs.run(args, Map.of("M65_IMAGE", img.file().toString()), new PrintStream(out), new PrintStream(err));
  }

  String out() {
    return out.toString();
  }

  static byte[] bytes(int n, long seed) {
    byte[] b = new byte[n];
    new Random(seed).nextBytes(b);
    return b;
  }

  /** A local tree with a nested directory, a name with spaces and an empty directory. */
  Path localTree() throws IOException {
    Path game = tmp.resolve("local/My Game");
    Files.createDirectories(game.resolve("disks/extra"));
    Files.createDirectories(game.resolve("empty"));
    Files.write(game.resolve("readme.txt"), "hello mega65\n".getBytes());
    Files.write(game.resolve("disks/Disk 1.d81"), bytes(819_200, 1));
    Files.write(game.resolve("disks/extra/bonus.prg"), bytes(5000, 2));
    Files.setLastModifiedTime(game.resolve("readme.txt"), FileTime.from(Instant.parse("2024-05-06T07:08:10Z")));
    return game;
  }

  static List<String> tree(Path root) throws IOException {
    try (Stream<Path> s = Files.walk(root)) {
      return s.map(p -> root.relativize(p).toString() + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
    }
  }

  static void assertSameTree(Path a, Path b) throws IOException {
    assertEquals(tree(a), tree(b));
    try (Stream<Path> s = Files.walk(a)) {
      for (Path p : s.filter(Files::isRegularFile).toList()) {
        assertArrayEquals(Files.readAllBytes(p), Files.readAllBytes(b.resolve(a.relativize(p).toString())), p.toString());
      }
    }
  }

  @Test
  void cpCopiesTreesInAndOutLikeCp() throws IOException {
    Path game = localTree();
    assertEquals(1, m65("cp", game.toString(), "m65:/"), "directories need -r");
    assertTrue(err.toString().contains("m65.cp: -r not specified; omitting directory"), err.toString());

    assertEquals(0, m65("cp", "-rp", game.toString(), "m65:/"), err.toString());
    assertEquals(0, m65("ls", "-R", "/My Game"));
    assertTrue(out().startsWith("/My Game:\ndisks\nempty\nreadme.txt\n\n/My Game/disks:\nDisk 1.d81\nextra\n"), out());
    assertEquals(0, m65("cat", "m65:/my game/README.TXT"));
    assertEquals("hello mega65\n", out());

    // out: into an existing directory -> back/My Game (the name as stored)
    Path back = Files.createDirectories(tmp.resolve("back"));
    assertEquals(0, m65("cp", "-rp", "m65:/my game", back.toString()), err.toString());
    assertSameTree(game, back.resolve("My Game"));
    assertEquals(Files.getLastModifiedTime(game.resolve("readme.txt")).toMillis() / 2000,
        Files.getLastModifiedTime(back.resolve("My Game/readme.txt")).toMillis() / 2000, "-p keeps times");

    // out to a new name: the directory itself becomes the target
    assertEquals(0, m65("cp", "-R", "m65:/My Game/disks", tmp.resolve("disks-copy").toString()));
    assertSameTree(game.resolve("disks"), tmp.resolve("disks-copy"));
    img.fsck();
  }

  @Test
  void cpOverwritesByDefaultKeepsWithNAndHandlesSeveralSources() throws IOException {
    Path a = tmp.resolve("a.prg");
    Path b = tmp.resolve("b.prg");
    Files.write(a, bytes(3000, 1));
    Files.write(b, bytes(4000, 2));
    assertEquals(0, m65("mkdir", "-p", "m65:/prgs/old"));
    assertEquals(0, m65("cp", a.toString(), b.toString(), "m65:/prgs"));
    assertEquals(1, m65("cp", a.toString(), b.toString(), "m65:/prgs/a.prg"), "several sources need a directory");
    assertTrue(err.toString().contains("is not a directory"), err.toString());

    Files.write(a, "new".getBytes());
    assertEquals(0, m65("cp", "-n", a.toString(), "m65:/prgs/"));
    assertEquals(0, m65("cat", "/prgs/a.prg"));
    assertEquals(3000, out.size(), "-n keeps the existing file");
    assertEquals(0, m65("cp", a.toString(), "m65:/prgs/"));
    assertEquals(0, m65("cat", "/prgs/a.prg"));
    assertEquals("new", out(), "overwrites by default, like cp");

    assertEquals(0, m65("cp", "m65:/prgs/b.prg", "m65:/prgs/old/b copy.prg"), "card to card");
    assertEquals(0, m65("ls", "-l", "/prgs/old"));
    assertTrue(out().matches("(?s)-\\s+4000 .* b copy\\.prg\\n"), out());
    assertEquals(1, m65("cp", "m65:/prgs/b.prg", "m65:/prgs/B.PRG"));
    assertTrue(err.toString().contains("are the same file"), err.toString());
    assertEquals(1, m65("cp", "-r", "m65:/prgs", "m65:/prgs/old"));
    assertTrue(err.toString().contains("into itself"), err.toString());
    assertEquals(1, m65("cp", "m65:/missing", tmp.toString()));
    assertTrue(err.toString().contains("cannot stat 'm65:/missing': No such file or directory"), err.toString());
    assertEquals(1, m65("cp", "m65:/prgs/a.prg"));
    img.fsck();
  }

  @Test
  void mvRmMkdirLsStatDf() throws IOException {
    Files.write(tmp.resolve("one.prg"), bytes(3000, 4));
    assertEquals(0, m65("cp", tmp.resolve("one.prg").toString(), "m65:/"));
    assertEquals(0, m65("mkdir", "/dir", "m65:/dir2"));
    assertEquals(1, m65("mkdir", "/dir"));
    assertTrue(err.toString().contains("cannot create directory '/dir': File exists"), err.toString());
    assertEquals(0, m65("mv", "/one.prg", "/dir"));
    assertEquals(0, m65("mv", "m65:/dir/one.prg", "/dir/Renamed Program.prg"));
    assertEquals(0, m65("ls", "/dir", "/dir2"));
    assertEquals("/dir:\nRenamed Program.prg\n\n/dir2:\n", out());
    assertEquals(1, m65("mv", tmp.resolve("one.prg").toString(), "/dir"), "local files cannot be moved");
    assertEquals(0, m65("stat", "/DIR/renamed program.prg"));
    assertTrue(out().contains(" Short: RENAME~1.PRG") && out().contains("Contig: yes"), out());
    assertEquals(0, m65("df"));
    assertTrue(out().contains("Avail"), out());
    assertEquals(0, m65("defrag"));
    assertTrue(out().contains("no fragmented files"), out());

    assertEquals(1, m65("rm", "/dir"));
    assertTrue(err.toString().contains("cannot remove '/dir': Is a directory"), err.toString());
    assertEquals(1, m65("rm", "/nope"));
    assertEquals(0, m65("rm", "-f", "/nope"));
    assertEquals(0, m65("rm", "-rf", "/dir", "/dir2"));
    assertEquals(1, m65("rm", "-r", "/"));
    assertEquals(0, m65("ls"));
    assertEquals("", out());
    assertEquals(1, m65("ls", "-x"));
    assertTrue(err.toString().contains("invalid option -- 'x'"), err.toString());
    assertEquals(1, m65("boot", "m65:/x.d81"), "image file, not a MEGA65");
    assertEquals(1, m65("frobnicate"));
    img.fsck();
  }

  @Test
  void commandFromProgramName() {
    assertArrayEquals(new String[] {"ls", "-l"}, M65fs.withInvokedCommand("/usr/local/bin/m65.ls", new String[] {"-l"}));
    assertArrayEquals(new String[] {"ls"}, M65fs.withInvokedCommand("m65", new String[] {"ls"}));
    assertArrayEquals(new String[] {"ls"}, M65fs.withInvokedCommand(null, new String[] {"ls"}));
    assertEquals("/GAMES", M65fs.cardPath("m65:GAMES"));
    assertEquals("/", M65fs.cardPath("m65:"));
    assertEquals("/x", M65fs.cardPath("/x"));
  }

  @Test
  void help() {
    assertEquals(0, M65fs.run(new String[] {"help"}, Map.of(), new PrintStream(out), new PrintStream(err)));
    assertTrue(out().contains("m65.cp [-rRnfpv] SOURCE... DEST"));
    assertEquals(0, M65fs.run(new String[] {"cp", "--help"}, Map.of(), new PrintStream(out), new PrintStream(err)));
    assertNotEquals(0, M65fs.run(new String[0], Map.of(), new PrintStream(out), new PrintStream(err)));
    assertFalse(err.toString().isEmpty());
  }
}
