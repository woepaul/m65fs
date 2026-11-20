package io.github.paw.m65fs.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.fat32.FatImages;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mega65PathTest {

  @TempDir static Path tmp;
  static FileSystem fs;

  @BeforeAll
  static void open() throws IOException {
    var img = FatImages.bareVolume(tmp, 40);
    fs = FileSystems.newFileSystem(img.file(), Map.of());
  }

  @AfterAll
  static void close() throws IOException {
    fs.close();
  }

  Path p(String s, String... more) {
    return fs.getPath(s, more);
  }

  @Test
  void parsing() {
    assertEquals("/a/b/c", p("/a", "b", "c").toString());
    assertEquals("/a/b", p("//a///b/").toString());
    assertEquals("a/b", p("a/b").toString());
    assertEquals("", p("").toString());
    assertTrue(p("/x").isAbsolute());
    assertFalse(p("x").isAbsolute());
    assertThrows(InvalidPathException.class, () -> p("a\0b"));
  }

  @Test
  void components() {
    Path path = p("/games/arcade/pacman.prg");
    assertEquals(p("/"), path.getRoot());
    assertEquals(p("pacman.prg"), path.getFileName());
    assertEquals(p("/games/arcade"), path.getParent());
    assertEquals(p("/"), p("/games").getParent());
    assertNull(p("/").getParent());
    assertNull(p("/").getFileName());
    assertNull(p("games").getParent());
    assertEquals(3, path.getNameCount());
    assertEquals(p("arcade"), path.getName(1));
    assertEquals(p("arcade/pacman.prg"), path.subpath(1, 3));
    assertEquals(List.of("games", "arcade", "pacman.prg"),
        StreamSupport.stream(path.spliterator(), false).map(Path::toString).toList());
  }

  @Test
  void caseInsensitiveEquality() {
    assertEquals(p("/Games/README.TXT"), p("/games/readme.txt"));
    assertEquals(p("/Games/README.TXT").hashCode(), p("/games/readme.txt").hashCode());
    assertNotEquals(p("/games"), p("games"));
    assertEquals(0, p("/A").compareTo(p("/a")));
    assertTrue(p("/Games/x").startsWith(p("/GAMES")));
    assertTrue(p("/games/x.prg").endsWith(p("X.PRG")));
    assertFalse(p("/games/x.prg").startsWith(p("games")));
    assertTrue(p("/games/x").startsWith("/games"));
  }

  @Test
  void normalizeResolveRelativize() {
    assertEquals(p("/a/c"), p("/a/./b/../c").normalize());
    assertEquals(p("/c"), p("/../../c").normalize());
    assertEquals(p("../c"), p("a/../../c").normalize());
    assertEquals(p("/a/b/c"), p("/a/b").resolve("c"));
    assertEquals(p("/x"), p("/a/b").resolve("/x"));
    assertEquals(p("/a/b"), p("/a/b").resolve(""));
    assertEquals(p("/a/sibling"), p("/a/b").resolveSibling("sibling"));
    assertEquals(p("../c/d"), p("/a/b").relativize(p("/a/c/d")));
    assertEquals(p("/a/c/d"), p("/a/b").resolve(p("/a/b").relativize(p("/a/c/d"))).normalize());
    assertThrows(IllegalArgumentException.class, () -> p("/a").relativize(p("b")));
    assertEquals(p("/rel/x"), p("rel/x").toAbsolutePath());
  }

  @Test
  void uriRoundTrip() throws IOException {
    var img = FatImages.bareVolume(java.nio.file.Files.createDirectories(tmp.resolve("uri")), 40);
    URI root = URI.create("mega65://uritest/");
    try (FileSystem named = FileSystems.newFileSystem(root, Map.of("imageFile", img.file()))) {
      Path path = named.getPath("/Games/My Game.d81");
      URI uri = path.toUri();
      assertEquals("mega65", uri.getScheme());
      assertEquals("/Games/My Game.d81", uri.getPath());
      assertEquals(path, Path.of(uri));
      assertEquals(named, FileSystems.getFileSystem(root));
    }
    assertThrows(java.nio.file.FileSystemNotFoundException.class, () -> FileSystems.getFileSystem(root));
  }

  @Test
  void ipv6ZoneSpellings() {
    assertEquals("[fe80::1%enp14s0]", Mega65FileSystemProvider.authority(URI.create("mega65://[fe80::1%enp14s0]/x")));
    assertEquals("[fe80::1%enp14s0]", Mega65FileSystemProvider.authority(URI.create("mega65://[fe80::1%25enp14s0]/x")));
    assertEquals("auto", Mega65FileSystemProvider.authority(URI.create("mega65:///x")));
    assertEquals("auto", Mega65FileSystemProvider.authority(URI.create("mega65://auto/")));
  }

  @Test
  void globMatching() {
    var m = fs.getPathMatcher("glob:/games/**/*.{d81,prg}");
    assertTrue(m.matches(p("/games/a/b/X.D81")));
    assertTrue(m.matches(p("/games/a/x.prg")));
    assertFalse(m.matches(p("/games/x.txt")));
    var star = fs.getPathMatcher("glob:*.d8?");
    assertTrue(star.matches(p("disk.d81")));
    assertFalse(star.matches(p("dir/disk.d81")), "* does not cross directories");
    var cls = fs.getPathMatcher("glob:file[0-9!].txt");
    assertTrue(cls.matches(p("file7.txt")));
    assertTrue(cls.matches(p("file!.txt")));
    var neg = fs.getPathMatcher("glob:file[!0-9].txt");
    assertTrue(neg.matches(p("fileA.txt")));
    assertFalse(neg.matches(p("file1.txt")));
    assertTrue(fs.getPathMatcher("regex:.*\\.D81").matches(p("/x.d81")));
    assertTrue(fs.getPathMatcher("glob:a+b(c).txt").matches(p("a+b(c).txt")));
  }
}
