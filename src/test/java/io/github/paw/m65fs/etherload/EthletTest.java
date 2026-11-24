package io.github.paw.m65fs.etherload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The bundled ethlets against docs/ethlets-spec.md. */
class EthletTest {

  @Test
  void imagesHaveTheSpecifiedSizeAndStartWithLdaImmediate() {
    // ETHLOAD.M65 only calls payloads that start with $A9
    assertEquals(256, Ethlet.ECHO.newPacket().length);
    assertEquals(1280, Ethlet.LOAD.newPacket().length);
    assertEquals(1088, Ethlet.START.newPacket().length);
    for (Ethlet e : Ethlet.values()) {
      assertEquals(0xa9, e.newPacket()[0] & 0xff, e.name());
    }
  }

  @Test
  void parameterFieldsAreZeroInTheImages() {
    byte[] load = Ethlet.LOAD.newPacket();
    for (int i = Ethlet.LOAD.seqOffset(); i < load.length; i++) {
      assertEquals(0, load[i], "LOAD offset " + i);
    }
    for (int f : new int[] {Ethlet.LOAD_DEST_MB, Ethlet.LOAD_COUNT, Ethlet.LOAD_COUNT + 1, Ethlet.LOAD_DEST,
        Ethlet.LOAD_DEST + 1, Ethlet.LOAD_DEST_BANK}) {
      assertEquals(0, load[f], "LOAD field " + f);
    }
    byte[] start = Ethlet.START.newPacket();
    for (int i = Ethlet.START_IMAGE; i < start.length; i++) {
      assertEquals(0, start[i], "START offset " + i);
    }
    byte[] echo = Ethlet.ECHO.newPacket();
    assertEquals(0, echo[0xfe]);
    assertEquals(0, echo[0xff]);
  }

  @Test
  void packetsAreRecognised() {
    for (Ethlet e : Ethlet.values()) {
      assertEquals(e, Ethlet.of(e.newPacket()));
    }
    assertNull(Ethlet.of(new byte[128]), "the trigger is no ethlet");
    assertNull(Ethlet.of(new byte[256]));
  }

  @Test
  void newPacketReturnsIndependentCopies() {
    byte[] a = Ethlet.LOAD.newPacket();
    a[1] ^= 0x7f;
    assertTrue(Ethlet.LOAD.newPacket()[1] != a[1]);
  }
}
