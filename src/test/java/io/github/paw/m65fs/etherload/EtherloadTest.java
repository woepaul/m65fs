package io.github.paw.m65fs.etherload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.transport.Mega65TimeoutException;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.UdpLink;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EtherloadTest {

  @Test
  void triggerPacketMatchesEtherloadCommon() {
    // trigger_eth_hyperrupt(): 128 zero bytes with magic_string[12] at offset 0x24
    byte[] expected = new byte[128];
    byte[] magic = {0x65, 0x47, 0x53, 0x4b, 0x45, 0x59, 0x43, 0x4f, 0x44, 0x45, 0x00, (byte) 0x80};
    System.arraycopy(magic, 0, expected, 0x24, magic.length);
    assertArrayEquals(expected, Etherload.triggerPacket());
  }

  @Test
  void dmaPacketLayoutMatchesSendMem() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1); var link = UdpLink.open(hv.target())) {
      var el = Etherload.on(link);
      byte[] data = {1, 2, 3};
      byte[] p = el.dmaPacket(0x0123_4567, data, 0, 3);
      var d = EthletProtocols.DmaLoad.INSTANCE;
      assertEquals(1280, p.length);
      assertEquals(0x67, p[d.destAddress] & 0xff);
      assertEquals(0x45, p[d.destAddress + 1] & 0xff);
      assertEquals(0x03, p[d.destBank] & 0xff);
      assertEquals(0x12, p[d.destMb] & 0xff);
      assertEquals(3, WindowProtocol.le16(p, d.byteCount));
      assertEquals(0x0123_4567, d.loadAddress(p));
      assertArrayEquals(data, Arrays.copyOfRange(p, d.data, d.data + 3));
    }
  }

  @Test
  void bootstrapUploadsHelperAndRunsIt() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1); var link = UdpLink.open(hv.target())) {
      var el = Etherload.on(link);
      byte[] prg = Etherload.bundledHelper();
      // bootstrap() would multicast the trigger, which needs an interface; do the rest by hand
      el.ping(Etherload.DEFAULT_TIMEOUT);
      el.writeMemory(0x0801, prg, 2, prg.length - 2, Etherload.DEFAULT_TIMEOUT);
      el.awaitAll(Etherload.DEFAULT_TIMEOUT);
      el.allDoneBasic2(0x0801, true, false, Etherload.DEFAULT_TIMEOUT);

      assertArrayEquals(Arrays.copyOfRange(prg, 2, prg.length),
          Arrays.copyOfRange(hv.memory, 0x0801, 0x0801 + prg.length - 2));
      assertTrue(hv.basicRun);
      assertEquals(0x0801, hv.basicEnd);
    }
  }

  @Test
  void survivesPacketLossAndDuplicates() throws Exception {
    try (var hv = new FakeHypervisor(0.1, 0.1, 42); var link = UdpLink.open(hv.target())) {
      var el = Etherload.on(link);
      byte[] data = new byte[200_000];
      new Random(7).nextBytes(data);
      el.writeMemory(0x10000, data, 0, data.length, Duration.ofSeconds(10));
      el.awaitAll(Duration.ofSeconds(10));
      assertArrayEquals(data, Arrays.copyOfRange(hv.memory, 0x10000, 0x10000 + data.length));
      assertTrue(hv.dropped.get() > 0, "test should actually have dropped packets");
      assertTrue(el.window().retransmissions() > 0);
    }
  }

  @Test
  void writesNeverCrossMegabyteBoundary() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1); var link = UdpLink.open(hv.target())) {
      var el = Etherload.on(link);
      // 0xFFE00 + 1024 would cross into the next MB -> must be split into 512 + 512
      byte[] data = new byte[1024];
      Arrays.fill(data, (byte) 0x5a);
      el.writeMemory(0x0FFE00, data, 0, data.length, Etherload.DEFAULT_TIMEOUT);
      el.awaitAll(Etherload.DEFAULT_TIMEOUT);
      assertEquals(2, hv.dmaPackets.get(), "one packet each side of the 1 MiB boundary");
      assertEquals(0x5a, hv.memory[0x0FFE00]);
      assertEquals(0x5a, hv.memory[0x0FFFFF]);
      assertEquals(0x5a, hv.memory[0x100000]);
      assertEquals(0x5a, hv.memory[0x1001FF]);
      assertEquals(0, hv.memory[0x100200]);
    }
  }

  @Test
  void pingTimesOutWithoutMega65() throws Exception {
    // a port nobody listens on
    var target = new Target((Inet6Address) InetAddress.getByName("::1"), 9, null);
    try (var link = UdpLink.open(target)) {
      var el = Etherload.on(link);
      assertThrows(Mega65TimeoutException.class, () -> el.ping(Duration.ofMillis(300)));
    }
  }
}
