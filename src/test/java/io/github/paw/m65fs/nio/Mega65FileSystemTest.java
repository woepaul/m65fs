package io.github.paw.m65fs.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.fat32.FatImages;
import io.github.paw.m65fs.fat32.NoContiguousSpaceException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code java.nio.file.Files} API on an image-backed MEGA65 file system. */
class Mega65FileSystemTest {

  @TempDir Path tmp;
  FatImages.Image img;
  FileSystem fs;

  @BeforeEach
  void open() throws IOException {
    img = FatImages.bareVolume(tmp, 40);
    fs = FileSystems.newFileSystem(URI.create("mega65://test/"), Map.of("imageFile", img.file().toString(),
        "zone", "Europe/Vienna"));
  }

  @AfterEach
  void verify() throws IOException {
    if (fs.isOpen()) {
      assertAllContiguous(fs.getPath("/"));
      fs.close();
    }
    img.fsck();
  }

  Path p(String s) {
    return fs.getPath(s);
  }

  @Test
  void providerIsInstalledAndRegistered() {
    assertInstanceOf(Mega65FileSystemProvider.class, fs.provider());
    assertTrue(FileSystems.getFileSystem(URI.create("mega65://test/")) == fs);
    assertThrows(java.nio.file.FileSystemAlreadyExistsException.class,
        () -> FileSystems.newFileSystem(URI.create("mega65://TEST/"), Map.of("imageFile", img.file())));
    assertEquals(Set.of("basic", "dos", "mega65"), fs.supportedFileAttributeViews());
  }

  @Test
  void writeAndReadWithFilesHelpers() throws IOException {
    Files.writeString(p("/hello.txt"), "Hello MEGA65\n");
    assertEquals("Hello MEGA65\n", Files.readString(p("/HELLO.TXT")));
    assertArrayEquals("Hello MEGA65\n".getBytes(), img.get("/hello.txt"));

    Files.write(p("/lines.txt"), List.of("10 PRINT \"HI\"", "20 GOTO 10"));
    assertEquals(List.of("10 PRINT \"HI\"", "20 GOTO 10"), Files.readAllLines(p("/lines.txt")));
    try (BufferedWriter w = Files.newBufferedWriter(p("/buffered.txt"))) {
      for (int i = 0; i < 10_000; i++) {
        w.write("line " + i + "\n");
      }
    }
    try (Stream<String> lines = Files.lines(p("/buffered.txt"))) {
      assertEquals(10_000, lines.count());
    }
    assertEquals(13, Files.size(p("/hello.txt")));
  }

  @Test
  void streams() throws IOException {
    byte[] data = random(3_000_000, 1);
    try (OutputStream out = Files.newOutputStream(p("/big.bin"))) {
      for (int off = 0; off < data.length; off += 100_000) {
        out.write(data, off, Math.min(100_000, data.length - off));
      }
    }
    try (InputStream in = Files.newInputStream(p("/big.bin"))) {
      assertArrayEquals(data, in.readAllBytes());
    }
    assertArrayEquals(data, img.get("/big.bin"));
  }

  @Test
  void contentIsWrittenOnClose() throws IOException {
    Files.writeString(p("/a.txt"), "old");
    try (SeekableByteChannel ch = Files.newByteChannel(p("/a.txt"), StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
      ch.write(ByteBuffer.wrap("new content".getBytes()));
      assertEquals("old", new String(img.get("/a.txt")), "card unchanged until close");
    }
    assertEquals("new content", Files.readString(p("/a.txt")));
  }

  @Test
  void appendAndRandomAccess() throws IOException {
    Files.writeString(p("/log.txt"), "one\n");
    Files.writeString(p("/log.txt"), "two\n", StandardOpenOption.APPEND);
    assertEquals("one\ntwo\n", Files.readString(p("/log.txt")));

    try (SeekableByteChannel ch = Files.newByteChannel(p("/log.txt"), StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      ch.position(4);
      ch.write(ByteBuffer.wrap("TWO".getBytes()));
      ch.position(20);
      ch.write(ByteBuffer.wrap("!".getBytes())); // gap is zero-filled
      ch.position(0);
      ByteBuffer b = ByteBuffer.allocate(3);
      ch.read(b);
      assertEquals("one", new String(b.array()));
      assertEquals(21, ch.size());
      ch.truncate(8);
    }
    assertEquals("one\nTWO\n", Files.readString(p("/log.txt")));
  }

  @Test
  void openOptions() throws IOException {
    assertThrows(NoSuchFileException.class, () -> Files.newByteChannel(p("/missing")));
    assertThrows(NoSuchFileException.class, () -> Files.newByteChannel(p("/missing"), StandardOpenOption.WRITE));
    assertThrows(NoSuchFileException.class, () -> Files.writeString(p("/no/such/dir.txt"), "x"));
    Files.writeString(p("/x.txt"), "x");
    assertThrows(FileAlreadyExistsException.class,
        () -> Files.newByteChannel(p("/x.txt"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    Files.createDirectory(p("/dir"));
    assertThrows(IOException.class, () -> Files.readAllBytes(p("/dir")));
    // TRUNCATE_EXISTING without writing still truncates
    Files.newByteChannel(p("/x.txt"), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).close();
    assertEquals(0, Files.size(p("/x.txt")));
    // DELETE_ON_CLOSE
    Files.newByteChannel(p("/tmp.bin"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        StandardOpenOption.DELETE_ON_CLOSE).close();
    assertFalse(Files.exists(p("/tmp.bin")));
  }

  @Test
  void largeWriteSpillsToTempFile() throws IOException {
    byte[] data = random(9_500_000, 2); // > 8 MiB memory limit
    Files.write(p("/huge.bin"), data);
    assertArrayEquals(data, Files.readAllBytes(p("/huge.bin")));
  }

  @Test
  void directoriesAndListing() throws IOException {
    Files.createDirectories(p("/games/arcade/classics"));
    Files.writeString(p("/games/arcade/pacman.prg"), "waka");
    Files.writeString(p("/games/Readme.TXT"), "hi");
    try (Stream<Path> s = Files.list(p("/games"))) {
      assertEquals(Set.of(p("/games/arcade"), p("/games/readme.txt")), s.collect(Collectors.toSet()));
    }
    try (Stream<Path> s = Files.walk(p("/"))) {
      assertEquals(6, s.count());
    }
    try (Stream<Path> s = Files.find(p("/"), 10, (path, a) -> a.isRegularFile() && path.toString().endsWith(".prg"))) {
      assertEquals(List.of(p("/games/arcade/pacman.prg")), s.toList());
    }
    try (var ds = Files.newDirectoryStream(p("/games"), "*.txt")) {
      assertEquals(List.of("Readme.TXT"), Stream.of(ds.iterator().next()).map(x -> x.getFileName().toString()).toList());
    }
    assertTrue(Files.isDirectory(p("/GAMES/ARCADE")));
    assertEquals(p("/games/arcade/pacman.prg"), p("/GAMES/ARCADE/PACMAN.PRG").toRealPath());
    assertEquals("/games/arcade/pacman.prg", p("/GAMES/ARCADE/PACMAN.PRG").toRealPath().toString());
    assertThrows(FileAlreadyExistsException.class, () -> Files.createDirectory(p("/Games")));
    assertThrows(DirectoryNotEmptyException.class, () -> Files.delete(p("/games")));
  }

  @Test
  void deleteAndExists() throws IOException {
    Files.writeString(p("/f.txt"), "x");
    assertTrue(Files.exists(p("/F.TXT")));
    Files.delete(p("/f.txt"));
    assertFalse(Files.exists(p("/f.txt")));
    assertThrows(NoSuchFileException.class, () -> Files.delete(p("/f.txt")));
    assertFalse(Files.deleteIfExists(p("/f.txt")));
  }

  @Test
  void copyAndMoveInsideTheCard() throws IOException {
    byte[] data = random(200_000, 3);
    Files.write(p("/orig.bin"), data);
    Files.setLastModifiedTime(p("/orig.bin"), FileTime.from(Instant.parse("2001-02-03T04:05:06Z")));
    Files.copy(p("/orig.bin"), p("/copy.bin"), StandardCopyOption.COPY_ATTRIBUTES);
    assertArrayEquals(data, Files.readAllBytes(p("/copy.bin")));
    assertEquals(Files.getLastModifiedTime(p("/orig.bin")), Files.getLastModifiedTime(p("/copy.bin")));
    assertThrows(FileAlreadyExistsException.class, () -> Files.copy(p("/orig.bin"), p("/copy.bin")));
    Files.writeString(p("/other.txt"), "other");
    Files.copy(p("/other.txt"), p("/copy.bin"), StandardCopyOption.REPLACE_EXISTING);
    assertEquals("other", Files.readString(p("/copy.bin")));

    Files.createDirectory(p("/dir"));
    Files.move(p("/orig.bin"), p("/dir/moved.bin"));
    assertFalse(Files.exists(p("/orig.bin")));
    assertArrayEquals(data, Files.readAllBytes(p("/dir/moved.bin")));
    Files.move(p("/dir/moved.bin"), p("/dir/MOVED.BIN")); // case-only rename
    assertEquals(List.of("MOVED.BIN"), names(p("/dir")));
    assertThrows(FileAlreadyExistsException.class, () -> Files.move(p("/other.txt"), p("/dir/moved.bin")));
    Files.move(p("/other.txt"), p("/dir/moved.bin"), StandardCopyOption.REPLACE_EXISTING);
    assertEquals("other", Files.readString(p("/dir/moved.bin")));
    assertThrows(AtomicMoveNotSupportedException.class,
        () -> Files.move(p("/copy.bin"), p("/c2.bin"), StandardCopyOption.ATOMIC_MOVE));
    Files.move(p("/dir"), p("/renamed dir"));
    assertTrue(Files.exists(p("/renamed dir/moved.bin")));
  }

  @Test
  void copyBetweenHostAndCard() throws IOException {
    Path local = tmp.resolve("game.d81");
    byte[] d81 = random(819_200, 4);
    Files.write(local, d81);
    Files.copy(local, p("/GAME.D81"));
    assertArrayEquals(d81, img.get("/GAME.D81"));
    Path back = tmp.resolve("back.d81");
    Files.copy(p("/game.d81"), back);
    assertArrayEquals(d81, Files.readAllBytes(back));
    Files.createDirectory(p("/tree"));
    Files.writeString(p("/tree/a.txt"), "a");
    Path hostTree = tmp.resolve("tree");
    Files.createDirectory(hostTree);
    try (Stream<Path> s = Files.list(p("/tree"))) {
      for (Path f : s.toList()) {
        Files.copy(f, hostTree.resolve(f.getFileName().toString()));
      }
    }
    assertEquals("a", Files.readString(hostTree.resolve("a.txt")));
  }

  @Test
  void attributes() throws IOException {
    Files.write(p("/game.d81"), random(819_200, 5));
    BasicFileAttributes basic = Files.readAttributes(p("/game.d81"), BasicFileAttributes.class);
    assertTrue(basic.isRegularFile());
    assertEquals(819_200, basic.size());
    assertTrue(secondsBetween(basic.lastModifiedTime().toInstant(), Instant.now()) < 120);

    Mega65FileAttributes m = Files.readAttributes(p("/game.d81"), Mega65FileAttributes.class);
    assertTrue(m.isContiguous());
    assertEquals("GAME.D81", m.shortName());
    assertTrue(m.startSector() > 0);
    assertTrue(m.isArchive());

    Map<String, Object> map = Files.readAttributes(p("/game.d81"), "mega65:shortName,contiguous,size");
    assertEquals(Map.of("shortName", "GAME.D81", "contiguous", true, "size", 819_200L), map);
    assertEquals(819_200L, Files.getAttribute(p("/game.d81"), "size"));
    assertEquals(9, Files.readAttributes(p("/game.d81"), "*").size());

    Files.setAttribute(p("/game.d81"), "dos:hidden", true);
    assertTrue(Files.isHidden(p("/game.d81")));
    DosFileAttributeView dos = Files.getFileAttributeView(p("/game.d81"), DosFileAttributeView.class);
    dos.setReadOnly(true);
    assertTrue(Files.readAttributes(p("/game.d81"), DosFileAttributes.class).isReadOnly());
    assertFalse(Files.isWritable(p("/game.d81")));
    assertThrows(AccessDeniedException.class, () -> Files.writeString(p("/game.d81"), "x"));
    dos.setReadOnly(false);
    dos.setHidden(false);

    FileTime t = FileTime.from(Instant.parse("1999-12-31T22:59:58Z"));
    Files.setLastModifiedTime(p("/game.d81"), t);
    assertEquals(t, Files.getLastModifiedTime(p("/game.d81")));
    assertTrue(Files.readAttributes(p("/"), BasicFileAttributes.class).isDirectory());
    assertEquals(Files.readAttributes(p("/game.d81"), BasicFileAttributes.class).fileKey(),
        Files.readAttributes(p("/GAME.D81"), BasicFileAttributes.class).fileKey());
    assertTrue(Files.isSameFile(p("/game.d81"), p("/./GAME.D81")));
  }

  @Test
  void fileStore() throws IOException {
    var store = (Mega65FileStore) Files.getFileStore(p("/"));
    assertEquals("M65TEST", store.name());
    assertEquals("fat32", store.type());
    long free = store.getUnallocatedSpace();
    assertTrue(store.getTotalSpace() > free && free > 30_000_000);
    Files.write(p("/f.bin"), new byte[1_000_000]);
    assertEquals(free - 1_000_448, store.getUnallocatedSpace()); // 1954 clusters of 512 bytes
    assertTrue(store.getLargestContiguousSpace() <= store.getUnallocatedSpace());
    assertTrue(store.supportsFileAttributeView("mega65"));
  }

  @Test
  void noContiguousSpaceSurfacesFromClose() throws IOException {
    var store = (Mega65FileStore) Files.getFileStore(p("/"));
    long free = store.getUnallocatedSpace();
    int n = 8;
    long each = free / n - 4096;
    for (int i = 0; i < n; i++) {
      Files.write(p("/f" + i), new byte[(int) each]);
    }
    for (int i = 1; i < n; i += 2) {
      Files.delete(p("/f" + i));
    }
    Files.writeString(p("/victim.txt"), "keep me");
    var ex = assertThrows(NoContiguousSpaceException.class,
        () -> Files.write(p("/victim.txt"), new byte[(int) (each + 100_000)]));
    assertTrue(ex.getMessage().contains("no contiguous free space"), ex.getMessage());
    assertEquals("keep me", Files.readString(p("/victim.txt")));
  }

  @Test
  void readOnlyFileSystem() throws IOException {
    Files.writeString(p("/x.txt"), "x");
    fs.close();
    try (FileSystem ro = FileSystems.newFileSystem(img.file(), Map.of("readOnly", true))) {
      assertTrue(ro.isReadOnly());
      assertEquals("x", Files.readString(ro.getPath("/x.txt")));
      assertThrows(java.nio.file.ReadOnlyFileSystemException.class, () -> Files.writeString(ro.getPath("/y.txt"), "y"));
      assertThrows(java.nio.file.ReadOnlyFileSystemException.class, () -> Files.createDirectory(ro.getPath("/d")));
    }
  }

  @Test
  void closedFileSystem() throws IOException {
    Path x = p("/x");
    fs.close();
    assertFalse(fs.isOpen());
    assertThrows(ClosedFileSystemException.class, () -> Files.readAllBytes(x));
    assertThrows(java.nio.file.FileSystemNotFoundException.class,
        () -> FileSystems.getFileSystem(URI.create("mega65://test/")));
  }

  @Test
  void mountNeedsAConnection() {
    var m65 = (Mega65FileSystem) fs;
    assertThrows(UnsupportedOperationException.class, () -> m65.mountD81(p("/x.d81")));
    assertTrue(m65.target().isEmpty());
  }

  @Test
  void commitWritesOpenChannelsAndKeepsThemUsable() throws IOException {
    var m65 = (Mega65FileSystem) fs;
    byte[] a = random(100_000, 1);
    byte[] b = random(50_000, 2);
    Path f = p("/LOG.BIN");
    try (SeekableByteChannel ch = Files.newByteChannel(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(a));
      assertEquals(0, Files.size(f), "not on the card before commit");
      m65.commit();
      assertArrayEquals(a, Files.readAllBytes(f));
      assertTrue(ch.isOpen());

      ch.write(ByteBuffer.wrap(b));
      assertArrayEquals(a, Files.readAllBytes(f), "later writes wait for the next commit");
      m65.commit();
      byte[] ab = new byte[a.length + b.length];
      System.arraycopy(a, 0, ab, 0, a.length);
      System.arraycopy(b, 0, ab, a.length, b.length);
      assertArrayEquals(ab, Files.readAllBytes(f));
      assertTrue(Files.readAttributes(f, Mega65FileAttributes.class).isContiguous());
    }
    m65.commit(); // nothing open: a plain barrier
    assertEquals(150_000, Files.size(f));
  }

  @Test
  void commitFailureKeepsPreviousContentAndChannelOpen() throws IOException {
    var m65 = (Mega65FileSystem) fs;
    Path f = p("/BIG.BIN");
    Files.write(f, random(1000, 3));
    long tooBig = m65.fileStore().getLargestContiguousSpace() + 1_000_000;
    try (SeekableByteChannel ch = Files.newByteChannel(f, StandardOpenOption.WRITE)) {
      ch.position(tooBig - 1).write(ByteBuffer.wrap(new byte[1]));
      assertThrows(NoContiguousSpaceException.class, m65::commit);
      assertTrue(ch.isOpen());
      assertEquals(1000, Files.size(f));
      ch.truncate(2000);
      m65.commit();
      assertEquals(2000, Files.size(f));
    }
  }

  @Test
  void closingTheFileSystemWritesOpenChannels() throws IOException {
    byte[] a = random(70_000, 4);
    SeekableByteChannel ch = Files.newByteChannel(p("/OPEN.BIN"), StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    ch.write(ByteBuffer.wrap(a));
    fs.close();
    assertFalse(ch.isOpen());
    try (var again = FileSystems.newFileSystem(img.file(), Map.of())) {
      assertArrayEquals(a, Files.readAllBytes(again.getPath("/OPEN.BIN")));
    }
  }

  // ------------------------------------------------------------------ helpers

  private static long secondsBetween(Instant a, Instant b) {
    return Math.abs(ChronoUnit.SECONDS.between(a, b));
  }

  private List<String> names(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.map(x -> x.getFileName().toString()).toList();
    }
  }

  private void assertAllContiguous(Path root) throws IOException {
    try (Stream<Path> s = Files.walk(root)) {
      for (Path f : s.filter(Files::isRegularFile).toList()) {
        assertTrue(Files.readAttributes(f, Mega65FileAttributes.class).isContiguous(), "fragmented: " + f);
      }
    }
  }

  static byte[] random(int n, long seed) {
    byte[] b = new byte[n];
    new Random(seed).nextBytes(b);
    return b;
  }
}
