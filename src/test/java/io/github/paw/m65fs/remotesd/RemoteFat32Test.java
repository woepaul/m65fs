package io.github.paw.m65fs.remotesd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.paw.m65fs.block.CachingBlockDevice;
import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The full read path: Fat32Volume -> cache -> remote block device -> simulated remotesd_eth. */
class RemoteFat32Test {

  @Test
  void readsFat32VolumeOverTheNetworkStack(@TempDir Path tmp) throws Exception {
    Path img = tmp.resolve("sd.img");
    long sectors = 64L * 2048;
    run("mkfs.fat", "-F", "32", "-s", "8", "-n", "MEGA65", "-C", img.toString(), Long.toString(sectors / 2));
    byte[] big = new byte[777_777];
    new Random(3).nextBytes(big);
    Path local = tmp.resolve("big.bin");
    Files.write(local, big);
    run("mmd", "-i", img.toString(), "::/GAMES");
    run("mcopy", "-i", img.toString(), local.toString(), "::/GAMES/Big File.bin");

    try (var dev = ImageFileBlockDevice.open(img, false);
        var fake = new FakeRemoteSd(dev, 0.01, 0.01, 7)) {
      var session = RemoteSdSession.attach(UdpLink.open(fake.target()), RemoteSdSession.Options.defaults());
      try (var vol = Fat32Volume.open(new CachingBlockDevice(new RemoteSdBlockDevice(session)))) {
        assertEquals("MEGA65", vol.bootSector().volumeLabel());
        assertEquals(Set.of("GAMES"), Set.copyOf(vol.list(vol.root()).stream().map(e -> e.name()).toList()));
        var e = vol.resolve(List.of("games", "big file.bin"));
        var out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        long pos = 0;
        int n;
        while ((n = vol.read(e, pos, buf.clear())) > 0) {
          out.write(buf.array(), 0, n);
          pos += n;
        }
        assertArrayEquals(big, out.toByteArray());
      }
      assertEquals(List.of(), fake.fatalErrors());
    }
  }

  private static void run(String... cmd) throws IOException, InterruptedException {
    var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().put("MTOOLS_SKIP_CHECK", "1");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IOException(String.join(" ", cmd) + ": " + out);
    }
  }
}
