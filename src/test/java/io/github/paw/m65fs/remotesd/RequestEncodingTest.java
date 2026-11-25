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
  void cwd() {
    // case 0x13: [7] = mode (0 get, 1 set), for set the path from [8], null-terminated
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x13, 0}, Request.Cwd.get().encode());
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x13, 1, 'A', '/', 'B', 'C', 0},
        Request.Cwd.set(java.util.List.of("A", "BC")).encode());
    assertArrayEquals(new byte[] {'m', 'r', 'e', 'q', 0, 0, 0x13, 1, 0}, Request.Cwd.set(java.util.List.of()).encode());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> Request.Cwd.set(java.util.List.of("A/B")));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> Request.Cwd.set(java.util.List.of("x".repeat(128))), "the helper has room for 127 characters");
  }

  @Test
  void parsesCwdResult() {
    // status, drive, cluster LE
    byte[] r = {'m', 'r', 's', 'p', 9, 0, 0x13, 0, 0, (byte) 0x85, (byte) 0xF9, 0x0D, 0x00};
    assertEquals(new Response.CwdResult(9, 0, 0, 915845), Response.parse(ByteBuffer.wrap(r)).orElseThrow());
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

  @Test
  void encodesTextAsScreenCodes() {
    byte[] p = new Request.Text(Request.Text.GREEN, false, "'a.PRG' -> [x_y]@").encode();
    assertEquals(0x14, p[6]);
    assertEquals(5, p[7]);
    assertEquals(0, p[8]);
    byte[] expected = {'\'', 1, '.', 'P', 'R', 'G', '\'', ' ', '-', '>', ' ', 0x1b, 24, 0x64, 25, 0x1d, 0};
    assertArrayEquals(expected, java.util.Arrays.copyOfRange(p, 9, p.length));
    assertEquals(1, new Request.Text(Request.Text.RED, true, "").encode()[8]);
  }

  @Test
  void aLongTextKeepsItsEnd() {
    String line = "x".repeat(100) + "END";
    byte[] p = new Request.Text(1, false, line).encode();
    assertEquals(9 + 80, p.length);
    assertEquals("...", new String(p, 9, 3, java.nio.charset.StandardCharsets.ISO_8859_1));
    assertEquals("END", new String(p, p.length - 3, 3, java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test
  void aLongCommandKeepsItsName() {
    String line = "cp -rv " + "x".repeat(100) + " m65:/END";
    byte[] p = new Request.Text(1, false, "cp ".length(), line).encode();
    assertEquals(9 + 80, p.length);
    assertEquals("CP ...", new String(p, 9, 6, java.nio.charset.StandardCharsets.ISO_8859_1)
        .replace((char) 3, 'C').replace((char) 16, 'P'));
    assertEquals("END", new String(p, p.length - 3, 3, java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test
  void parsesTextAck() {
    byte[] r = {'m', 'r', 's', 'p', 3, 0, 0x14};
    assertEquals(new Response.TextAck(3), Response.parse(ByteBuffer.wrap(r)).orElseThrow());
  }

  @Test
  void theJavaSideKnowsTheHelpersVersion() throws java.io.IOException {
    String asm = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/asm/helper/helper.a"));
    var m = java.util.regex.Pattern.compile("(?m)^HELPER_VERSION\\s*=\\s*(\\d+)").matcher(asm);
    org.junit.jupiter.api.Assertions.assertTrue(m.find(), "HELPER_VERSION in helper.a");
    assertEquals(Integer.parseInt(m.group(1)), RemoteSdSession.HELPER_VERSION,
        "change both together (and rebuild helper.prg with tools/build-blobs.sh)");
  }

  @Test
  void parsesHelloWithAndWithoutVersion() {
    assertEquals(new Response.HelloAck(1, 0),
        Response.parse(ByteBuffer.wrap(new byte[] {'m', 'r', 's', 'p', 1, 0, (byte) 0xfd})).orElseThrow());
    assertEquals(new Response.HelloAck(1, 7),
        Response.parse(ByteBuffer.wrap(new byte[] {'m', 'r', 's', 'p', 1, 0, (byte) 0xfd, 7})).orElseThrow());
  }
}
