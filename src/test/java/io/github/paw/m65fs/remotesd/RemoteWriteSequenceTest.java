package io.github.paw.m65fs.remotesd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.paw.m65fs.block.CachingBlockDevice;
import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hardware write test's sequence on 4 KiB clusters (like the real card), directly, through the
 * cache, and through cache + network stack.
 */
class RemoteWriteSequenceTest {

  @Test
  void hardwareSequenceDirect(@TempDir Path tmp) throws Exception {
    Path img = image(tmp);
    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img, false))) {
      sequence(vol);
    }
  }

  @Test
  void hardwareSequenceThroughCache(@TempDir Path tmp) throws Exception {
    Path img = image(tmp);
    try (var vol = Fat32Volume.open(new CachingBlockDevice(ImageFileBlockDevice.open(img, false)))) {
      sequence(vol);
    }
  }

  @Test
  void hardwareSequenceThroughNetwork(@TempDir Path tmp) throws Exception {
    Path img = image(tmp);
    try (var dev = ImageFileBlockDevice.open(img, false); var fake = new FakeRemoteSd(dev, 0, 0, 1)) {
      var session = RemoteSdSession.attach(UdpLink.open(fake.target()), RemoteSdSession.Options.defaults());
      try (var vol = Fat32Volume.open(new CachingBlockDevice(new RemoteSdBlockDevice(session)))) {
        sequence(vol);
      }
      assertEquals(List.of(), fake.fatalErrors());
    }
  }

  static void sequence(Fat32Volume vol) throws IOException {
    var dir = vol.createDirectory(vol.root(), "M65FSTEST");
    var run = vol.createDirectory(dir, "run-183641");
    var rnd = new Random(65);
    for (int size : new int[] {0, 1, 512, 4096, 100_000, 1_000_000}) {
      byte[] data = new byte[size];
      rnd.nextBytes(data);
      vol.writeContent(vol.createFile(run, "file " + size + ".bin"), data);
    }
    vol.writeContent(vol.resolve(List.of("M65FSTEST", "run-183641", "file 100000.bin")), new byte[300]);
    vol.writeContent(vol.resolve(List.of("M65FSTEST", "run-183641", "file 4096.bin")), new byte[50_000]);
    vol.createDirectory(vol.resolve(List.of("M65FSTEST", "run-183641")), "Sub Dir");
    var moved = vol.move(vol.resolve(List.of("M65FSTEST", "run-183641", "file 512.bin")),
        vol.resolve(List.of("M65FSTEST", "run-183641", "Sub Dir")), "Moved & Renamed.bin");
    assertEquals(512, vol.resolve(List.of("M65FSTEST", "run-183641", "Sub Dir", "moved & renamed.bin")).size());
    assertEquals(moved.firstCluster(), vol.resolve(List.of("M65FSTEST", "run-183641", "Sub Dir", "MOVED & RENAMED.BIN")).firstCluster());
    assertEquals(6, vol.list(vol.resolve(List.of("M65FSTEST", "run-183641"))).size());
  }

  static Path image(Path tmp) throws Exception {
    Path img = tmp.resolve("sd.img");
    var p = new ProcessBuilder("mkfs.fat", "-F", "32", "-s", "8", "-C", img.toString(), "131072")
        .redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) throw new IOException("mkfs failed");
    return img;
  }
}
