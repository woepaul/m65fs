package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PetsciiTest {

  static String convert(int... bytes) throws IOException {
    byte[] b = new byte[bytes.length];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) bytes[i];
    }
    var out = new ByteArrayOutputStream();
    Petscii.copy(new ByteArrayInputStream(b), new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  static int[] text(String s) {
    return s.chars().toArray();
  }

  @Test
  void caseIsSwappedBackAndLinesEndWithLf() throws IOException {
    // "qUICK tIPS" as stored: PETSCII lower case at $41-$5A, upper case at $C1-$DA
    assertEquals("Quick Tips\n\nThis file\n", convert(0xd1, 'U', 'I', 'C', 'K', ' ', 0xd4, 'I', 'P', 'S', 0x0d, 0x0d,
        0xd4, 'H', 'I', 'S', ' ', 'F', 'I', 'L', 'E', 0x0d));
    assertEquals("Ab\n", convert(0x61, 'B', 0x0d));          // $61-$7A: upper case too
  }

  @Test
  void crLfCountsOnceAndControlCodesAreDropped() throws IOException {
    assertEquals("a\nb\n\n", convert('A', 0x0d, 0x0a, 'B', 0x0a, 0x0d));
    assertEquals("ab\n", convert(0x93, 0x05, 'A', 0x12, 'B', 0x92, 0x8d)); // clear, white, rvs on/off
  }

  @Test
  void symbolsAndGraphics() throws IOException {
    assertEquals("£↑←@[] 0123", convert(0x5c, 0x5e, 0x5f, '@', '[', ']', ' ', '0', '1', '2', '3'));
    assertEquals("─│┼┌┐└┘", convert(0xc0, 0xdd, 0xdb, 0xb0, 0xae, 0xad, 0xbd));
    assertEquals("▌▄", convert(0xe1, 0xe2));                 // $E0-$FE repeat $A0-$BE
  }

  @Test
  void upperCaseGraphicsSet() {
    assertEquals('A', Petscii.toUnicode(0x41, Petscii.Charset.UPPER));
    assertEquals('♠', Petscii.toUnicode(0xc1, Petscii.Charset.UPPER));
    assertEquals('♠', Petscii.toUnicode(0x61, Petscii.Charset.UPPER));
    assertEquals('♥', Petscii.toUnicode(0xd3, Petscii.Charset.UPPER));
    assertEquals('π', Petscii.toUnicode(0xde, Petscii.Charset.UPPER));
    assertEquals('π', Petscii.toUnicode(0xff, Petscii.Charset.UPPER));
    assertEquals('╮', Petscii.toUnicode(0xc9, Petscii.Charset.UPPER));
    assertEquals('◤', Petscii.toUnicode(0xa9, Petscii.Charset.UPPER));
    assertEquals('£', Petscii.toUnicode(0x5c, Petscii.Charset.UPPER));
    assertEquals('▌', Petscii.toUnicode(0xa1, Petscii.Charset.UPPER)); // shared with the other set
  }

  @Test
  void plainAsciiPunctuationStays() throws IOException {
    assertEquals("'core/' - here.", convert(text("'CORE/' - HERE.")));
  }
}
