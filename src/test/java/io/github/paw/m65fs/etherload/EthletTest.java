package io.github.paw.m65fs.etherload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EthletTest {

  @Test
  void parsesOphisMapSkippingAnonymousLabels() {
    var labels = Ethlet.parseMap("""
        $6840 | entry            | ethlet_dma_load.a65:12
        $684C | *                | ethlet_dma_load.a65:29
        $68FE | seq_num          | ethlet_dma_load.a65:149
        """);
    assertEquals(Map.of("entry", 0x6840, "seq_num", 0x68FE), labels);
  }

  @Test
  void packetSizesMatchMega65Ftp() {
    // etherload_common.c: echo/single commands are 1024 bytes, dma_load is 1280
    assertEquals(1024, Ethlet.ECHO.newPacket().length);
    assertEquals(1280, Ethlet.DMA_LOAD.newPacket().length);
    assertEquals(1024, Ethlet.ALL_DONE_BASIC2.newPacket().length);
  }

  @Test
  void singleCommandSeqNumIsAt1022() {
    // singlecmd_get_packet_seq() reads payload[1022..1023]
    assertEquals(1022, Ethlet.ECHO.offset("seq_num"));
    assertEquals(1022, Ethlet.ALL_DONE_BASIC2.offset("seq_num"));
  }

  @Test
  void dmaLoadLayout() {
    var e = Ethlet.DMA_LOAD;
    assertEquals(0xBE, e.offset("seq_num"));
    assertEquals(0xC0, e.offset("data"));
    assertEquals(0x24, e.offset("dest_mb"));
    assertEquals(0x27, e.offset("byte_count"));
    assertEquals(0x2C, e.offset("dest_address"));
    assertEquals(0x2E, e.offset("dest_bank"));
    assertEquals(0x13, e.offset("rom_write_enable"));
  }

  @Test
  void allDoneBasic2PatchFieldsInsidePacket() {
    var e = Ethlet.ALL_DONE_BASIC2;
    assertEquals(0x286, e.offset("data_end_address"));
    assertEquals(0x288, e.offset("do_run"));
    assertEquals(0x204, e.offset("enable_cart_signature"));
  }

  @Test
  void labelsOutsidePacketAreRejected() {
    // data_end_address_copy lives at $0334, not in the packet
    assertThrows(IllegalArgumentException.class, () -> Ethlet.ALL_DONE_BASIC2.offset("data_end_address_copy"));
    assertThrows(IllegalArgumentException.class, () -> Ethlet.ECHO.offset("no_such_label"));
  }

  @Test
  void newPacketReturnsIndependentCopies() {
    byte[] a = Ethlet.DMA_LOAD.newPacket();
    a[0] = 0x7f;
    assertEquals(Ethlet.DMA_LOAD.newPacket()[0] == 0x7f, false);
  }
}
