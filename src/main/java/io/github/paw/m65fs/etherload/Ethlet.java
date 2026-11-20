package io.github.paw.m65fs.etherload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The 6502 code blobs ("ethlets") the MEGA65 hypervisor executes straight out of a received
 * UDP payload. Each one is loaded from {@code blobs/<name>.bin} together with the Ophis label
 * map {@code blobs/<name>.map}, from which the patchable field offsets are derived.
 *
 * <p>Offsets are relative to the start of the UDP payload (the {@code entry} label).
 */
public enum Ethlet {
  /** Echoes the packet back; used as ping. Sequence number at {@code seq_num}. */
  ECHO("ethlet_echo", 1024),
  /** DMA-copies its {@code data} area to a 28-bit address and echoes the whole packet as ACK. */
  DMA_LOAD("ethlet_dma_load", 1280),
  /**
   * Resets into C64 mode: optionally mounts a D81, keeps a program loaded at $0801, sets the
   * BASIC end-of-program pointer and RUNs it.
   */
  ALL_DONE_BASIC2("ethlet_all_done_basic2", 1024),
  /**
   * Resets into MEGA65 mode: detaches the drives, optionally mounts a D81, keeps a program
   * loaded at $2001, sets the BASIC end-of-program pointer and RUNs it.
   */
  ALL_DONE_BASIC65("ethlet_all_done_basic65", 1024),
  /** Optionally mounts a D81 and jumps to an address (no reset, no ROM initialisation). */
  ALL_DONE_JUMP("ethlet_all_done_jump", 1024);

  private static final String ENTRY_LABEL = "entry";

  private final String resourceName;
  private final int packetSize;
  private volatile Loaded loaded;

  Ethlet(String resourceName, int packetSize) {
    this.resourceName = resourceName;
    this.packetSize = packetSize;
  }

  /** Size of the UDP payload sent for this ethlet (the image is zero-padded to this size). */
  public int packetSize() {
    return packetSize;
  }

  /** Returns a fresh, mutable copy of the ethlet image, zero-padded to {@link #packetSize()}. */
  public byte[] newPacket() {
    return Arrays.copyOf(load().image, packetSize);
  }

  /**
   * Offset of a label from the Ophis map, relative to the start of the payload.
   *
   * @throws IllegalArgumentException if the label is unknown or lies outside the packet
   */
  public int offset(String label) {
    Integer addr = load().labels.get(label);
    if (addr == null) {
      throw new IllegalArgumentException(name() + ": unknown label '" + label + "'");
    }
    int off = addr - load().entry;
    if (off < 0 || off >= packetSize) {
      throw new IllegalArgumentException(name() + ": label '" + label + "' is outside the packet (offset " + off + ")");
    }
    return off;
  }

  private Loaded load() {
    Loaded l = loaded;
    if (l == null) {
      synchronized (this) {
        l = loaded;
        if (l == null) {
          loaded = l = Loaded.of(resourceName, packetSize);
        }
      }
    }
    return l;
  }

  private record Loaded(byte[] image, int entry, Map<String, Integer> labels) {
    static Loaded of(String name, int packetSize) {
      byte[] image = Blobs.read(name + ".bin");
      if (image.length > packetSize) {
        throw new IllegalStateException(name + ".bin is " + image.length + " bytes, larger than packet size " + packetSize);
      }
      Map<String, Integer> labels = parseMap(new String(Blobs.read(name + ".map"), StandardCharsets.US_ASCII));
      Integer entry = labels.get(ENTRY_LABEL);
      if (entry == null) {
        throw new IllegalStateException(name + ".map has no '" + ENTRY_LABEL + "' label");
      }
      return new Loaded(image, entry, Map.copyOf(labels));
    }
  }

  /** Parses Ophis map lines of the form {@code $6840 | entry | file.a65:12}; anonymous labels are skipped. */
  static Map<String, Integer> parseMap(String map) {
    Map<String, Integer> labels = new HashMap<>();
    for (String line : map.split("\\R")) {
      String[] cols = line.split("\\|");
      if (cols.length < 2) {
        continue;
      }
      String addr = cols[0].strip();
      String label = cols[1].strip();
      if (!addr.startsWith("$") || label.equals("*") || label.isEmpty()) {
        continue;
      }
      labels.put(label, Integer.parseInt(addr.substring(1), 16));
    }
    return labels;
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
