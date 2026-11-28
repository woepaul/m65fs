package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * PETSCII text (as the MEGA65's {@code TYPE} command shows it) to Unicode, for
 * {@code m65.cat -p/-P}. Two character sets, as on the machine: lower/upper case (after
 * Shift+C=, the one text files are written for: $41-$5A lower case, $C1-$DA and $61-$7A upper
 * case) and upper case/graphics (the power-on default: $41-$5A upper case, $C1-$DA and $61-$7A
 * graphics such as card suits and diagonal lines). Line ends are
 * carriage returns ($0D, also $8D, and CR LF counts once); colour, cursor and other control codes
 * are dropped. Graphics characters map to box drawing, block elements and the Symbols for Legacy
 * Computing block (Unicode 13), following the mapping proposed for PETSCII in Unicode L2/19-025.
 */
final class Petscii {

  private Petscii() {}

  /** $A0-$BF in the lower/upper case set ($E0-$FE repeat them). */
  private static final int[] GRAPHICS_A0 = {
      0x00A0, 0x258C, 0x2584, 0x2594, 0x2581, 0x258F, 0x2592, 0x2595,
      0x1FB8F, 0x1FB99, 0x1FB87, 0x251C, 0x2597, 0x2514, 0x2510, 0x2582,
      0x250C, 0x2534, 0x252C, 0x2524, 0x258E, 0x258D, 0x1FB88, 0x1FB82,
      0x1FB83, 0x2583, 0x2713, 0x2596, 0x259D, 0x2518, 0x2598, 0x259A};

  /** $C0, $DB-$DF in the lower/upper case set ($60, $7B-$7F repeat them). */
  private static final int[] GRAPHICS_C0 = {0x2500};
  private static final int[] GRAPHICS_DB = {0x253C, 0x1FB8C, 0x2502, 0x1FB96, 0x1FB98};

  /** $60-$7F (and $C0-$DF) in the upper case/graphics set. */
  private static final int[] UPPER_60 = {
      0x2500, 0x2660, 0x1FB72, 0x1FB78, 0x1FB77, 0x1FB76, 0x1FB7A, 0x1FB71,
      0x1FB74, 0x256E, 0x2570, 0x256F, 0x1FB7C, 0x2572, 0x2571, 0x1FB7D,
      0x1FB7E, 0x2022, 0x1FB7B, 0x2665, 0x1FB70, 0x256D, 0x2573, 0x25CB,
      0x2663, 0x1FB75, 0x2666, 0x253C, 0x1FB8C, 0x2502, 0x03C0, 0x25E5};

  /** The two character sets. */
  enum Charset {
    /** Lower/upper case (text files). */
    LOWER,
    /** Upper case/graphics (the power-on default; PETSCII art, listings). */
    UPPER
  }

  /** {@link #toUnicode(int, Charset)} in the lower/upper case set. */
  static int toUnicode(int b) {
    return toUnicode(b, Charset.LOWER);
  }

  /** The Unicode code point for {@code b}, '\n' for a line end, or -1 for a control code (dropped). */
  static int toUnicode(int b, Charset set) {
    b &= 0xff;
    if (b == 0x0d || b == 0x8d || b == 0x0a) {
      return '\n';
    }
    if (b < 0x20 || b >= 0x80 && b < 0xa0) {
      return -1;
    }
    if (set == Charset.UPPER) {
      if (b >= 0x41 && b <= 0x5a) {
        return b; // A-Z
      }
      if (b >= 0x60 && b <= 0x7f || b >= 0xc0 && b <= 0xdf) {
        return UPPER_60[b & 0x1f];
      }
      if (b == 0xa9 || b == 0xe9) {
        return 0x25E4; // ◤ (lower/upper case set: a checkerboard triangle)
      }
      if (b == 0xba || b == 0xfa) {
        return 0x1FB7F; // (lower/upper case set: ✓)
      }
      if (b == 0xff) {
        return 0x03C0; // π
      }
    }
    if (b >= 0x41 && b <= 0x5a) {
      return b + 0x20; // a-z
    }
    if (b >= 0x61 && b <= 0x7a || b >= 0xc1 && b <= 0xda) {
      return 'A' + (b & 0x1f) - 1;
    }
    return switch (b) {
      case 0x5c -> 0x00A3; // £
      case 0x5e -> 0x2191; // ↑
      case 0x5f -> 0x2190; // ←
      case 0x60, 0xc0 -> GRAPHICS_C0[0];
      case 0xff -> GRAPHICS_DB[3];
      default -> {
        if (b >= 0x7b && b <= 0x7f) {
          yield GRAPHICS_DB[b - 0x7b];
        }
        if (b >= 0xdb && b <= 0xdf) {
          yield GRAPHICS_DB[b - 0xdb];
        }
        if (b >= 0xa0 && b <= 0xbf) {
          yield GRAPHICS_A0[b - 0xa0];
        }
        if (b >= 0xe0 && b <= 0xfe) {
          yield GRAPHICS_A0[b - 0xe0];
        }
        yield b; // space, digits, punctuation, @ [ ]: as in ASCII
      }
    };
  }

  /** Copies {@code in} to {@code out} converted, in the lower/upper case set. */
  static void copy(InputStream in, PrintStream out) throws IOException {
    copy(in, out, Charset.LOWER);
  }

  /** Copies {@code in} to {@code out} converted. */
  static void copy(InputStream in, PrintStream out, Charset set) throws IOException {
    byte[] buf = new byte[8192];
    StringBuilder sb = new StringBuilder();
    boolean afterCr = false;
    for (int n; (n = in.read(buf)) > 0; ) {
      for (int i = 0; i < n; i++) {
        int b = buf[i] & 0xff;
        boolean lf = b == 0x0a;
        if (lf && afterCr) {
          afterCr = false; // CR LF: one line end
          continue;
        }
        afterCr = b == 0x0d || b == 0x8d;
        int c = toUnicode(b, set);
        if (c >= 0) {
          sb.appendCodePoint(c);
        }
      }
      out.print(sb);
      sb.setLength(0);
    }
    out.flush();
  }
}
