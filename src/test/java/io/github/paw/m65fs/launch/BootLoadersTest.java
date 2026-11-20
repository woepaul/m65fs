package io.github.paw.m65fs.launch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootLoadersTest {

  @Test
  void c64LoaderCarriesTheNameInItsLast17Bytes() {
    byte[] prg = BootLoaders.c64(Petscii.fromAscii("hello"));
    assertEquals(0x0801, (prg[0] & 0xff) | (prg[1] & 0xff) << 8);
    // 10 SYS2061
    assertArrayEquals(new byte[] {(byte) 0x9e, '2', '0', '6', '1', 0}, Arrays.copyOfRange(prg, 6, 12));
    int field = prg.length - 17;
    assertEquals(5, prg[field]);
    assertArrayEquals("HELLO".getBytes(), Arrays.copyOfRange(prg, field + 1, field + 6));
    for (int i = field + 6; i < prg.length; i++) {
      assertEquals((byte) 0xa0, prg[i], "padding");
    }
    // the stub (copied to $0334) must fit below the end of the cassette buffer at $03FC
    int stubLength = prg[0x0f] & 0xff; // operand of LDX #len at $080D
    assertEquals(0xa2, prg[0x0e] & 0xff, "LDX #");
    assertEquals(prg.length - 2 - 0x1a, stubLength, "everything after the copy loop is the stub");
    assertEquals(true, 0x0334 + stubLength <= 0x03fc);
  }

  @Test
  void mega65LoaderIsMountAndRunAsPetcatTokenizesIt(@TempDir Path tmp) throws IOException {
    byte[] expected = CbmTools.basic(tmp, "65", "0 mount\"my disk.d81\":run\"demo 1\"\n");
    assertArrayEquals(expected, BootLoaders.mega65(Petscii.fromAscii("DEMO 1"), "My Disk.d81"));
  }

  @Test
  void rejectsUnusableNames() {
    assertThrows(IllegalArgumentException.class, () -> BootLoaders.c64(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> BootLoaders.c64(new byte[17]));
    assertThrows(IllegalArgumentException.class, () -> BootLoaders.mega65("A\"B".getBytes(), "X.D81"));
    assertThrows(IllegalArgumentException.class, () -> BootLoaders.mega65("AB".getBytes(), "X\".D81"));
  }
}
