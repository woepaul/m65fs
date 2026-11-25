package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Requests understood by the transfer helper (src/main/asm/helper/; the protocol of mega65-tools'
 * remotesd_eth). Every request starts with the 7-byte header
 * {@code "mreq", seq (LE16), opcode}; the sequence number is filled in by the sliding window.
 * All multi-byte fields are little-endian.
 */
public sealed interface Request {

  int HEADER = 7;

  int OP_WRITE_SECTOR = 0x02;
  int OP_READ_SECTORS = 0x04;
  int OP_READ_MEMORY = 0x11;
  int OP_MOUNT = 0x12;
  int OP_CWD = 0x13;
  int OP_TEXT = 0x14;
  int OP_HELLO = 0xFD;
  int OP_RESET_TX = 0xFE;
  int OP_QUIT = 0xFF;

  /** Encodes the request; the sequence number bytes are left 0. */
  byte[] encode();

  /** Must be the first request; locks the helper to this client's address and UDP port. */
  record Hello() implements Request {
    @Override
    public byte[] encode() {
      return header(HEADER, OP_HELLO);
    }
  }

  /** Ends the session; the MEGA65 resets after acknowledging. */
  record Quit() implements Request {
    @Override
    public byte[] encode() {
      return header(HEADER, OP_QUIT);
    }
  }

  /**
   * Read {@code count} (1..128) sectors starting at {@code sector}. Answered by one
   * {@link Response.SectorData} per sector.
   */
  record ReadSectors(long sector, int count) implements Request {
    public static final int MAX_COUNT = 128;
    static final int LENGTH = 13;

    public ReadSectors {
      checkSector(sector);
      if (count < 1 || count > MAX_COUNT) {
        throw new IllegalArgumentException("count must be 1.." + MAX_COUNT + ": " + count);
      }
    }

    @Override
    public byte[] encode() {
      byte[] p = header(LENGTH, OP_READ_SECTORS);
      p[7] = (byte) (count - 1);
      p[8] = 0;
      WindowProtocol.putLe32(p, 9, sector);
      return p;
    }
  }

  /**
   * One 512-byte sector of a write batch.
   *
   * @param batchCounter wrapping 8-bit id of the batch; each batch (and each single write) needs a new one
   * @param countMinusOne batch size minus one: 0 = single synchronous write, 1..127 = batch of 2..128
   * @param slot index of this sector within the batch
   * @param startSector first sector of the batch (the sector written is {@code startSector + slot})
   */
  record WriteSector(int batchCounter, int countMinusOne, int slot, long startSector, byte[] data)
      implements Request {
    static final int LENGTH = 14 + BlockDevice.SECTOR_SIZE;

    public WriteSector {
      checkSector(startSector);
      if (countMinusOne < 0 || countMinusOne > 127 || slot < 0 || slot > countMinusOne) {
        throw new IllegalArgumentException("invalid batch slot " + slot + "/" + countMinusOne);
      }
      Objects.requireNonNull(data);
      if (data.length != BlockDevice.SECTOR_SIZE) {
        throw new IllegalArgumentException("sector data must be 512 bytes");
      }
    }

    @Override
    public byte[] encode() {
      byte[] p = header(LENGTH, OP_WRITE_SECTOR);
      p[7] = (byte) batchCounter;
      p[8] = (byte) countMinusOne;
      p[9] = (byte) slot;
      WindowProtocol.putLe32(p, 10, startSector);
      System.arraycopy(data, 0, p, 14, BlockDevice.SECTOR_SIZE);
      return p;
    }
  }

  /** Read {@code count} (1..256) bytes of MEGA65 memory at the 28-bit {@code address}. */
  record ReadMemory(int address, int count) implements Request {
    static final int LENGTH = 12;

    public ReadMemory {
      if (count < 1 || count > 256) {
        throw new IllegalArgumentException("count must be 1..256: " + count);
      }
      if (address < 0 || address >= 1 << 28) {
        throw new IllegalArgumentException("address outside 28 bit range");
      }
    }

    @Override
    public byte[] encode() {
      byte[] p = header(LENGTH, OP_READ_MEMORY);
      p[7] = (byte) (count - 1);
      WindowProtocol.putLe32(p, 8, address);
      return p;
    }
  }

  /** Mount a D81 image from the SD card root via the hypervisor. */
  record Mount(String filename) implements Request {
    public static final int MAX_NAME = 63;

    public Mount {
      Objects.requireNonNull(filename);
      if (filename.isEmpty() || filename.length() > MAX_NAME || filename.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("invalid image name: " + filename);
      }
    }

    @Override
    public byte[] encode() {
      byte[] name = filename.getBytes(StandardCharsets.ISO_8859_1);
      byte[] p = header(HEADER + name.length + 1, OP_MOUNT);
      System.arraycopy(name, 0, p, HEADER, name.length);
      return p;
    }
  }

  /**
   * Reports the hypervisor's current drive and working directory, after changing it first if
   * {@code path} is present: to the directory reached from the root through the given names (as
   * the hypervisor compares them, ignoring case: the long name if the entry has one, otherwise the
   * 8.3 name; an empty list is the root directory). Answered
   * by {@link Response.CwdResult}. Needs a hypervisor with {@code trap_dos_getcwd}.
   */
  record Cwd(Optional<List<String>> path) implements Request {
    /** Room for the path in the helper, including the terminating NUL. */
    public static final int MAX_PATH = 128;

    public Cwd {
      Objects.requireNonNull(path);
      path.ifPresent(names -> {
        for (String n : names) {
          if (n.isEmpty() || n.indexOf('/') >= 0 || n.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid directory name: " + n);
          }
        }
        if (String.join("/", names).length() + 1 > MAX_PATH) {
          throw new IllegalArgumentException("path longer than " + (MAX_PATH - 1) + " characters");
        }
      });
    }

    /** Only reports the working directory. */
    public static Cwd get() {
      return new Cwd(Optional.empty());
    }

    /** Changes to the directory reached from the root through {@code names}. */
    public static Cwd set(List<String> names) {
      return new Cwd(Optional.of(List.copyOf(names)));
    }

    @Override
    public byte[] encode() {
      if (path.isEmpty()) {
        return header(HEADER + 1, OP_CWD);
      }
      byte[] name = String.join("/", path.get()).getBytes(StandardCharsets.ISO_8859_1);
      byte[] p = header(HEADER + 1 + name.length + 1, OP_CWD);
      p[HEADER] = 1;
      System.arraycopy(name, 0, p, HEADER + 1, name.length);
      return p;
    }
  }

  /**
   * A line for the log on the helper's screen (80 columns): what the host is doing, e.g. the
   * {@code 'src' -> 'dst'} lines of {@code m65.cp -v}. Answered by {@link Response.TextAck}.
   *
   * @param colour MEGA65 colour of the line (0..15, e.g. {@link #GREEN})
   * @param replace replace the last line instead of adding one (e.g. to append a result)
   */
  record Text(int colour, boolean replace, String text) implements Request {
    public static final int COLUMNS = 80;
    public static final int WHITE = 1;
    public static final int RED = 2;
    public static final int GREEN = 5;
    public static final int YELLOW = 7;

    public Text {
      Objects.requireNonNull(text);
      if (colour < 0 || colour > 15) {
        throw new IllegalArgumentException("colour must be 0..15: " + colour);
      }
    }

    @Override
    public byte[] encode() {
      byte[] codes = screenCodes(text);
      byte[] p = header(HEADER + 2 + codes.length, OP_TEXT);
      p[HEADER] = (byte) colour;
      p[HEADER + 1] = (byte) (replace ? 1 : 0);
      System.arraycopy(codes, 0, p, HEADER + 2, codes.length);
      return p;
    }

    /**
     * The text as screen codes of the MEGA65's lower case character set; longer than a row: the
     * end, after {@code "..."} (paths: the end matters most).
     */
    static byte[] screenCodes(String text) {
      int[] cps = text.codePoints().toArray();
      int from = cps.length > COLUMNS ? cps.length - (COLUMNS - 3) : 0;
      byte[] b = new byte[Math.min(cps.length, COLUMNS)];
      int i = 0;
      if (from > 0) {
        b[i++] = '.';
        b[i++] = '.';
        b[i++] = '.';
      }
      for (int k = from; k < cps.length; k++) {
        b[i++] = (byte) screenCode(cps[k]);
      }
      return b;
    }

    static int screenCode(int c) {
      if (c >= 'a' && c <= 'z') {
        return c - 'a' + 1;
      }
      if (c >= 'A' && c <= 'Z' || c >= ' ' && c <= '?') {
        return c;
      }
      return switch (c) {
        case '@' -> 0x00;
        case '[' -> 0x1b;
        case '£' -> 0x1c;
        case ']' -> 0x1d;
        case '^' -> 0x1e;
        case '_' -> 0x64; // bottom bar
        case '|' -> 0x5d; // vertical bar
        case '{' -> '(';
        case '}' -> ')';
        case '\\' -> '/';
        case '~' -> '-';
        case '`' -> '\'';
        default -> '?';
      };
    }
  }

  private static byte[] header(int length, int opcode) {
    byte[] p = new byte[length];
    p[0] = 'm';
    p[1] = 'r';
    p[2] = 'e';
    p[3] = 'q';
    p[6] = (byte) opcode;
    return p;
  }

  private static void checkSector(long sector) {
    if (sector < 0 || sector > 0xFFFF_FFFFL) {
      throw new IllegalArgumentException("sector out of 32 bit range: " + sector);
    }
  }
}
