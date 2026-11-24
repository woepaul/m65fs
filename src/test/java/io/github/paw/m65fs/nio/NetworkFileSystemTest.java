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

  @Test
  void workingDirectoryIsTheHypervisors(@TempDir Path tmp) throws IOException {
    var img = FatImages.partitioned(tmp, 64);
    try (var dev = ImageFileBlockDevice.open(img.file(), false);
        var fake = new FakeRemoteSd(dev, 0.01, 0.01, 4)) {
      var provider = new Mega65FileSystemProvider();
      int gamesCluster;
      try (var fs = open(provider, fake)) {
        assertTrue(fs.isWorkingDirectoryShared());
        assertEquals(fs.getPath("/"), fs.workingDirectory());
        Files.createDirectories(fs.getPath("/Games/Shooters"));
        Files.write(fs.getPath("/Games/Shooters/GAME.D81"), new byte[819_200]);
        Files.write(fs.getPath("/ROOT.D81"), new byte[819_200]);
        gamesCluster = (int) Files.getAttribute(fs.getPath("/Games"), "mega65:firstCluster");

        // as CHDIR "GAMES",U12 in BASIC (names are matched ignoring case)
        fs.setWorkingDirectory(fs.getPath("games"));
        assertEquals(gamesCluster, fake.cwdCluster);
        assertEquals(fs.getPath("/Games"), fs.workingDirectory(), "spelled as stored");
        assertTrue(Files.isDirectory(fs.getPath("Shooters")));
        assertEquals(fs.getPath("/Games/Shooters/GAME.D81"), fs.getPath("Shooters/GAME.D81").toAbsolutePath());

        fs.setWorkingDirectory(fs.getPath("Shooters"));
        fs.setWorkingDirectory(fs.getPath(".."));
        assertEquals(gamesCluster, fake.cwdCluster);
        assertThrows(java.nio.file.NoSuchFileException.class, () -> fs.setWorkingDirectory(fs.getPath("nope")));
        assertThrows(java.nio.file.NotDirectoryException.class,
            () -> fs.setWorkingDirectory(fs.getPath("Shooters/GAME.D81")));
        assertEquals(gamesCluster, fake.cwdCluster, "unchanged after a failed change");

        // mounting changes the hypervisor to the root directory; the file system changes back
        assertTrue(fs.mountD81(fs.getPath("/ROOT.D81")));
        assertEquals(gamesCluster, fake.cwdCluster);
        fs.setWorkingDirectory(fs.getPath("Shooters"));
      }

      // the next session starts where the last one (or BASIC) left off
      try (var fs = open(provider, fake)) {
        assertEquals(fs.getPath("/Games/Shooters"), fs.workingDirectory());
        fs.setWorkingDirectory(fs.getPath("/"));
        assertEquals(2, fake.cwdCluster, "the root cluster, as after cdroot");
      }
      fake.cwdCluster = 0;
      try (var fs = open(provider, fake)) {
        assertEquals(fs.getPath("/"), fs.workingDirectory(), "cluster 0 is the root directory");
      }
      fake.cwdCluster = 12345;
      try (var fs = open(provider, fake)) {
        assertEquals(fs.getPath("/"), fs.workingDirectory(), "unknown directory (deleted since): the root");
        assertEquals(2, fake.cwdCluster, "and the hypervisor changes there too");
      }
      fake.cwdCluster = gamesCluster;
      fake.cwdDrive = 1;
      try (var fs = open(provider, fake)) {
        assertEquals(fs.getPath("/"), fs.workingDirectory(), "another partition: the root");
        assertFalse(fs.isWorkingDirectoryShared());
      }

      // a hypervisor without trap_dos_getcwd: a working directory of this file system only
      fake.cwdDrive = 0;
      fake.cwdSupported = false;
      try (var fs = open(provider, fake)) {
        assertFalse(fs.isWorkingDirectoryShared());
        assertEquals(fs.getPath("/"), fs.workingDirectory());
        fs.setWorkingDirectory(fs.getPath("/Games"));
        assertEquals(fs.getPath("/Games/Shooters"), fs.getPath("Shooters").toAbsolutePath());
        assertEquals(gamesCluster, fake.cwdCluster, "not sent");
      }
      assertEquals(List.of(), fake.fatalErrors());
    }
    img.fsck();
  }

  private static Mega65FileSystem open(Mega65FileSystemProvider provider, FakeRemoteSd fake) throws IOException {
    var session = RemoteSdSession.attach(UdpLink.open(fake.target()), RemoteSdSession.Options.defaults());
    return Mega65FileSystem.attach(provider, null, "sim", session, new Env(Map.of()));
  }
}
