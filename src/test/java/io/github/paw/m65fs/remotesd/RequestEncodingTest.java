package io.github.paw.m65fs.remotesd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** Byte layouts hand-derived from mega65_ftp.c and remotesd_eth.c. */
class RequestEncodingTest {

  @Test
  void hello() {
    // ethernet_login(): "mreq", seq, 0xfd
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, (byte) 0xfd}, new Request.Hello().encode());
  }

  @Test
  void readSectors() {
    // process_ethernet_read_sectors_job(): [7] = n-1, [8] = 0, [9..12] = sector LE
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x04, 15, 0, 0x78, 0x56, 0x34, 0x12},
        new Request.ReadSectors(0x12345678L, 16).encode());
  }

  @Test
  void writeSector() {
    byte[] data = new byte[512];
    data[0] = 0x11;
    data[511] = 0x22;
    byte[] p = new Request.WriteSector(7, 3, 2, 0xAABBCCDDL, data).encode();
    assertEquals(526, p.length); // remotesd_eth checks udp_length == 534 (8 byte UDP header)
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x02, 7, 3, 2, (byte) 0xDD, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA},
        java.util.Arrays.copyOf(p, 14));
    assertEquals(0x11, p[14]);
    assertEquals(0x22, p[525]);
  }

  @Test
  void readMemory() {
    // peek(): [7] = n-1, [8..11] = address; remotesd_eth checks udp_length == 20
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x11, 0, 0x29, 0x36, (byte) 0xFD, 0x0F},
        new Request.ReadMemory(0xFFD3629, 1).encode());
  }

  @Test
  void mount() {
    // process_ethernet_mount_file_job(): opcode at [6], name from [7], null-terminated
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x12, 'A', '.', 'D', '8', '1', 0},
        new Request.Mount("A.D81").encode());
  }

  @Test
  void parsesSectorData() {
    byte[] r = new byte[525];
    r[0] = 'm'; r[1] = 'r'; r[2] = 's'; r[3] = 'p';
    r[4] = 5;
    r[6] = 0x04;
    r[9] = 0x10;
    r[13] = 0x42;
    var resp = assertInstanceOf(Response.SectorData.class, Response.parse(ByteBuffer.wrap(r)).orElseThrow());
    assertEquals(5, resp.seq());
    assertEquals(0x10, resp.sector());
    assertEquals(0x42, resp.data().get(0));
    assertEquals(512, resp.data().remaining());
  }

  @Test
  void rejectsForeignPackets() {
    assertEquals(java.util.Optional.empty(), Response.parse(ByteBuffer.wrap("mega65".getBytes())));
    assertEquals(java.util.Optional.empty(), Response.parse(ByteBuffer.wrap(new byte[] {'m', 'r', 'e', 'q', 0, 0, (byte) 0xfd})));
  }
}
