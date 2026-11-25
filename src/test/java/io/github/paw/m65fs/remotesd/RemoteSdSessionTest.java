package io.github.paw.m65fs.remotesd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSdSessionTest {

  static final int SECTORS = 4096; // 2 MiB image

  @TempDir Path tmp;
  Path imageFile;
  byte[] original;
  ImageFileBlockDevice image;

  @BeforeEach
  void createImage() throws IOException {
    original = new byte[SECTORS * 512];
    new Random(11).nextBytes(original);
    imageFile = tmp.resolve("card.img");
    Files.write(imageFile, original);
    image = ImageFileBlockDevice.open(imageFile, false);
  }

  @AfterEach
  void closeImage() throws IOException {
    image.close();
  }

  static RemoteSdSession.Options opts() {
    return RemoteSdSession.Options.defaults().withTimeout(Duration.ofSeconds(5));
  }

  static RemoteSdSession attach(FakeRemoteSd fake) throws IOException {
    return RemoteSdSession.attach(UdpLink.open(fake.target()), opts());
  }

  @Test
  void textGoesToTheHelpersLog() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      s.showText(new Request.Text(Request.Text.WHITE, false, "m65.cp -v a m65:/"));
      s.showText(new Request.Text(Request.Text.GREEN, false, "'a' -> '/a'"));
      s.showText(new Request.Text(Request.Text.RED, true, "'a' -> '/a' failed"));
      assertEquals(List.of("m65.cp -v a m65:/", "'a' -> '/a' failed"), fake.texts());
    }
  }

  @Test
  void timeoutMarksTheSessionFailedAndCloseStillQuits() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      var s = RemoteSdSession.attach(UdpLink.open(fake.target()), opts().withTimeout(Duration.ofMillis(500)));
      fake.silent = true;
      var e = assertThrows(io.github.paw.m65fs.transport.Mega65TimeoutException.class,
          () -> s.writeSectors(10, ByteBuffer.allocate(8 * 512)));
      assertTrue(e.getMessage().contains("MEGA65 at"), e.getMessage());
      fake.silent = false; // it is back, but the helper's batch state is unknown
      var later = assertThrows(IOException.class, () -> s.readSectors(0, ByteBuffer.allocate(512)));
      assertTrue(later.getMessage().contains("failed earlier"), later.getMessage());
      assertTrue(later.getCause() == e);
      s.close();
      assertTrue(fake.quit, "best-effort quit reached the helper");
    }
  }

  @Test
  void closeAfterFailureDoesNotHangOrThrowWhenTheMachineIsGone() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      var s = RemoteSdSession.attach(UdpLink.open(fake.target()), opts().withTimeout(Duration.ofMillis(300)));
      fake.silent = true;
      assertThrows(IOException.class, () -> s.peek(0, 1));
      long t0 = System.nanoTime();
      s.close();
      assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "bounded best-effort quit");
      assertFalse(fake.quit);
    }
  }

  @Test
  void interruptAbortsAWaitImmediately() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      fake.silent = true;
      Thread.currentThread().interrupt();
      long t0 = System.nanoTime();
      try {
        assertThrows(java.io.InterruptedIOException.class, () -> s.readSectors(0, ByteBuffer.allocate(512)));
        assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag preserved");
      } finally {
        Thread.interrupted();
      }
      assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "no spinning until the 5 s timeout");
      fake.silent = false;
    }
  }

  @Test
  void helloDeterminesModelAndWriteWindow() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      fake.modelId = 0x03;
      try (var s = attach(fake)) {
        assertEquals(0x03, s.modelId());
        assertEquals(28, s.writeWindow());
      }
      assertTrue(fake.quit, "close sends quit");
    }
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      fake.modelId = 0x02;
      try (var s = attach(fake)) {
        assertEquals(3, s.writeWindow());
      }
    }
  }

  @Test
  void quitOnCloseCanBeDisabled() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      RemoteSdSession.attach(UdpLink.open(fake.target()), opts().withQuitOnClose(false)).close();
      assertFalse(fake.quit);
    }
  }

  @Test
  void readsSingleSectorsAndLargeRanges() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      assertSectors(s, 0, 1);
      assertSectors(s, 77, 1);
      assertSectors(s, 100, 128);
      assertSectors(s, 1000, 300); // > 128: split into 3 requests
      assertEquals(1 + 1 + 1 + 3, fake.readRequests);
      assertEquals(List.of(), fake.fatalErrors());
    }
  }

  @Test
  void readIntoBufferWithOffset() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      ByteBuffer buf = ByteBuffer.allocate(3 * 512 + 10);
      buf.position(10);
      s.readSectors(5, buf.slice(10, 3 * 512));
      assertArrayEquals(Arrays.copyOfRange(original, 5 * 512, 8 * 512), Arrays.copyOfRange(buf.array(), 10, 10 + 3 * 512));
    }
  }

  @Test
  void writesSingleSectorsAndBatches() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      byte[] expected = original.clone();
      var rnd = new Random(2);
      for (int[] w : new int[][] {{3, 1}, {10, 2}, {200, 128}, {500, 300}, {4095, 1}}) {
        byte[] data = new byte[w[1] * 512];
        rnd.nextBytes(data);
        s.writeSectors(w[0], ByteBuffer.wrap(data));
        System.arraycopy(data, 0, expected, w[0] * 512, data.length);
      }
      assertEquals(List.of(), fake.fatalErrors());
      assertArrayEquals(expected, Files.readAllBytes(imageFile));
      // and read back through the session
      assertSectors(s, 500, 300, expected);
    }
  }

  @Test
  void manyWriteBatchesWrapTheBatchCounter() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      byte[] expected = original.clone();
      byte[] data = new byte[2 * 512];
      for (int i = 0; i < 300; i++) { // > 256 batches
        Arrays.fill(data, (byte) i);
        s.writeSectors(2L * (i % 1000), ByteBuffer.wrap(data));
        System.arraycopy(data, 0, expected, 2 * (i % 1000) * 512, data.length);
      }
      assertEquals(List.of(), fake.fatalErrors());
      assertArrayEquals(expected, Files.readAllBytes(imageFile));
    }
  }

  @Test
  void interleavedReadsAndWritesNeverMix() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      byte[] expected = original.clone();
      var rnd = new Random(4);
      for (int i = 0; i < 50; i++) {
        int lba = rnd.nextInt(SECTORS - 200);
        int n = 1 + rnd.nextInt(150);
        if (rnd.nextBoolean()) {
          byte[] data = new byte[n * 512];
          rnd.nextBytes(data);
          s.writeSectors(lba, ByteBuffer.wrap(data));
          System.arraycopy(data, 0, expected, lba * 512, data.length);
        } else {
          assertSectors(s, lba, n, expected);
        }
        if (i % 7 == 0) {
          s.peek(RemoteSdSession.MODEL_ID_ADDRESS, 1);
        }
      }
      assertEquals(List.of(), fake.fatalErrors());
      assertArrayEquals(expected, Files.readAllBytes(imageFile));
    }
  }

  @Test
  void survivesLossAndDuplicates() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0.03, 0.03, 9); var s = attach(fake)) {
      byte[] expected = original.clone();
      var rnd = new Random(5);
      for (int i = 0; i < 16; i++) {
        int lba = rnd.nextInt(SECTORS - 200);
        int n = 1 + rnd.nextInt(150);
        if (i % 2 == 0) {
          byte[] data = new byte[n * 512];
          rnd.nextBytes(data);
          s.writeSectors(lba, ByteBuffer.wrap(data));
          System.arraycopy(data, 0, expected, lba * 512, data.length);
        } else {
          assertSectors(s, lba, n, expected);
        }
      }
      assertEquals(List.of(), fake.fatalErrors());
      assertArrayEquals(expected, Files.readAllBytes(imageFile));
      assertTrue(s.retransmissions() > 0, "loss should have caused retransmissions");
    }
  }

  @Test
  void peekAndMount() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1); var s = attach(fake)) {
      fake.modelId = 0x21;
      byte[] mem = s.peek(RemoteSdSession.MODEL_ID_ADDRESS - 2, 5);
      assertArrayEquals(new byte[] {0, 0, 0x21, 0, 0}, mem);
      assertTrue(s.mount("GAME.D81"));
      fake.mountStatus = 1;
      assertFalse(s.mount("OTHER.D81"));
      assertEquals(List.of("GAME.D81", "OTHER.D81"), fake.mounted());
    }
  }

  @Test
  void requestValidation() {
    assertThrows(IllegalArgumentException.class, () -> new Request.ReadSectors(0, 129));
    assertThrows(IllegalArgumentException.class, () -> new Request.ReadSectors(1L << 32, 1));
    assertThrows(IllegalArgumentException.class, () -> new Request.Mount("x".repeat(64)));
    assertThrows(IllegalArgumentException.class, () -> new Request.ReadMemory(0, 257));
  }

  @Test
  void closedSessionRejectsOperations() throws IOException {
    try (var fake = new FakeRemoteSd(image, 0, 0, 1)) {
      var s = attach(fake);
      s.close();
      assertThrows(IOException.class, () -> s.readSectors(0, ByteBuffer.allocate(512)));
    }
  }

  private void assertSectors(RemoteSdSession s, long lba, int n) throws IOException {
    assertSectors(s, lba, n, original);
  }

  private static void assertSectors(RemoteSdSession s, long lba, int n, byte[] expected) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(n * 512);
    s.readSectors(lba, buf);
    assertEquals(n * 512, buf.position());
    assertArrayEquals(Arrays.copyOfRange(expected, (int) lba * 512, (int) (lba + n) * 512), buf.array(),
        "sectors " + lba + "+" + n);
  }
}
