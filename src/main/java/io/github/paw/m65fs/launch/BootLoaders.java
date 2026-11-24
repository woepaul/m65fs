package io.github.paw.m65fs.launch;

import io.github.paw.m65fs.etherload.Etherload;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** The small programs that load and start a program from the mounted disk. */
final class BootLoaders {

  /** BASIC token of RUN (BASIC 2 and BASIC 65). */
  private static final int TOKEN_RUN = 0x8a;
  /** BASIC 65 token of MOUNT (escape byte $FE, then $49). */
  private static final byte[] TOKEN_MOUNT = {(byte) 0xfe, 0x49};
  private static final int MAX_NAME = 16;

  private BootLoaders() {}

  /**
   * C64 mode: the bundled {@code c64boot.prg} (source: src/main/asm/c64boot.a), which does
   * {@code LOAD"name",8,1} and {@code RUN}. Its last 17 bytes are the name length and the name.
   */
  static byte[] c64(byte[] name) {
    checkName(name);
    byte[] prg = blob();
    int field = prg.length - 1 - MAX_NAME;
    prg[field] = (byte) name.length;
    Arrays.fill(prg, field + 1, prg.length, (byte) 0xa0);
    System.arraycopy(name, 0, prg, field + 1, name.length);
    return prg;
  }

  /**
   * MEGA65 mode: the BASIC 65 program {@code 0 MOUNT"image":RUN"name"} at $2001.
   *
   * <p>The image is mounted by BASIC (MOUNT also makes it the default disk for RUN).
   */
  static byte[] mega65(byte[] name, String image) {
    checkName(name);
    noQuote(name, "program name");
    byte[] img = Petscii.fromAscii(image);
    noQuote(img, "disk image name");
    var line = new ByteArrayOutputStream();
    line.writeBytes(TOKEN_MOUNT);
    quoted(line, img);
    line.write(':');
    line.write(TOKEN_RUN);
    quoted(line, name);
    line.write(0);                      // end of line

    int start = Etherload.BASIC65_START;
    var out = new ByteArrayOutputStream();
    le16(out, start);                   // load address
    le16(out, start + 4 + line.size()); // link to the next line (the end marker)
    le16(out, 0);                       // line number 0
    out.writeBytes(line.toByteArray());
    le16(out, 0);                       // end of program
    return out.toByteArray();
  }

  private static void quoted(ByteArrayOutputStream out, byte[] s) {
    out.write('"');
    out.writeBytes(s);
    out.write('"');
  }

  private static void noQuote(byte[] s, String what) {
    for (byte b : s) {
      if (b == '"') {
        throw new IllegalArgumentException("a " + what + " with a quote cannot be used in MEGA65 mode");
      }
    }
  }

  private static void checkName(byte[] name) {
    if (name.length == 0 || name.length > MAX_NAME) {
      throw new IllegalArgumentException("program name must have 1.." + MAX_NAME + " characters");
    }
    for (byte b : name) {
      if (b == 0) {
        throw new IllegalArgumentException("program name contains a NUL byte");
      }
    }
  }

  private static void le16(ByteArrayOutputStream out, int v) {
    out.write(v & 0xff);
    out.write(v >> 8 & 0xff);
  }

  private static byte[] blob() {
    try (var in = BootLoaders.class.getResourceAsStream("/io/github/paw/m65fs/blobs/c64boot.prg")) {
      if (in == null) {
        throw new IllegalStateException("missing resource c64boot.prg");
      }
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
