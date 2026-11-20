package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates FAT32 test images with {@code mkfs.fat} and manipulates them with mtools, so tests
 * check our implementation against independent tools. Tests are skipped if the tools are missing.
 */
public final class FatImages {

  /** Partition start used for MBR images (1 MiB, like real SD cards). */
  public static final long PARTITION_LBA = 2048;

  private FatImages() {}

  /** Handle on an image; {@code mtoolsSpec} is what to pass to {@code mtools -i}. */
  public record Image(Path file, String mtoolsSpec) {

    public void mkdir(String path) throws IOException {
      run("mmd", "-i", mtoolsSpec, "::" + path);
    }

    public void put(Path local, String path) throws IOException {
      run("mcopy", "-i", mtoolsSpec, local.toString(), "::" + path);
    }

    public void put(byte[] data, String path) throws IOException {
      Path tmp = Files.createTempFile("m65fs", ".bin");
      try {
        Files.write(tmp, data);
        put(tmp, path);
      } finally {
        Files.delete(tmp);
      }
    }

    public void delete(String path) throws IOException {
      run("mdel", "-i", mtoolsSpec, "::" + path);
    }

    /** File content as seen by mtools. */
    public byte[] get(String path) throws IOException {
      Path tmp = Files.createTempFile("m65fs", ".out");
      try {
        Files.delete(tmp);
        run("mcopy", "-i", mtoolsSpec, "::" + path, tmp.toString());
        return Files.readAllBytes(tmp);
      } finally {
        Files.deleteIfExists(tmp);
      }
    }

    /** Names in a directory as listed by mtools (long names where present). */
    public java.util.Set<String> list(String dir) throws IOException {
      java.util.Set<String> names = new java.util.TreeSet<>();
      String prefix = dir.endsWith("/") ? dir : dir + "/";
      for (String line : run("mdir", "-i", mtoolsSpec, "-b", "::" + dir).split("\\R")) {
        if (line.isBlank()) {
          continue;
        }
        String full = line.startsWith("::") ? line.substring(2) : line;
        if (full.startsWith(prefix)) {
          full = full.substring(prefix.length());
        }
        if (full.endsWith("/")) {
          full = full.substring(0, full.length() - 1);
        }
        names.add(full);
      }
      return names;
    }

    /**
     * Runs {@code fsck.fat -n} on the volume and throws if it reports problems. For partitioned
     * images the partition is extracted first (fsck.fat has no offset option).
     */
    public String fsck() throws IOException {
      int at = mtoolsSpec.indexOf("@@");
      if (at < 0) {
        return run("fsck.fat", "-n", file.toString());
      }
      long offset = Long.parseLong(mtoolsSpec.substring(at + 2));
      Path part = Files.createTempFile("m65fs-part", ".img");
      try (var in = java.nio.channels.FileChannel.open(file);
          var out = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) {
        long n = in.size() - offset;
        for (long done = 0; done < n; ) {
          done += in.transferTo(offset + done, n - done, out);
        }
        return run("fsck.fat", "-n", part.toString());
      } finally {
        Files.delete(part);
      }
    }
  }

  public static void assumeTools() {
    for (String tool : List.of("mkfs.fat", "mcopy", "mmd", "mdel")) {
      assumeTrue(onPath(tool), tool + " not installed");
    }
  }

  /** A bare FAT32 volume (no partition table) of {@code mib} MiB with 1 sector per cluster. */
  public static Image bareVolume(Path dir, int mib) throws IOException {
    assumeTools();
    Path img = dir.resolve("bare.img");
    run("mkfs.fat", "-F", "32", "-s", "1", "-n", "M65TEST", "-C", img.toString(), Integer.toString(mib * 1024));
    return new Image(img, img.toString());
  }

  /** An MBR-partitioned image with one FAT32 partition (type 0x0C) at {@link #PARTITION_LBA}. */
  public static Image partitioned(Path dir, int mib) throws IOException {
    assumeTools();
    Path img = dir.resolve("sdcard.img");
    long totalSectors = (long) mib * 2048;
    long partSectors = totalSectors - PARTITION_LBA;
    try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
      raf.setLength(totalSectors * 512);
      ByteBuffer mbr = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
      int e = 0x1BE;
      mbr.put(e + 4, (byte) Partition.TYPE_FAT32_LBA);
      mbr.putInt(e + 8, (int) PARTITION_LBA);
      mbr.putInt(e + 12, (int) partSectors);
      mbr.put(510, (byte) 0x55).put(511, (byte) 0xAA);
      raf.write(mbr.array());
    }
    run("mkfs.fat", "-F", "32", "-s", "1", "-n", "M65TEST", "--offset=" + PARTITION_LBA, img.toString(),
        Long.toString(partSectors / 2));
    return new Image(img, img + "@@" + PARTITION_LBA * 512);
  }

  static String run(String... cmd) throws IOException {
    var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().putAll(Map.of("MTOOLS_SKIP_CHECK", "1", "MTOOLS_NO_VFAT", "0"));
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      int rc = p.waitFor();
      if (rc != 0) {
        throw new IOException(String.join(" ", cmd) + " failed (" + rc + "):\n" + out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    return out;
  }

  private static boolean onPath(String tool) {
    List<String> dirs = new ArrayList<>(List.of(System.getenv().getOrDefault("PATH", "").split(":")));
    dirs.add("/usr/sbin");
    dirs.add("/sbin");
    return dirs.stream().anyMatch(d -> Files.isExecutable(Path.of(d, tool)));
  }
}
