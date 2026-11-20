package io.github.paw.m65fs.launch;


/** Conversions between ASCII and PETSCII (unshifted character set) for file names. */
public final class Petscii {

  private Petscii() {}

  /**
   * ASCII to PETSCII as typed on an unshifted keyboard: letters of either case become $41-$5A
   * (shown as capitals), other printable characters are kept.
   *
   * @throws IllegalArgumentException for characters outside printable ASCII
   */
  public static byte[] fromAscii(String s) {
    byte[] b = new byte[s.length()];
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        throw new IllegalArgumentException("not printable ASCII: " + s);
      }
      b[i] = (byte) (c >= 'a' && c <= 'z' ? c - 0x20 : c);
    }
    return b;
  }

  /**
   * PETSCII to readable ASCII: $41-$5A as capitals, $C1-$DA (shifted) as lower case, other
   * non-printable bytes as {@code '?'}.
   */
  public static String toAscii(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length);
    for (byte x : b) {
      int c = x & 0xff;
      if (c >= 0xc1 && c <= 0xda) {
        sb.append((char) (c - 0x60));
      } else if (c >= 0x20 && c <= 0x5d) {
        sb.append((char) c);
      } else {
        sb.append('?');
      }
    }
    return sb.toString();
  }
}
