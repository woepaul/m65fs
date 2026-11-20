package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.fat32.NoContiguousSpaceException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Finds and fixes fragmented files on a MEGA65 file system, in two steps (the scan reads only
 * directories and the FAT sectors of the files' chains: a few seconds for a full card):
 *
 * <pre>{@code
 * Defragmenter.Report report = Defragmenter.scan(fs);          // read-only
 * report.files().forEach(System.out::println);
 * List<Result> results = Defragmenter.defragment(fs, report.paths(), System.out::println);
 * }</pre>
 *
 * <p>Defragmenting a file copies its clusters into one free contiguous run and then frees the
 * old ones (see {@link Fat32Volume#defragment}); its content, size, timestamps and attributes
 * stay the same. A file needs a free run of its own size. Fragmented directories are reported
 * but left alone: the hypervisor and every FAT implementation follow directory cluster chains.
 */
public final class Defragmenter {

  private Defragmenter() {}

  /** 1234567 -> "1.2 MiB". */
  static String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KiB", "MiB", "GiB"};
    double v = bytes;
    int u = -1;
    while (v >= 1024 && u < units.length - 1) {
      v /= 1024;
      u++;
    }
    return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u]);
  }

  /**
   * A fragmented file or directory.
   *
   * @param size size in bytes (0 for directories)
   * @param fragments number of separate cluster runs
   */
  public record Fragmented(Path path, long size, int fragments) {
    @Override
    public String toString() {
      return String.format("%3d fragments %10s  %s", fragments, humanSize(size), path);
    }
  }

  /**
   * What {@link #scan} found.
   *
   * @param files fragmented files, in directory order
   * @param directories fragmented directories (informational)
   * @param filesScanned number of files looked at
   * @param directoriesScanned number of directories looked at (including the start)
   */
  public record Report(List<Fragmented> files, List<Fragmented> directories, int filesScanned,
      int directoriesScanned) {
    public Report {
      files = List.copyOf(files);
      directories = List.copyOf(directories);
    }

    /** No fragmented files (fragmented directories do not count). */
    public boolean isClean() {
      return files.isEmpty();
    }

    /** The fragmented files' paths, ready for {@link #defragment}. */
    public List<Path> paths() {
      return files.stream().map(Fragmented::path).toList();
    }
  }

  /** What happened to one file. */
  public enum Outcome {
    DEFRAGMENTED,
    /** Already contiguous (e.g. rewritten since the scan). */
    ALREADY_CONTIGUOUS,
    /** No free run large enough; the file is unchanged. */
    NO_CONTIGUOUS_SPACE,
    /** The file no longer exists. */
    NOT_FOUND,
    /** Not a regular file. */
    NOT_A_FILE
  }

  /**
   * The result for one file.
   *
   * @param fragments fragments before
   * @param detail human-readable explanation for outcomes other than {@code DEFRAGMENTED}
   */
  public record Result(Path path, Outcome outcome, int fragments, String detail) {
    @Override
    public String toString() {
      return switch (outcome) {
        case DEFRAGMENTED -> String.format("defragmented (%d fragments -> 1)  %s", fragments, path);
        default -> String.format("%s  %s%s", outcome.name().toLowerCase().replace('_', ' '), path,
            detail.isEmpty() ? "" : " (" + detail + ")");
      };
    }
  }

  /** Scans the whole file system. Read-only. */
  public static Report scan(Mega65FileSystem fs) throws IOException {
    return scan(fs, fs.getPath("/"));
  }

  /** Scans the tree below {@code start} (a directory). Read-only. */
  public static Report scan(Mega65FileSystem fs, Path start) throws IOException {
    fs.ensureOpen();
    Mega65Path root = Mega65Path.cast(start).toAbsolutePath().normalize();
    DirEntry dir = fs.entry(root).orElseThrow(() -> new NoSuchFileException(root.toString()));
    if (!dir.isDirectory()) {
      throw new java.nio.file.NotDirectoryException(root.toString());
    }
    var walk = new Walk(fs.volume());
    walk.directory(root, dir);
    return new Report(walk.files, walk.dirs, walk.fileCount, walk.dirCount);
  }

  private static final class Walk {
    final Fat32Volume vol;
    final List<Fragmented> files = new ArrayList<>();
    final List<Fragmented> dirs = new ArrayList<>();
    int fileCount;
    int dirCount;

    Walk(Fat32Volume vol) {
      this.vol = vol;
    }

    void directory(Mega65Path path, DirEntry dir) throws IOException {
      dirCount++;
      int fragments = vol.extents(dir).size();
      if (fragments > 1) {
        dirs.add(new Fragmented(path, 0, fragments));
      }
      for (DirEntry e : vol.list(dir)) {
        Mega65Path child = Mega65Path.cast(path.resolve(e.name()));
        if (e.isDirectory()) {
          directory(child, e);
        } else if (e.isRegularFile()) {
          fileCount++;
          int n = vol.extents(e).size();
          if (n > 1) {
            files.add(new Fragmented(child, e.size(), n));
          }
        }
      }
    }
  }

  /**
   * Defragments the given files. Smaller files go first (moving them frees holes that can merge
   * into larger free runs); files that found no space are tried once more at the end.
   *
   * @param progress called with each result as it happens (may be {@code null})
   * <p>A file needs a free run of its own size; {@link Mega65FileStore#getLargestContiguousSpace()}
   * tells the largest one (it reads the whole FAT, which takes a while on a large card).
   *
   * @return one result per file, in processing order
   * @throws IOException on device errors (the results so far have been passed to
   *     {@code progress}); a file without enough contiguous space is a result, not an exception
   */
  public static List<Result> defragment(Mega65FileSystem fs, Collection<? extends Path> files,
      Consumer<Result> progress) throws IOException {
    fs.ensureOpen();
    Consumer<Result> report = progress != null ? progress : _ -> {};
    record Item(Mega65Path path, long size) {}
    List<Item> todo = new ArrayList<>();
    for (Path p : files) {
      Mega65Path mp = Mega65Path.cast(Objects.requireNonNull(p)).toAbsolutePath().normalize();
      todo.add(new Item(mp, fs.entry(mp).map(DirEntry::size).orElse(0L)));
    }
    todo.sort(Comparator.comparingLong(Item::size));

    List<Result> results = new ArrayList<>();
    List<Item> retry = new ArrayList<>();
    for (Item item : todo) {
      Result r = defragmentOne(fs, item.path());
      if (r.outcome() == Outcome.NO_CONTIGUOUS_SPACE) {
        retry.add(item);
      } else {
        results.add(r);
        report.accept(r);
      }
    }
    for (Item item : retry) {
      Result r = defragmentOne(fs, item.path());
      results.add(r);
      report.accept(r);
    }
    fs.volume().flush();
    return results;
  }

  private static Result defragmentOne(Mega65FileSystem fs, Mega65Path path) throws IOException {
    var entry = fs.entry(path);
    if (entry.isEmpty()) {
      return new Result(path, Outcome.NOT_FOUND, 0, "");
    }
    DirEntry e = entry.get();
    if (!e.isRegularFile()) {
      return new Result(path, Outcome.NOT_A_FILE, 0, "");
    }
    Fat32Volume vol = fs.volume();
    int fragments = vol.extents(e).size();
    if (fragments <= 1) {
      return new Result(path, Outcome.ALREADY_CONTIGUOUS, fragments, "");
    }
    try {
      vol.defragment(e);
      return new Result(path, Outcome.DEFRAGMENTED, fragments, "");
    } catch (NoContiguousSpaceException ex) {
      return new Result(path, Outcome.NO_CONTIGUOUS_SPACE, fragments, ex.getReason());
    }
  }
}
