package io.github.paw.m65fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.paw.m65fs.block.CachingBlockDevice;
import io.github.paw.m65fs.etherload.Etherload;
import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.remotesd.RemoteSdBlockDevice;
import io.github.paw.m65fs.remotesd.RemoteSdSession;
import io.github.paw.m65fs.transport.Mega65Locator;
import io.github.paw.m65fs.transport.SlidingWindow;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.UdpLink;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests against a real MEGA65. Excluded from the default build; run with
 * {@code ./mvnw test -Phardware}.
 *
 * <p>Target: system property {@code m65.target} ({@code fe80::…%if}) or auto-discovery.
 * Tests that change the MEGA65's state additionally require {@code -Dm65.bootstrap=true}.
 */
@Tag("hardware")
class HardwareSmokeTest {

  static Target target() throws IOException {
    String t = System.getProperty("m65.target");
    if (t != null && !t.isBlank()) {
      return Target.parse(t);
    }
    var found = Mega65Locator.discover(Mega65Locator.DEFAULT_TIMEOUT);
    assumeTrue(found.isPresent(), "no MEGA65 beacon heard (remote mode active?)");
    return found.get();
  }

  @Test
  void discover() throws IOException {
    var found = Mega65Locator.discover(Mega65Locator.DEFAULT_TIMEOUT);
    System.out.println("discovery: " + found.map(Target::toString).orElse("no MEGA65 found"));
    assumeTrue(found.isPresent(), "no MEGA65 beacon heard");
  }

  /**
   * Uploads and starts remotesd_eth, then talks to it with a minimal hello + quit (the real
   * session client comes in M2). Quit resets the MEGA65.
   */
  @Test
  void bootstrapHelperThenHelloAndQuit() throws IOException {
    assumeTrue(Boolean.getBoolean("m65.bootstrap"), "set -Dm65.bootstrap=true to upload and start the helper");
    try (var link = UdpLink.open(target())) {
      var el = Etherload.on(link);
      el.bootstrap(Etherload.bundledHelper());
      System.out.println("helper started on " + link.target());

      SlidingWindow w = el.window();
      w.setProtocol(MINIMAL_MREQ);
      w.setCapacity(3);
      boolean[] hello = new boolean[1];
      w.submit(mreq(0xfd), _ -> hello[0] = true, Duration.ofSeconds(3));
      w.awaitAll(Duration.ofSeconds(3));
      assertTrue(hello[0], "hello acknowledged by remotesd_eth");
      System.out.println("hello acknowledged");
      w.submit(mreq(0xff), _ -> {}, Duration.ofSeconds(3));
      w.awaitAll(Duration.ofSeconds(3));
      System.out.println("quit acknowledged; MEGA65 resets");
    }
  }

  /** Read-only: opens the SD card as FAT32, lists the root, reads two files. Quit resets the MEGA65. */
  @Test
  void readSdCardFat32() throws IOException {
    assumeTrue(Boolean.getBoolean("m65.bootstrap"), "set -Dm65.bootstrap=true to upload and start the helper");
    var session = RemoteSdSession.connect(target(), RemoteSdSession.Options.defaults());
    try (var vol = Fat32Volume.open(new CachingBlockDevice(new RemoteSdBlockDevice(session)))) {
      System.out.printf("model id $%02x, write window %d, FAT32 at sector %d, %d sectors/cluster, label '%s'%n",
          session.modelId(), session.writeWindow(), vol.volumeLba(), vol.bootSector().sectorsPerCluster(),
          vol.bootSector().volumeLabel());
      for (DirEntry e : vol.list(vol.root())) {
        System.out.printf("  %s %10d  %-12s  %s%s%n", e.isDirectory() ? "<DIR>" : "     ", e.size(), e.shortName(),
            e.name(), e.isRegularFile() && !vol.isContiguous(e) ? "  [fragmented]" : "");
      }
      var readme = vol.resolve(java.util.List.of("README.md"));
      ByteBuffer buf = ByteBuffer.allocate((int) readme.size());
      vol.read(readme, 0, buf);
      System.out.println("README.md starts with: "
          + new String(buf.array(), 0, Math.min(60, buf.position()), java.nio.charset.StandardCharsets.ISO_8859_1).replace('\n', ' '));
      var rom = vol.resolve(java.util.List.of("MEGA65.ROM"));
      ByteBuffer romBuf = ByteBuffer.allocate((int) rom.size());
      long t0 = System.nanoTime();
      vol.read(rom, 0, romBuf);
      double secs = (System.nanoTime() - t0) / 1e9;
      System.out.printf("read MEGA65.ROM (%d bytes) in %.3f s = %.0f KiB/s, %d retransmissions%n",
          romBuf.position(), secs, romBuf.position() / 1024.0 / secs, session.retransmissions());
    }
  }

  /**
   * Writes on the real card, confined to {@code -Dm65.testdir=NAME} (an existing top-level
   * directory). Everything happens in a fresh {@code run-<time>} subdirectory that is deleted at
   * the end; then both FATs must be byte-identical to a snapshot taken before the first write and
   * the FSInfo free count must be unchanged. Requires {@code -Dm65.write=true}.
   */
  @Test
  void writeInsideTestDirectory() throws IOException {
    assumeTrue(Boolean.getBoolean("m65.write"), "set -Dm65.write=true -Dm65.testdir=NAME to write to the card");
    String testDir = System.getProperty("m65.testdir");
    assumeTrue(testDir != null && !testDir.isBlank(), "set -Dm65.testdir=NAME");
    var session = RemoteSdSession.connect(target(), RemoteSdSession.Options.defaults());
    var cache = new CachingBlockDevice(new RemoteSdBlockDevice(session));
    try (var vol = Fat32Volume.open(cache)) {
      var bs = vol.bootSector();
      long fatLba = vol.volumeLba() + bs.fatStart();
      int fatBytes = Math.toIntExact(bs.sectorsPerFat() * 512);
      System.out.printf("FAT: %d sectors (%d MiB) x 2, %d clusters of %d bytes%n",
          bs.sectorsPerFat(), fatBytes >> 20, bs.clusterCount(), bs.bytesPerCluster());

      var dir = vol.resolve(java.util.List.of(testDir));
      assertTrue(dir.isDirectory(), testDir + " must be a directory");
      for (DirEntry leftover : vol.list(dir)) {
        if (leftover.isDirectory() && leftover.name().startsWith("run-")) {
          System.out.println("removing leftover " + leftover.name() + " from an earlier run");
          deleteRecursively(vol, leftover);
        }
      }
      dir = vol.resolve(java.util.List.of(testDir));

      // ---- snapshot
      long t0 = System.nanoTime();
      byte[] fat1 = readRaw(cache, fatLba, fatBytes);
      byte[] fat2 = readRaw(cache, fatLba + bs.sectorsPerFat(), fatBytes);
      assertTrue(java.util.Arrays.equals(fat1, fat2), "FAT copies differ before the test; not touching this card");
      long freeBefore = vol.freeClusters();
      java.nio.file.Path backup = java.nio.file.Path.of("target", "fat1-backup-" + System.currentTimeMillis() + ".bin");
      java.nio.file.Files.write(backup, fat1);
      System.out.printf("snapshot of FATs taken in %.1f s (FAT #1 saved to %s), %d free clusters%n",
          (System.nanoTime() - t0) / 1e9, backup, freeBefore);

      // ---- work inside run-<time>
      String runName = "run-" + java.time.LocalTime.now().withNano(0).toString().replace(":", "");
      var run = vol.createDirectory(dir, runName);
      var rnd = new java.util.Random(65);
      java.util.Map<String, byte[]> expected = new java.util.LinkedHashMap<>();
      for (int size : new int[] {0, 1, 512, 4096, 100_000, 1_000_000}) {
        byte[] data = new byte[size];
        rnd.nextBytes(data);
        String name = "file " + size + ".bin";
        long w0 = System.nanoTime();
        vol.writeContent(vol.createFile(run, name), data);
        double secs = (System.nanoTime() - w0) / 1e9;
        if (size >= 100_000) {
          System.out.printf("wrote %d bytes in %.2f s = %.0f KiB/s%n", size, secs, size / 1024.0 / secs);
        }
        expected.put(name, data);
      }
      // rewrite: shrink, grow (relocates: the next file follows directly), rename, move
      byte[] small = new byte[300];
      rnd.nextBytes(small);
      vol.writeContent(vol.resolve(java.util.List.of(testDir, runName, "file 100000.bin")), small);
      expected.put("file 100000.bin", small);
      byte[] grown = new byte[50_000];
      rnd.nextBytes(grown);
      vol.writeContent(vol.resolve(java.util.List.of(testDir, runName, "file 4096.bin")), grown);
      expected.put("file 4096.bin", grown);
      var sub = vol.createDirectory(vol.resolve(java.util.List.of(testDir, runName)), "Sub Dir");
      vol.move(vol.resolve(java.util.List.of(testDir, runName, "file 512.bin")), sub, "Moved & Renamed.bin");
      byte[] moved = expected.remove("file 512.bin");
      for (int i = 0; i < 60; i++) { // extends the directory beyond one cluster
        vol.createFile(sub, "many files with long names " + i + ".txt");
      }
      // verify through a fresh cache (forces reads from the card)
      cache.invalidate();
      for (var e : expected.entrySet()) {
        var entry = vol.resolve(java.util.List.of(testDir, runName, e.getKey()));
        assertTrue(vol.isContiguous(entry), "contiguous: " + e.getKey());
        ByteBuffer buf = ByteBuffer.allocate(e.getValue().length);
        if (e.getValue().length > 0) {
          vol.read(entry, 0, buf);
        }
        assertTrue(java.util.Arrays.equals(e.getValue(), buf.array()), "content of " + e.getKey());
      }
      assertEquals(61, vol.list(vol.resolve(java.util.List.of(testDir, runName, "Sub Dir"))).size());
      var movedEntry = vol.resolve(java.util.List.of(testDir, runName, "Sub Dir", "moved & renamed.bin"));
      ByteBuffer movedBuf = ByteBuffer.allocate(moved.length);
      vol.read(movedEntry, 0, movedBuf);
      assertTrue(java.util.Arrays.equals(moved, movedBuf.array()), "content of moved file");
      System.out.println("all files verified; cleaning up");

      // ---- clean up and prove nothing else changed
      var subNow = vol.resolve(java.util.List.of(testDir, runName, "Sub Dir"));
      for (DirEntry e : vol.list(subNow)) {
        vol.delete(e);
      }
      vol.delete(subNow);
      var runNow = vol.resolve(java.util.List.of(testDir, runName));
      for (DirEntry e : vol.list(runNow)) {
        vol.delete(e);
      }
      vol.delete(runNow);
      cache.invalidate();
      byte[] fat1After = readRaw(cache, fatLba, fatBytes);
      byte[] fat2After = readRaw(cache, fatLba + bs.sectorsPerFat(), fatBytes);
      assertTrue(java.util.Arrays.equals(fat1, fat1After), "FAT #1 identical to snapshot");
      assertTrue(java.util.Arrays.equals(fat1, fat2After), "FAT #2 identical to snapshot");
      assertEquals(freeBefore, vol.freeClusters(), "FSInfo free count unchanged");
      System.out.println("FATs byte-identical to snapshot, free count unchanged");
    }
  }

  /** The public NIO API against the real card; writes only inside {@code -Dm65.testdir}. */
  @Test
  void nioFileSystemOnRealCard() throws Exception {
    assumeTrue(Boolean.getBoolean("m65.write"), "set -Dm65.write=true -Dm65.testdir=NAME to write to the card");
    String testDir = System.getProperty("m65.testdir");
    assumeTrue(testDir != null && !testDir.isBlank(), "set -Dm65.testdir=NAME");
    String t = System.getProperty("m65.target");
    java.net.URI uri = java.net.URI.create(t == null || t.isBlank() ? "mega65://auto/" : "mega65://[" + t + "]/");
    try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of())) {
      var root = fs.getPath("/");
      try (var s = java.nio.file.Files.list(root)) {
        System.out.println("/ : " + s.map(p -> p.getFileName().toString()).sorted().toList());
      }
      var store = (io.github.paw.m65fs.nio.Mega65FileStore) java.nio.file.Files.getFileStore(root);
      long free = store.getUnallocatedSpace();
      System.out.printf("store '%s': %d MiB total, %d MiB free%n", store.name(), store.getTotalSpace() >> 20, free >> 20);

      var dir = fs.getPath("/" + testDir);
      var image = dir.resolve("Test Image.d81");
      byte[] d81 = new byte[819_200];
      new java.util.Random(65).nextBytes(d81);
      long t0 = System.nanoTime();
      java.nio.file.Files.write(image, d81);
      long t1 = System.nanoTime();
      byte[] back = java.nio.file.Files.readAllBytes(fs.getPath("/" + testDir.toLowerCase() + "/TEST IMAGE.D81"));
      long t2 = System.nanoTime();
      assertTrue(java.util.Arrays.equals(d81, back), "read back");
      System.out.printf("D81 written in %.2f s, read in %.2f s%n", (t1 - t0) / 1e9, (t2 - t1) / 1e9);
      var attrs = java.nio.file.Files.readAttributes(image, io.github.paw.m65fs.nio.Mega65FileAttributes.class);
      assertTrue(attrs.isContiguous());
      System.out.printf("attributes: short=%s startSector=%d contiguous=%s modified=%s%n",
          attrs.shortName(), attrs.startSector(), attrs.isContiguous(), attrs.lastModifiedTime());

      java.nio.file.Files.createDirectories(dir.resolve("a/b"));
      java.nio.file.Files.copy(image, dir.resolve("a/b/copy.d81"));
      java.nio.file.Files.move(dir.resolve("a/b/copy.d81"), dir.resolve("a/moved.d81"));
      java.nio.file.Files.writeString(dir.resolve("a/hello.txt"), "Hello from Java NIO\n");
      try (var s = java.nio.file.Files.walk(dir)) {
        System.out.println(testDir + " tree: " + s.map(p -> dir.relativize(p).toString()).toList());
      }
      assertEquals("Hello from Java NIO\n", java.nio.file.Files.readString(dir.resolve("A/HELLO.TXT")));
      assertTrue(java.util.Arrays.equals(d81, java.nio.file.Files.readAllBytes(dir.resolve("a/moved.d81"))));

      try (var s = java.nio.file.Files.walk(dir)) {
        for (var p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
          if (!p.equals(dir)) {
            java.nio.file.Files.delete(p);
          }
        }
      }
      try (var s = java.nio.file.Files.list(dir)) {
        assertEquals(0, s.count(), testDir + " is empty again");
      }
      assertEquals(free, store.getUnallocatedSpace(), "free space unchanged");
      System.out.println("cleaned up; free space unchanged");
    }
  }

  /**
   * Writes {@code /M65FS.D81}, mounts it, checks the hypervisor's drive 0 registers, mounts the
   * previously mounted image again and deletes the test image. Only writes if the currently
   * mounted image can be identified as a file in the root directory (so it can be restored).
   */
  @Test
  void mountD81OnRealCard() throws Exception {
    assumeTrue(Boolean.getBoolean("m65.mount"), "set -Dm65.mount=true to write /M65FS.D81 and mount it");
    String t = System.getProperty("m65.target");
    java.net.URI uri = java.net.URI.create(t == null || t.isBlank() ? "mega65://auto/" : "mega65://[" + t + "]/");
    try (var fs = (io.github.paw.m65fs.nio.Mega65FileSystem) java.nio.file.FileSystems.newFileSystem(uri,
        java.util.Map.of())) {
      byte[] before = fs.peek(0xFFD368B, 5);
      long origSector = java.nio.ByteBuffer.wrap(before, 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
          & 0xFFFF_FFFFL;
      System.out.printf("before: $D68B=%02x, image 0 sector=%d%n", before[0], origSector);
      java.nio.file.Path original = null;
      try (var s = java.nio.file.Files.list(fs.getPath("/"))) {
        for (var p : s.filter(java.nio.file.Files::isRegularFile).toList()) {
          var a = java.nio.file.Files.readAttributes(p, io.github.paw.m65fs.nio.Mega65FileAttributes.class);
          if (a.size() > 0 && a.startSector() == origSector) {
            original = fs.getPath("/").resolve(p.toRealPath().getFileName().toString());
          }
        }
      }
      boolean nothingMounted = before[0] == 0;
      System.out.println("currently mounted: " + (nothingMounted ? "nothing" : original));
      assumeTrue(nothingMounted || original != null,
          "mounted image is not a root directory file; cannot restore, not writing");

      var store = fs.fileStore();
      long free = store.getUnallocatedSpace();
      var image = fs.getPath("/M65FS.D81");
      assumeTrue(!java.nio.file.Files.exists(image), "/M65FS.D81 exists already");
      try {
        java.nio.file.Files.write(image, new byte[819_200]);
        long sector = java.nio.file.Files.readAttributes(image, io.github.paw.m65fs.nio.Mega65FileAttributes.class)
            .startSector();
        assertTrue(fs.mountD81(image), "hypervisor accepted the mount");
        byte[] mounted = fs.peek(0xFFD368B, 5);
        long mountedSector = java.nio.ByteBuffer.wrap(mounted, 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
            & 0xFFFF_FFFFL;
        System.out.printf("mounted:  $D68B=%02x, image 0 sector=%d (file starts at %d)%n", mounted[0], mountedSector,
            sector);
        assertEquals(sector, mountedSector, "drive 0 points at the test image");
      } finally {
        if (original != null) {
          boolean restored = fs.mountD81(original);
          byte[] after = fs.peek(0xFFD368B, 5);
          System.out.printf("restored %s: %s, $D68B=%02x%n", original, restored, after[0]);
          assertTrue(restored, "original image mounted again");
          assertTrue(java.util.Arrays.equals(before, after), "drive 0 registers as before");
        }
        java.nio.file.Files.deleteIfExists(image);
        if (original == null) {
          System.out.println("NOTE: drive 0 still points at the deleted test image; press the MEGA65 reset "
              + "button (hypervisor reset detaches it, nothing was mounted before)");
        }
      }
      assertEquals(free, store.getUnallocatedSpace(), "free space unchanged");
      System.out.println("test image deleted; free space unchanged");
    }
  }

  private static void deleteRecursively(Fat32Volume vol, DirEntry entry) throws IOException {
    if (entry.isDirectory()) {
      for (DirEntry child : vol.list(entry)) {
        deleteRecursively(vol, child);
      }
    }
    vol.delete(entry);
  }

  private static byte[] readRaw(CachingBlockDevice dev, long lba, int bytes) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(bytes);
    while (b.hasRemaining()) {
      int n = Math.min(b.remaining(), 128 * 512);
      dev.read(lba + b.position() / 512, b.slice(b.position(), n));
      b.position(b.position() + n);
    }
    return b.array();
  }

  private static byte[] mreq(int opcode) {
    return new byte[] {'m', 'r', 'e', 'q', 0, 0, (byte) opcode};
  }

  /** Just enough of the remotesd_eth framing for hello/quit. */
  private static final WindowProtocol MINIMAL_MREQ = new WindowProtocol() {
    @Override
    public int seqOf(ByteBuffer rx) {
      return rx.remaining() >= 7 ? WindowProtocol.le16(rx, 4) : -1;
    }

    @Override
    public void embedSeq(byte[] tx, int seq) {
      WindowProtocol.putLe16(tx, 4, seq);
    }

    @Override
    public boolean matches(ByteBuffer rx, byte[] tx) {
      return rx.remaining() == 7 && rx.get(0) == 'm' && rx.get(1) == 'r' && rx.get(2) == 's' && rx.get(3) == 'p'
          && rx.get(6) == tx[6];
    }
  };
}
