package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DirectoryParserTest {

  @Test
  void shortNameRendering() {
    assertEquals("README.TXT", DirectoryParser.shortName(raw("README  TXT"), 0));
    assertEquals("readme.TXT", DirectoryParser.shortName(raw("README  TXT"), 0x08));
    assertEquals("README.txt", DirectoryParser.shortName(raw("README  TXT"), 0x10));
    assertEquals("GAMES", DirectoryParser.shortName(raw("GAMES      "), 0));
    byte[] e5 = raw("XFILE   BIN");
    e5[0] = 0x05;
    assertEquals("åFILE.BIN", DirectoryParser.shortName(e5, 0));
  }

  @Test
  void dates() {
    // 2026-11-20 = (46 << 9) | (11 << 5) | 20
    short d = (short) ((46 << 9) | (11 << 5) | 20);
    short t = (short) ((17 << 11) | (2 << 5) | 15); // 17:02:30
    assertEquals(LocalDate.of(2026, 11, 20), DirectoryParser.date(d));
    assertEquals(LocalDateTime.of(2026, 11, 20, 17, 2, 30), DirectoryParser.dateTime(d, t, 0));
    assertEquals(LocalDateTime.of(2026, 11, 20, 17, 2, 31, 500_000_000), DirectoryParser.dateTime(d, t, 150));
    assertNull(DirectoryParser.date((short) 0));
  }

  private static byte[] raw(String elevenChars) {
    return elevenChars.getBytes(StandardCharsets.ISO_8859_1);
  }
}
