package io.github.paw.m65fs.tools;

import io.github.paw.m65fs.nio.Defragmenter;
import io.github.paw.m65fs.nio.Mega65FileSystem;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line tool: reports fragmented files on a MEGA65 SD card (or an SD card image) and,
 * with {@code --fix}, defragments them.
 *
 * <pre>
 * java -cp m65fs.jar io.github.paw.m65fs.tools.Defrag [--fix] [--target fe80::…%if | --image card.img]
 *      [--no-reset] [directory]
 * </pre>
 *
 * Exit status: 0 = no fragmented files (left), 1 = fragmented files remain, 2 = error.
 */
public final class Defrag {

  private Defrag() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the tool; returns the exit status. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    boolean fix = false;
    boolean reset = true;
    String target = null;
    Path image = null;
    String dir = "/";
    try {
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--fix" -> fix = true;
          case "--no-reset" -> reset = false;
          case "--target" -> target = args[++i];
          case "--image" -> image = Path.of(args[++i]);
          case "-h", "--help" -> {
            usage(out);
            return 0;
          }
          default -> {
            if (args[i].startsWith("-")) {
              throw new IllegalArgumentException("unknown option " + args[i]);
            }
            dir = args[i];
          }
        }
      }
      if (target != null && image != null) {
        throw new IllegalArgumentException("--target and --image exclude each other");
      }
    } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
      err.println("defrag: " + (e instanceof ArrayIndexOutOfBoundsException ? "missing option value" : e.getMessage()));
      usage(err);
      return 2;
    }

    Map<String, Object> env = new HashMap<>();
    env.put("quitOnClose", reset);
    env.put("readOnly", !fix);
    try (FileSystem opened = open(target, image, env)) {
      var fs = (Mega65FileSystem) opened;
      out.println("scanning " + fs + (dir.equals("/") ? "" : dir) + " ...");
      Defragmenter.Report report = Defragmenter.scan(fs, fs.getPath(dir));
      out.printf("%d files in %d directories%n", report.filesScanned(), report.directoriesScanned());
      for (var d : report.directories()) {
        out.println("fragmented directory (fine, not changed): " + d.path() + " (" + d.fragments() + " fragments)");
      }
      if (report.isClean()) {
        out.println("no fragmented files");
        return 0;
      }
      out.println(report.files().size() + " fragmented file(s):");
      report.files().forEach(f -> out.println("  " + f));
      if (!fix) {
        out.println("run again with --fix to defragment them");
        return 1;
      }
      out.println("defragmenting ...");
      List<Defragmenter.Result> results = Defragmenter.defragment(fs, report.paths(), r -> out.println("  " + r));
      long left = results.stream()
          .filter(r -> r.outcome() == Defragmenter.Outcome.NO_CONTIGUOUS_SPACE).count();
      long done = results.stream().filter(r -> r.outcome() == Defragmenter.Outcome.DEFRAGMENTED).count();
      out.printf("%d defragmented, %d still fragmented%n", done, left);
      if (left > 0) {
        out.println("largest contiguous free space: " + size(fs.fileStore().getLargestContiguousSpace())
            + " (a file needs a free run of its own size; free up space and run again)");
      }
      return left == 0 ? 0 : 1;
    } catch (IOException | RuntimeException e) {
      err.println("defrag: " + e.getMessage());
      return 2;
    }
  }

  private static FileSystem open(String target, Path image, Map<String, Object> env) throws IOException {
    if (image != null) {
      return Mega65FileSystem.openImage(image, env);
    }
    if (target != null) {
      env.put("target", target);
    }
    return FileSystems.newFileSystem(URI.create("mega65://auto/"), env);
  }

  private static String size(long bytes) {
    return bytes >= 1 << 20 ? (bytes >> 20) + " MiB" : bytes + " bytes";
  }

  private static void usage(PrintStream s) {
    s.println("""
        usage: defrag [--fix] [--target fe80::...%if | --image card.img] [--no-reset] [directory]
          Lists fragmented files on the MEGA65 SD card (found automatically) or an SD card image.
          --fix       defragment the listed files (copies each into one contiguous run;
                      content, size, times and attributes stay the same)
          --target    MEGA65 address instead of discovery
          --image     work on an SD card image file
          --no-reset  do not reset the MEGA65 when done
          directory   only look below this directory (default /)
        exit status: 0 no fragmented files (left), 1 fragmented files remain, 2 error""");
  }
}
