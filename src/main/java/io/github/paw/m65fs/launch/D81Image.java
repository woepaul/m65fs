package io.github.paw.m65fs.launch;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the directory of a D81 disk image (1581 format: 80 tracks of 40 sectors of 256 bytes).
 * Only the sectors that are needed are read, so it works well on images on the MEGA65's SD card.
 */
public final class D81Image {

  /** Size of a D81 image; images with 3200 error bytes appended are accepted too. */
  public static final long SIZE = 819_200;
  private static final int SECTOR = 256;
  private static final int SECTORS_PER_TRACK = 40;
  private static final int HEADER_TRACK = 40;

  /**
   * A directory entry.
   *
   * @param name the file name as stored (PETSCII, without the $A0 padding)
   * @param type the type byte: bits 0-3 = file type, bit 6 = locked, bit 7 = closed
   * @param track first track of the file
   * @param sector first sector of the file
   * @param blocks size in 254-byte blocks, as listed
   */
  public record Entry(byte[] name, int type, int track, int sector, int blocks) {
    public static final int DEL = 0;
    public static final int SEQ = 1;
    public static final int PRG = 2;
    public static final int USR = 3;
    public static final int REL = 4;
    public static final int CBM = 5;

    public Entry {
      name = name.clone();
    }

    @Override
    public byte[] name() {
      return name.clone();
    }

    public int fileType() {
      return type & 0x0f;
    }

    public boolean isClosed() {
      return (type & 0x80) != 0;
    }

    /** A properly closed PRG file. */
    public boolean isProgram() {
      return fileType() == PRG && isClosed();
    }

    /** The name for display (PETSCII letters as ASCII). */
    public String displayName() {
      return Petscii.toAscii(name);
    }

    @Override
    public String toString() {
      String[] types = {"DEL", "SEQ", "PRG", "USR", "REL", "CBM"};
      String t = fileType() < types.length ? types[fileType()] : "?" + fileType();
      return String.format("%4d \"%s\" %s%s", blocks, displayName(), t, isClosed() ? "" : "*");
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Entry e && Arrays.equals(name, e.name) && type == e.type && track == e.track
          && sector == e.sector && blocks == e.blocks;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(name) * 31 + type;
    }
  }

  private final SeekableByteChannel ch;
  private final byte[] diskName;
  private final List<Entry> entries;

  private D81Image(SeekableByteChannel ch) throws IOException {
    this.ch = ch;
    long size = ch.size();
    if (size != SIZE && size != SIZE + 3200) {
      throw new IOException("not a D81 image (size " + size + ", expected " + SIZE + ")");
    }
    byte[] header = sector(HEADER_TRACK, 0);
    if (header[2] != 'D') {
      throw new IOException("not a D81 image (no 1581 DOS version 'D' in the header)");
    }
    diskName = strip(Arrays.copyOfRange(header, 4, 20));
    entries = readDirectory(header[0] & 0xff, header[1] & 0xff);
  }

  /** Reads the directory from {@code ch}; the channel is used again by {@link #loadAddress}. */
  public static D81Image read(SeekableByteChannel ch) throws IOException {
    return new D81Image(ch);
  }

  /** Reads the directory of a local image file. */
  public static D81Image read(Path image) throws IOException {
    // keep the whole image in memory: it is at most 800 KiB and the channel can be closed
    byte[] bytes = Files.readAllBytes(image);
    return read(new InMemoryChannel(bytes));
  }

  public String diskName() {
    return Petscii.toAscii(diskName);
  }

  /** All used directory entries in order (including scratched-but-listed and non-PRG files). */
  public List<Entry> entries() {
    return entries;
  }

  /** The first closed PRG file: what {@code LOAD"*",8} loads after mounting the disk. */
  public Optional<Entry> firstProgram() {
    return entries.stream().filter(Entry::isProgram).findFirst();
  }

  /** The program with exactly this (PETSCII) name. */
  public Optional<Entry> program(byte[] name) {
    return entries.stream().filter(e -> e.isProgram() && Arrays.equals(e.name, name)).findFirst();
  }

  /** The load address of a file: the first two bytes of its first sector. */
  public int loadAddress(Entry e) throws IOException {
    byte[] s = sector(e.track(), e.sector());
    if (s[0] == 0 && (s[1] & 0xff) < 3) {
      throw new IOException("file \"" + e.displayName() + "\" is shorter than a load address");
    }
    return (s[2] & 0xff) | (s[3] & 0xff) << 8;
  }

  private List<Entry> readDirectory(int track, int sector) throws IOException {
    List<Entry> result = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    while (track != 0) {
      if (!seen.add(track * SECTORS_PER_TRACK + sector)) {
        throw new IOException("directory chain loops at track " + track + " sector " + sector);
      }
      byte[] s = sector(track, sector);
      for (int i = 0; i < 8; i++) {
        int off = i * 32;
        int type = s[off + 2] & 0xff;
        if (type == 0) {
          continue; // empty or scratched slot
        }
        byte[] name = strip(Arrays.copyOfRange(s, off + 5, off + 21));
        int blocks = (s[off + 30] & 0xff) | (s[off + 31] & 0xff) << 8;
        result.add(new Entry(name, type, s[off + 3] & 0xff, s[off + 4] & 0xff, blocks));
      }
      track = s[0] & 0xff;
      sector = s[1] & 0xff;
    }
    return List.copyOf(result);
  }

  private byte[] sector(int track, int sector) throws IOException {
    if (track < 1 || track > 80 || sector < 0 || sector >= SECTORS_PER_TRACK) {
      throw new IOException("invalid track/sector " + track + "/" + sector);
    }
    long pos = ((long) (track - 1) * SECTORS_PER_TRACK + sector) * SECTOR;
    ByteBuffer buf = ByteBuffer.allocate(SECTOR);
    ch.position(pos);
    while (buf.hasRemaining()) {
      if (ch.read(buf) < 0) {
        throw new EOFException("image ends inside track " + track + " sector " + sector);
      }
    }
    return buf.array();
  }

  private static byte[] strip(byte[] padded) {
    int n = padded.length;
    while (n > 0 && (padded[n - 1] & 0xff) == 0xa0) {
      n--;
    }
    return Arrays.copyOf(padded, n);
  }

  /** Minimal read-only channel over a byte array. */
  private static final class InMemoryChannel implements SeekableByteChannel {
    private final byte[] data;
    private long pos;

    InMemoryChannel(byte[] data) {
      this.data = data;
    }

    @Override
    public int read(ByteBuffer dst) {
      if (pos >= data.length) {
        return -1;
      }
      int n = (int) Math.min(dst.remaining(), data.length - pos);
      dst.put(data, (int) pos, n);
      pos += n;
      return n;
    }

    @Override
    public int write(ByteBuffer src) {
      throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public long position() {
      return pos;
    }

    @Override
    public SeekableByteChannel position(long p) {
      pos = p;
      return this;
    }

    @Override
    public long size() {
      return data.length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
      throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }
}
