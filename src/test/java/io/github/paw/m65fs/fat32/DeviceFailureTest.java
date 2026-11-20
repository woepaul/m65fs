package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A device error in the middle of an operation poisons the volume; the medium stays consistent. */
class DeviceFailureTest {

  /** Fails every write after {@code writesLeft} successful ones. */
  static final class FlakyDevice implements BlockDevice {
    final BlockDevice dev;
    volatile int writesLeft = Integer.MAX_VALUE;
    final IOException error = new IOException("cable pulled");

    FlakyDevice(BlockDevice dev) {
      this.dev = dev;
    }

    @Override public long sectorCount() throws IOException { return dev.sectorCount(); }
    @Override public void read(long lba, ByteBuffer buf) throws IOException { dev.read(lba, buf); }
    @Override public void write(long lba, ByteBuffer buf) throws IOException {
      if (writesLeft-- <= 0) {
        throw error;
      }
      dev.write(lba, buf);
    }
    @Override public void flush() throws IOException { dev.flush(); }
    @Override public boolean isReadOnly() { return false; }
    @Override public void close() throws IOException { dev.close(); }
  }

  @Test
  void failureMidWriteRefusesFurtherOperationsAndLeavesAConsistentVolume(@TempDir Path tmp) throws IOException {
    var img = FatImages.bareVolume(tmp, 40);
    byte[] keep = new byte[20_000];
    new Random(1).nextBytes(keep);
    var flaky = new FlakyDevice(ImageFileBlockDevice.open(img.file(), false));
    var vol = Fat32Volume.open(flaky);
    vol.writeContent(vol.createFile(vol.root(), "KEEP.BIN"), keep);
    DirEntry big = vol.createFile(vol.root(), "BIG.BIN");

    flaky.writesLeft = 3; // the data goes out in batches; fail before FAT and directory are updated
    var e = assertThrows(IOException.class, () -> vol.writeContent(big, new byte[2_000_000]));
    assertSame(flaky.error, e, "the original error surfaces, not a follow-up failure");

    flaky.writesLeft = Integer.MAX_VALUE; // even if the device recovers, the cached state is suspect
    var later = assertThrows(IOException.class, () -> vol.list(vol.root()));
    assertTrue(later.getMessage().contains("unusable"), later.getMessage());
    assertSame(flaky.error, later.getCause());
    vol.close(); // no exception, nothing more written

    img.fsck();
    try (var again = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), true))) {
      var entries = again.list(again.root());
      assertEquals(2, entries.size());
      DirEntry k = entries.stream().filter(x -> x.name().equals("KEEP.BIN")).findFirst().orElseThrow();
      ByteBuffer back = ByteBuffer.allocate(keep.length);
      again.read(k, 0, back);
      assertArrayEquals(keep, back.array());
      assertEquals(0, entries.stream().filter(x -> x.name().equals("BIG.BIN")).findFirst().orElseThrow().size(),
          "the interrupted write never became visible");
    }
  }
}
