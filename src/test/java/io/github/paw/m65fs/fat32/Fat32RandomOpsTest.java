package io.github.paw.m65fs.fat32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.paw.m65fs.block.ImageFileBlockDevice;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random sequences of operations checked against an in-memory model, then verified with mtools,
 * fsck.fat and the no-fragmentation invariant.
 */
class Fat32RandomOpsTest {

  @TempDir Path tmp;

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 3, 4})
  void randomOperationsKeepVolumeConsistent(long seed) throws IOException {
    var img = FatImages.bareVolume(tmp, 40);
    var rnd = new Random(seed);
    Map<String, byte[]> files = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Set<String> dirs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    dirs.add("");

    try (var vol = Fat32Volume.open(ImageFileBlockDevice.open(img.file(), false))) {
      int bpc = vol.bootSector().bytesPerCluster();
      for (int op = 0; op < 400; op++) {
        List<String> dirList = new ArrayList<>(dirs);
        List<String> fileList = new ArrayList<>(files.keySet());
        String dir = dirList.get(rnd.nextInt(dirList.size()));
        switch (rnd.nextInt(10)) {
          case 0, 1, 2 -> { // create + write
            String name = randomName(rnd);
            String path = dir + "/" + name;
            if (files.containsKey(path) || dirs.contains(path)) {
              continue;
            }
            byte[] data = new byte[rnd.nextInt(30 * bpc)];
            rnd.nextBytes(data);
            vol.writeContent(vol.createFile(vol.resolve(split(dir)), name), data);
            files.put(path, data);
          }
          case 3, 4 -> { // rewrite (grow/shrink)
            if (fileList.isEmpty()) {
              continue;
            }
            String path = fileList.get(rnd.nextInt(fileList.size()));
            byte[] data = new byte[rnd.nextInt(60 * bpc)];
            rnd.nextBytes(data);
            vol.writeContent(vol.resolve(split(path)), data);
            files.put(path, data);
          }
          case 5 -> { // delete file
            if (fileList.isEmpty()) {
              continue;
            }
            String path = fileList.get(rnd.nextInt(fileList.size()));
            vol.delete(vol.resolve(split(path)));
            files.remove(path);
          }
          case 6 -> { // mkdir
            String path = dir + "/" + randomName(rnd);
            if (files.containsKey(path) || dirs.contains(path) || path.split("/").length > 4) {
              continue;
            }
            vol.createDirectory(vol.resolve(split(dir)), path.substring(path.lastIndexOf('/') + 1));
            dirs.add(path);
          }
          case 7 -> { // rmdir (only empty ones)
            String d = dirList.get(rnd.nextInt(dirList.size()));
            if (d.isEmpty() || files.keySet().stream().anyMatch(f -> f.toLowerCase().startsWith(d.toLowerCase() + "/"))
                || dirs.stream().anyMatch(x -> x.toLowerCase().startsWith(d.toLowerCase() + "/"))) {
              continue;
            }
            vol.delete(vol.resolve(split(d)));
            dirs.remove(d);
          }
          default -> { // move/rename file
            if (fileList.isEmpty()) {
              continue;
            }
            String from = fileList.get(rnd.nextInt(fileList.size()));
            String name = randomName(rnd);
            String to = dir + "/" + name;
            if (files.containsKey(to) || dirs.contains(to)) {
              continue;
            }
            vol.move(vol.resolve(split(from)), vol.resolve(split(dir)), name);
            files.put(to, files.remove(from));
          }
        }
      }
      Fat32VolumeWriteTest.assertAllFilesContiguous(vol, vol.root(), "");
      for (var e : files.entrySet()) {
        assertArrayEquals(e.getValue(), Fat32VolumeWriteTest.readAll(vol, vol.resolve(split(e.getKey()))), e.getKey());
      }
    }
    System.out.printf("seed %d: %d files (%d KiB), %d directories%n", seed, files.size(),
        files.values().stream().mapToLong(b -> b.length).sum() / 1024, dirs.size() - 1);
    org.junit.jupiter.api.Assertions.assertTrue(files.size() > 20 && dirs.size() > 5, "test exercised too little");
    img.fsck();
    // independent check with mtools
    for (String d : dirs) {
      Set<String> expected = new TreeSet<>();
      String prefix = d + "/";
      files.keySet().stream().filter(f -> parent(f).equalsIgnoreCase(d)).forEach(f -> expected.add(f.substring(prefix.length())));
      dirs.stream().filter(x -> !x.isEmpty() && parent(x).equalsIgnoreCase(d)).forEach(x -> expected.add(x.substring(prefix.length())));
      assertEquals(expected, img.list(d.isEmpty() ? "/" : d), "listing of " + d);
    }
    for (var e : files.entrySet()) {
      assertArrayEquals(e.getValue(), img.get(e.getKey()), "mtools content of " + e.getKey());
    }
  }

  private static String randomName(Random rnd) {
    String[] parts = {"game", "Demo", "MUSIC", "readme", "Grüße", "x", "a long name with spaces", "FILE"};
    String[] exts = {".prg", ".D81", ".txt", "", ".seq", ".tar.gz"};
    return parts[rnd.nextInt(parts.length)] + rnd.nextInt(30) + exts[rnd.nextInt(exts.length)];
  }

  private static List<String> split(String path) {
    return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
  }

  private static String parent(String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }
}
