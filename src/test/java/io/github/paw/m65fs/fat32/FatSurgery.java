package io.github.paw.m65fs.fat32;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: fragments files on purpose (m65fs itself never writes fragmented files), as other
 * tools may leave them behind. Consistent: data is copied, both FATs and FSInfo stay valid.
 */
public final class FatSurgery {

  private FatSurgery() {}

  /**
   * Moves the clusters of {@code file} from logical index {@code keep} to the end into a free run
   * that starts at least {@code gap} clusters after the file's highest cluster, and relinks the
   * chain. Each call adds one fragment.
   */
  public static void split(Fat32Volume vol, DirEntry file, int keep, int gap) throws IOException {
    FatTable fat = vol.fatTable();
    List<Integer> clusters = new ArrayList<>();
    for (Extent e : fat.extents(file.firstCluster())) {
      for (int c = e.first(); c < e.end(); c++) {
        clusters.add(c);
      }
    }
    if (keep < 1 || keep >= clusters.size()) {
      throw new IllegalArgumentException("keep must be 1.." + (clusters.size() - 1));
    }
    List<Integer> tail = clusters.subList(keep, clusters.size());
    int max = clusters.stream().mapToInt(Integer::intValue).max().orElseThrow();
    var run = fat.findFreeRun(tail.size(), max + 1 + gap);
    if (run.length() < tail.size() || run.start() <= max) {
      throw new IllegalStateException("no free run after the file");
    }
    int spc = vol.bootSector().sectorsPerCluster();
    ByteBuffer buf = ByteBuffer.allocate(spc * 512);
    for (int i = 0; i < tail.size(); i++) {
      buf.clear();
      vol.device().read(vol.clusterLba(tail.get(i)), buf);
      vol.device().write(vol.clusterLba(run.start() + i), buf.flip());
    }
    fat.set(clusters.get(keep - 1), run.start());
    fat.linkRun(run.start(), tail.size());
    for (int c : List.copyOf(tail)) {
      fat.set(c, 0);
    }
    vol.flush();
  }
}
