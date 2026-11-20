package io.github.paw.m65fs.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.fat32.FatImages;
import io.github.paw.m65fs.remotesd.FakeRemoteSd;
import io.github.paw.m65fs.remotesd.RemoteSdSession;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file system over the full network stack against the simulated remotesd_eth. */
class NetworkFileSystemTest {

  @Test
  void filesMountAndPeekOverTheNetwork(@TempDir Path tmp) throws IOException {
    var img = FatImages.partitioned(tmp, 64);
    try (var dev = ImageFileBlockDevice.open(img.file(), false);
        var fake = new FakeRemoteSd(dev, 0.01, 0.01, 3)) {
      var session = RemoteSdSession.attach(UdpLink.open(fake.target()), RemoteSdSession.Options.defaults());
      var provider = new Mega65FileSystemProvider();
      try (var fs = Mega65FileSystem.attach(provider, null, "sim", session, new Env(Map.of()))) {
        byte[] d81 = new byte[819_200];
        new Random(1).nextBytes(d81);
        Files.write(fs.getPath("/My Game.d81"), d81);
        Files.createDirectory(fs.getPath("/GAMES"));
        Files.write(fs.getPath("/GAMES/OTHER.D81"), d81);
        assertArrayEquals(d81, Files.readAllBytes(fs.getPath("/my game.D81")));

        assertTrue(fs.mountD81(fs.getPath("/MY GAME.D81")));
        assertEquals(List.of("My Game.d81"), fake.mounted(), "the long name is sent, as Hyppo compares long names");
        fake.mountStatus = 1;
        assertFalse(fs.mountD81(fs.getPath("/My Game.d81")));
        assertThrows(IOException.class, () -> fs.mountD81(fs.getPath("/GAMES/OTHER.D81")), "root directory only");
        assertThrows(java.nio.file.NoSuchFileException.class, () -> fs.mountD81(fs.getPath("/nope.d81")));

        try (var ch = Files.newByteChannel(fs.getPath("/GAMES/SAVE.DAT"), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE)) {
          ch.write(java.nio.ByteBuffer.wrap(d81, 0, 5000));
          fs.commit();
          assertFalse(fake.quit, "commit keeps the session");
          assertArrayEquals(java.util.Arrays.copyOf(d81, 5000), Files.readAllBytes(fs.getPath("/GAMES/SAVE.DAT")));
        }

        assertEquals(0x03, fs.peek(RemoteSdSession.MODEL_ID_ADDRESS, 1)[0]);
        assertTrue(fs.target().isPresent());
      }
      assertTrue(fake.quit, "closing the file system ends the session");
      assertEquals(List.of(), fake.fatalErrors());
    }
    img.fsck();
  }
}
