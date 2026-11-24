package io.github.paw.m65fs.etherload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * The 45GS02 code blobs ("ethlets") that ETHLOAD.M65 executes straight out of a received UDP
 * payload. They are m65fs' own (src/main/asm/ethlets/, built by tools/build-ethlets.sh, see
 * docs/ethlets-spec.md): each image is exactly one payload, with its parameter fields at fixed
 * offsets (the constants below, checked by the sources) and the 16-bit sequence number filled
 * in by the host. The MEGA65 answers every ethlet with the unchanged payload.
 */
public enum Ethlet {
  /** Echoes the packet back; used as ping. */
  ECHO("ethlet_echo", 256, 0x0fe),
  /** Copies its data to a 28-bit address, then echoes the packet as acknowledgement. */
  LOAD("ethlet_load", 1280, 0x0fe),
  /**
   * Echoes the packet, then optionally mounts a D81, returns to the saved working directory and
   * resets into MEGA65 or C64 mode (keeping and optionally RUNning a BASIC program), or jumps
   * to an address.
   */
  START("ethlet_start", 1088, 0x43e);

  /** LOAD: destination address bits 20-27 (the DMA job's destination MB). */
  public static final int LOAD_DEST_MB = 0x00e;
  /** LOAD: byte count (LE16, 1..{@value #LOAD_MAX}). */
  public static final int LOAD_COUNT = 0x011;
  /** LOAD: destination address bits 0-15 (LE16). */
  public static final int LOAD_DEST = 0x016;
  /** LOAD: destination address bits 16-19 (the DMA job's destination bank). */
  public static final int LOAD_DEST_BANK = 0x018;
  /** LOAD: the data. */
  public static final int LOAD_DATA = 0x100;
  public static final int LOAD_MAX = 1024;

  /**
   * START: NUL-terminated disk image name, as Hyppo's setname takes it (first byte 0 = none);
   * at a page boundary in the Ethernet buffer, where setname reads it.
   */
  public static final int START_IMAGE = 0x3c0;
  /** START: mode, see {@code START_MODE_*}. */
  public static final int START_MODE = 0x400;
  /** START: flags, see {@link #START_FLAG_RUN}. */
  public static final int START_FLAGS = 0x401;
  /** START: video standard (0 unchanged, 1 PAL, 2 NTSC). */
  public static final int START_VIDEO = 0x402;
  /** START: end address of the BASIC program (LE16, 0 = none), or the jump address. */
  public static final int START_ADDRESS = 0x404;
  public static final int START_IMAGE_MAX = 63;
  public static final int START_MODE_MEGA65 = 0;
  public static final int START_MODE_C64 = 1;
  public static final int START_MODE_JUMP = 2;
  public static final int START_FLAG_RUN = 0x01;

  private final String resourceName;
  private final int packetSize;
  private final int seqOffset;
  private volatile byte[] image;

  Ethlet(String resourceName, int packetSize, int seqOffset) {
    this.resourceName = resourceName;
    this.packetSize = packetSize;
    this.seqOffset = seqOffset;
  }

  /** Size of the UDP payload (the image has exactly this size). */
  public int packetSize() {
    return packetSize;
  }

  /** Offset of the 16-bit sequence number in the payload. */
  public int seqOffset() {
    return seqOffset;
  }

  /** Returns a fresh, mutable copy of the ethlet image. */
  public byte[] newPacket() {
    return load().clone();
  }

  private byte[] load() {
    byte[] i = image;
    if (i == null) {
      i = Blobs.read(resourceName + ".bin");
      if (i.length != packetSize || (i[0] & 0xff) != 0xa9) {
        throw new IllegalStateException(resourceName + ".bin must be " + packetSize
            + " bytes starting with $A9 (LDA #), is " + i.length + " bytes");
      }
      image = i;
    }
    return i;
  }

  /** The ethlet a packet was made from, recognised by size and code, or {@code null}. */
  public static Ethlet of(byte[] packet) {
    for (Ethlet e : values()) {
      // the sizes differ; the first bytes are code (LOAD's host fields start at $0E)
      if (packet.length == e.packetSize && Arrays.equals(packet, 0, 8, e.load(), 0, 8)) {
        return e;
      }
    }
    return null;
  }

  /** Access to the bundled MEGA65 binaries. */
  static final class Blobs {
    private Blobs() {}

    static byte[] read(String file) {
      String path = "/io/github/paw/m65fs/blobs/" + file;
      try (InputStream in = Objects.requireNonNull(Ethlet.class.getResourceAsStream(path), "missing resource " + path)) {
        return in.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
