package io.github.paw.m65fs.tools;

import io.github.paw.m65fs.etherload.Etherload;
import io.github.paw.m65fs.etherload.Etherload.Video;
import io.github.paw.m65fs.launch.MachineMode;
import io.github.paw.m65fs.launch.Mega65Launcher;
import io.github.paw.m65fs.nio.Globs;
import io.github.paw.m65fs.nio.Mega65FileAttributes;
import io.github.paw.m65fs.nio.Mega65FileStore;
import io.github.paw.m65fs.nio.Mega65FileSystem;
import io.github.paw.m65fs.remotesd.RemoteSdSession;
import io.github.paw.m65fs.remotesd.Request;
import io.github.paw.m65fs.transport.Mega65Locator;
import io.github.paw.m65fs.transport.Target;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The m65 command line tools, modelled on the Unix core utilities: {@code m65.ls}, {@code m65.tree},
 * {@code m65.cp}, {@code m65.mv}, {@code m65.rm}, {@code m65.mkdir}, {@code m65.cat},
 * {@code m65.stat}, {@code m65.df}, plus {@code m65.boot}, {@code m65.run}, {@code m65.reset},
 * {@code m65.mount}, {@code m65.peek}, {@code m65.discover} and {@code m65.defrag}.
 *
 * <pre>
 * java -jar m65fs.jar COMMAND [ARGS]      (the bin/m65.* wrappers do this)
 * </pre>
 *
 * Card paths are written {@code m65:/path} where local paths are possible too ({@code m65.cp});
 * commands that only work on the card also take plain paths. Environment: {@code M65_TARGET}
 * (address, default: discovery), {@code M65_IMAGE} (SD card image instead of a MEGA65),
 * {@code M65_RESET=1} (reset the MEGA65 after each command; default: leave the transfer helper
 * running). Exit status 0 = success, 1 = error; {@code defrag}: 1 = fragmented files remain,
 * 2 = error.
 */
public final class M65fs {

  /** Prefix that marks a path on the MEGA65's SD card. */
  public static final String CARD_PREFIX = "m65:";

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
  /** The commands; each has a link {@code m65.COMMAND} (bin/, and made by the install scripts). */
  static final List<String> COMMANDS = List.of("ls", "tree", "cd", "pwd", "cp", "mv", "rm", "mkdir", "cat",
      "stat", "df", "defrag", "dsplit", "boot", "run", "reset", "mount", "peek", "discover");

  private final PrintStream out;
  private final PrintStream err;
  private final InputStream in;
  private boolean stdinUsed;
  private final Map<String, String> env;
  private String prog = "m65";
  private String commandName = "";
  /** The command line for the MEGA65's screen: without "m65." (cp -v a m65:/), to save room. */
  private String commandLine = "";
  private int status;

  private M65fs(Map<String, String> env, InputStream in, PrintStream out, PrintStream err) {
    this.env = env;
    this.in = in;
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    System.exit(run(withInvokedCommand(programName(), args), System.getenv(), System.out, System.err));
  }

  /**
   * Prepends the command when the program was invoked as {@code m65.CMD} (a link to the native
   * binary), so {@code m65.ls -l} runs like {@code m65 ls -l}.
   */
  static String[] withInvokedCommand(String programName, String[] args) {
    if (programName == null) {
      return args;
    }
    String base = Path.of(programName).getFileName().toString();
    if (base.startsWith("m65.") && base.length() > 4) {
      String[] all = new String[args.length + 1];
      all[0] = base.substring(4);
      System.arraycopy(args, 0, all, 1, args.length);
      return all;
    }
    return args;
  }

  /**
   * argv[0] in the native image; {@code null} on the JVM (where it is always "java") and on
   * Windows, where native images can't read it (there the m65.COMMAND.exe launchers pass the
   * command as the first argument instead).
   */
  private static String programName() {
    if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
      return NativeImage.programName();
    }
    return null;
  }

  /** Separate class so the GraalVM SDK (compile-time only) is never touched on the JVM. */
  private static final class NativeImage {
    static String programName() {
      try {
        return org.graalvm.nativeimage.ProcessProperties.getArgumentVectorProgramName();
      } catch (UnsupportedOperationException e) {
        return null; // Windows: "Argument vector support not available"
      }
    }
  }

  /** Runs {@code COMMAND [ARGS]}; returns the exit status. */
  public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
    return run(args, env, System.in, out, err);
  }

  /** As above, with {@code in} as standard input (for {@code m65.cp - DEST}). */
  public static int run(String[] args, Map<String, String> env, InputStream in, PrintStream out, PrintStream err) {
    return new M65fs(env, in, out, err).dispatch(new ArrayList<>(List.of(args)));
  }

  /** A failure reported as {@code m65.cmd: message}. */
  private static final class Failure extends RuntimeException {
    Failure(String message) {
      super(message);
    }
  }

  private int dispatch(List<String> a) {
    if (a.equals(List.of("--version"))) {
      out.print("m65fs " + version() + "\n");
      return 0;
    }
    if (a.equals(List.of("--list-commands"))) {
      // for scripts that create the m65.COMMAND links (tools/install.sh, tools/build-native.sh);
      // "\n" also on Windows, so that no "\r" ends up in a file name
      COMMANDS.forEach(c -> out.print(c + "\n"));
      out.flush();
      return 0;
    }
    if (a.size() >= 2 && a.get(1).equals("__complete") && COMMANDS.contains(a.getFirst())) {
      a.removeFirst(); // m65.ls __complete ... (the script calls the command that was typed)
    }
    if (!a.isEmpty() && a.getFirst().equals("__complete")) {
      return complete(a);
    }
    if (!a.isEmpty() && a.getFirst().equals("completion")) {
      return completion(a);
    }
    if (a.isEmpty() || a.getFirst().equals("help") || a.getFirst().equals("--help")) {
      usage(a.isEmpty() ? err : out);
      return a.isEmpty() ? 1 : 0;
    }
    String cmd = a.removeFirst();
    prog = "m65." + cmd;
    commandName = cmd;
    commandLine = cmd + a.stream().map(x -> x.isEmpty() || x.contains(" ") ? " '" + x + "'" : " " + x)
        .collect(java.util.stream.Collectors.joining());
    if (!COMMANDS.contains(cmd)) {
      err.println("m65: unknown command '" + cmd + "' (m65 help lists the commands)");
      return 1;
    }
    if (a.contains("--help")) {
      out.println("usage: " + usageOf(cmd));
      return 0;
    }
    try {
      int s = switch (cmd) {
        case "ls" -> ls(a);
        case "tree" -> tree(a);
        case "cd" -> cd(a);
        case "pwd" -> pwd(a);
        case "cp" -> cp(a);
        case "mv" -> mv(a);
        case "rm" -> rm(a);
        case "mkdir" -> mkdir(a);
        case "dsplit" -> dsplit(a);
        case "cat" -> cat(a);
        case "stat" -> stat(a);
        case "df" -> df(a);
        case "defrag" -> defrag(a);
        case "boot" -> boot(a);
        case "run" -> runPrg(a);
        case "reset" -> reset(a);
        case "mount" -> mount(a);
        case "peek" -> peek(a);
        default -> discover(a);
      };
      return Math.max(s, status);
    } catch (Failure e) {
      err.println(prog + ": " + e.getMessage());
      return 1;
    } catch (IOException | RuntimeException e) {
      err.println(prog + ": " + describe(e));
      return 1;
    }
  }

  private String describe(Exception e) {
    return switch (e) {
      case NoSuchFileException n -> "'" + n.getFile() + "': No such file or directory";
      case FileAlreadyExistsException f -> "'" + f.getFile() + "': File exists";
      case DirectoryNotEmptyException d -> "'" + d.getFile() + "': Directory not empty";
      case NotDirectoryException d -> "'" + d.getFile() + "': Not a directory";
      default -> e.getMessage() != null ? e.getMessage() : e.toString();
    };
  }

  /** Reports a per-operand error and continues (exit status 1 at the end), like coreutils. */
  private void warn(String message) {
    err.println(prog + ": " + message);
    status = 1;
  }

  // ------------------------------------------------------------------ file commands

  private int ls(List<String> a) throws IOException {
    Set<Character> f = flags(a, "lRd");
    List<String> paths = a.isEmpty() ? List.of(".") : a;
    try (var fs = openCard(true)) {
      paths = glob(fs, paths, true);
      List<Path> files = new ArrayList<>();
      List<Path> dirs = new ArrayList<>();
      for (String s : paths) {
        Path p = fs.getPath(cardPath(s));
        if (!Files.exists(p)) {
          warn("cannot access '" + s + "': No such file or directory");
        } else if (Files.isDirectory(p) && !f.contains('d')) {
          dirs.add(p);
        } else {
          files.add(p);
        }
      }
      for (Path p : files) {
        entry(p, String.valueOf(p), f.contains('l'));
      }
      boolean headers = f.contains('R') || paths.size() > 1;
      boolean first = files.isEmpty();
      for (Path d : dirs) {
        first = listDir(d, f.contains('l'), f.contains('R'), headers, first);
      }
      return 0;
    }
  }

  private boolean listDir(Path dir, boolean longFormat, boolean recursive, boolean header, boolean first)
      throws IOException {
    if (header) {
      if (!first) {
        out.println();
      }
      out.println(dir + ":");
    }
    List<Path> children;
    try (Stream<Path> s = Files.list(dir)) {
      children = s.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
    }
    for (Path c : children) {
      entry(c, c.getFileName().toString(), longFormat);
    }
    if (recursive) {
      for (Path c : children) {
        if (Files.isDirectory(c)) {
          listDir(c, longFormat, true, true, false);
        }
      }
    }
    return false;
  }

  private void entry(Path p, String name, boolean longFormat) throws IOException {
    if (!longFormat) {
      out.println(name);
      return;
    }
    var at = Files.readAttributes(p, Mega65FileAttributes.class);
    String when = TIME.format(at.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
    out.printf("%s %10d  %s  %s%s%n", at.isDirectory() ? "d" : "-", at.isDirectory() ? 0 : at.size(), when, name,
        at.isContiguous() || at.isDirectory() ? "" : "   [fragmented]");
  }

  /**
   * {@code cd [DIR]}: changes the working directory (the root without DIR). The MEGA65 keeps it,
   * so it is the same as {@code CHDIR "DIR",U12} in BASIC and holds for the following commands.
   */
  private int cd(List<String> a) throws IOException {
    String dir = a.isEmpty() ? "/" : a.removeFirst();
    noOperands(a);
    try (var fs = openCard(true)) {
      if (!fs.isWorkingDirectoryShared()) {
        throw new Failure(fs.target().isEmpty()
            ? "an image file has no working directory (use absolute paths)"
            : "the MEGA65's hypervisor does not keep a working directory (needs MEGA65 core development "
                + "build 179 or later: https://builder.mega65.org/job/mega65-core/job/development/179/)");
      }
      fs.setWorkingDirectory(fs.getPath(cardPath(dir)));
      return 0;
    }
  }

  /** {@code pwd}: prints the working directory that relative card paths start from. */
  private int pwd(List<String> a) throws IOException {
    noOperands(a);
    try (var fs = openCard(true)) {
      out.println(fs.workingDirectory());
      return 0;
    }
  }

  /**
   * A file or directory for {@code tree}, with the totals of everything read below it;
   * {@code complete}: the whole subtree was read (not cut off by {@code -L}), so the totals are
   * those of the directory.
   */
  private record Node(String name, boolean dir, long size, String date, boolean fragmented, List<Node> children,
      long totalSize, int files, int dirs, boolean complete) {}

  /**
   * {@code tree [-d] [-L LEVEL] [DIR]}: like tree(1), with sizes, dates and directory totals.
   * With {@code -L} only the levels shown are read (each directory costs a round trip to the
   * card), so directories whose content is cut off show no totals and the summary counts what
   * was read.
   */
  private int tree(List<String> a) throws IOException {
    String level = option(a, "-L");
    int maxDepth = level == null ? Integer.MAX_VALUE : Integer.parseInt(level);
    if (maxDepth < 1) {
      throw new Failure("-L needs a level of 1 or more");
    }
    Set<Character> f = flags(a, "d");
    boolean dirsOnly = f.contains('d');
    String dir = a.isEmpty() ? "." : a.removeFirst();
    noOperands(a);
    try (var fs = openCard(true)) {
      Path root = fs.getPath(cardPath(dir));
      if (!Files.isDirectory(root)) {
        throw new Failure("'" + dir + "': Not a directory");
      }
      Node top = node(root, "", 0, maxDepth);
      List<String[]> rows = new ArrayList<>();
      treeRows(top, "", 1, maxDepth, dirsOnly, rows);
      int width = Math.min(72, rows.stream().mapToInt(r -> r[0].length()).max().orElse(0)) + 2;
      out.println(root.toRealPath());
      for (String[] r : rows) {
        out.println(r[1].isEmpty() ? r[0] : String.format("%-" + width + "s%s", r[0], r[1]));
      }
      out.println();
      String free = "";
      if (root.toAbsolutePath().normalize().getNameCount() == 0) {
        Mega65FileStore store = fs.fileStore();
        free = String.format(", volume '%s' %s free of %s", store.name(), humanSize(store.getUnallocatedSpace()),
            humanSize(store.getTotalSpace()));
      }
      out.printf("%d director%s, %d file%s, %s%s%s%n", top.dirs(), top.dirs() == 1 ? "y" : "ies", top.files(),
          top.files() == 1 ? "" : "s", humanSize(top.totalSize()),
          top.complete() ? "" : " (to level " + maxDepth + ")", free);
      return 0;
    }
  }

  /** Reads {@code p} (at {@code depth}, the top is 0) and, above {@code maxDepth}, its content. */
  private Node node(Path p, String name, int depth, int maxDepth) throws IOException {
    var at = Files.readAttributes(p, Mega65FileAttributes.class);
    String date = TIME.format(at.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
    if (!at.isDirectory()) {
      return new Node(name, false, at.size(), date, !at.isContiguous(), List.of(), at.size(), 1, 0, true);
    }
    if (depth >= maxDepth) {
      return new Node(name, true, 0, date, false, List.of(), 0, 0, 0, false);
    }
    List<Path> list;
    try (Stream<Path> s = Files.list(p)) {
      list = s.toList();
    }
    List<Node> children = new ArrayList<>();
    long total = 0;
    int files = 0;
    int dirs = 0;
    boolean complete = true;
    for (Path c : list) {
      Node n = node(c, c.getFileName().toString(), depth + 1, maxDepth);
      complete &= n.complete();
      children.add(n);
      total += n.totalSize();
      files += n.files();
      dirs += n.dirs() + (n.dir() ? 1 : 0);
    }
    children.sort(Comparator.comparing((Node n) -> !n.dir()).thenComparing(n -> n.name().toLowerCase(Locale.ROOT)));
    return new Node(name, true, 0, date, false, children, total, files, dirs, complete);
  }

  private static void treeRows(Node dir, String prefix, int depth, int maxDepth, boolean dirsOnly,
      List<String[]> rows) {
    List<Node> shown = dir.children().stream().filter(n -> n.dir() || !dirsOnly).toList();
    for (int i = 0; i < shown.size(); i++) {
      Node n = shown.get(i);
      boolean last = i == shown.size() - 1;
      String label = prefix + (last ? "└── " : "├── ") + n.name() + (n.dir() ? "/" : "");
      String info = n.dir() && !n.complete() ? ""
          : n.dir() ? humanSize(n.totalSize()) + " in " + n.files() + " file" + (n.files() == 1 ? "" : "s")
          : humanSize(n.size()) + "   " + n.date() + (n.fragmented() ? "   [fragmented]" : "");
      rows.add(new String[] {label, info});
      if (n.dir() && depth < maxDepth) {
        treeRows(n, prefix + (last ? "    " : "│   "), depth + 1, maxDepth, dirsOnly, rows);
      }
    }
  }

  /** 1234567 -> "1.2 MiB". */
  static String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KiB", "MiB", "GiB", "TiB"};
    double v = bytes;
    int u = -1;
    while (v >= 1024 && u < units.length - 1) {
      v /= 1024;
      u++;
    }
    return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
  }

  /** {@code cp [-rRnfpv] SOURCE... DEST} with GNU cp semantics; card paths are m65:/... */
  private int cp(List<String> a) throws IOException {
    Set<Character> f = flags(a, "rRnfpv");
    boolean recursive = f.contains('r') || f.contains('R');
    if (a.size() < 2) {
      throw new Failure(a.isEmpty() ? "missing file operand" : "missing destination file operand after '"
          + a.getFirst() + "'");
    }
    boolean needCard = a.stream().anyMatch(M65fs::isCard);
    boolean writeCard = isCard(a.getLast());
    try (var fs = needCard ? openCard(!writeCard) : null) {
      Path dst = path(fs, a.getLast());
      boolean dstIsDir = Files.isDirectory(dst);
      List<String> sources = glob(fs, a.subList(0, a.size() - 1), false);
      if (sources.size() > 1 && !dstIsDir) {
        throw new Failure("target '" + a.getLast() + "' is not a directory");
      }
      if (!dstIsDir && a.getLast().endsWith("/") && !Files.exists(dst)) {
        throw new Failure("cannot create regular file '" + a.getLast() + "': Not a directory");
      }
      for (String s : sources) {
        if (s.equals("-")) {
          // standard input: zcat hdd.img.gz | m65.cp - m65:/HDD.IMG
          if (dstIsDir) {
            warn("'-' (standard input) needs a file name as target, not the directory '" + a.getLast() + "'");
          } else if (stdinUsed) {
            warn("'-' (standard input) can only be read once");
          } else {
            stdinUsed = true;
            copyStdin(dst, f);
          }
          continue;
        }
        Path src = path(fs, s);
        if (!Files.exists(src)) {
          warn("cannot stat '" + s + "': No such file or directory");
          continue;
        }
        Path target = dstIsDir ? into(dst, src) : dst;
        if (Files.isDirectory(src)) {
          if (!recursive) {
            warn("-r not specified; omitting directory '" + s + "'");
            continue;
          }
          if (src.getFileSystem() == target.getFileSystem()
              && target.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())) {
            warn("cannot copy a directory, '" + s + "', into itself, '" + target + "'");
            continue;
          }
          copyDir(src, target, f);
        } else {
          copyFile(src, target, f);
        }
      }
      return 0;
    }
  }

  private void copyDir(Path src, Path target, Set<Character> f) throws IOException {
    if (Files.exists(target) && !Files.isDirectory(target)) {
      warn("cannot overwrite non-directory '" + target + "' with directory '" + src + "'");
      return;
    }
    if (!Files.exists(target)) {
      Files.createDirectory(target);
    }
    List<Path> children;
    try (Stream<Path> s = Files.list(src)) {
      children = s.toList();
    }
    for (Path c : children) {
      Path t = target.resolve(name(c));
      if (Files.isDirectory(c)) {
        copyDir(c, t, f);
      } else {
        copyFile(c, t, f);
      }
    }
    if (f.contains('p')) {
      Files.setLastModifiedTime(target, Files.getLastModifiedTime(src));
    }
  }

  private void copyFile(Path src, Path target, Set<Character> f) throws IOException {
    if (Files.isDirectory(target)) {
      warn("cannot overwrite directory '" + target + "' with non-directory");
      return;
    }
    boolean exists = Files.exists(target);
    if (exists && f.contains('n')) {
      return;
    }
    if (exists && Files.isSameFile(src, target)) {
      warn("'" + src + "' and '" + target + "' are the same file");
      return;
    }
    copyFile(src, target, f, f.contains('v') ? "'" + src + "' -> '" + target + "'" : null);
  }

  /**
   * Standard input to {@code target}. On the card the content is collected on the PC first (it
   * is written contiguously, which needs the size), then written; progress shows that part.
   */
  private void copyStdin(Path target, Set<Character> f) throws IOException {
    if (Files.isDirectory(target)) {
      warn("cannot overwrite directory '" + target + "' with non-directory");
      return;
    }
    if (Files.exists(target) && f.contains('n')) {
      return;
    }
    Mega65FileSystem to = target.getFileSystem() instanceof Mega65FileSystem m ? m : null;
    long w0 = to == null ? 0 : to.bytesWritten();
    var counted = new CountingInputStream(in);
    CopyLine progress = !f.contains('v') ? null : new CopyLine(out, "'-' -> '" + target + "'", liveProgress(),
        () -> to == null ? 0 : to.bytesWritten() - w0,
        () -> to != null && counted.eof ? counted.count : 0);
    copying(progress, "-", target, to, () -> writeTarget(counted, target));
  }

  /** Counts what it reads; {@code eof} once the end was reached. */
  private static final class CountingInputStream extends java.io.FilterInputStream {
    volatile long count;
    volatile boolean eof;

    CountingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b < 0) {
        eof = true;
      } else {
        count++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n < 0) {
        eof = true;
      } else {
        count += n;
      }
      return n;
    }

    @Override
    public void close() {
      // standard input stays open
    }
  }

  /**
   * Writes everything from {@code src} to {@code target} and closes {@code src}. On the card a
   * write-on-close channel: the new content goes to free clusters before the old ones are freed,
   * so if it does not fit or the copy is interrupted the old file stays as it was (a generic copy
   * would delete it first).
   */
  private static void writeTarget(InputStream src, Path target) throws IOException {
    OpenOption[] opts = {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
    try (src; OutputStream o = Files.newOutputStream(target, opts)) {
      src.transferTo(o);
    }
  }

  /** As above; {@code line} (if not null) is printed first, with progress while copying. */
  private void copyFile(Path src, Path target, Set<Character> f, String line) throws IOException {
    CopyLine progress = line == null ? null : copyLine(src, target, line);
    Mega65FileSystem card = target.getFileSystem() instanceof Mega65FileSystem m ? m
        : src.getFileSystem() instanceof Mega65FileSystem m ? m : null;
    copying(progress, src.toString(), target, card, () -> {
      writeTarget(Files.newInputStream(src), target);
      if (f.contains('p')) {
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(src));
      }
    });
  }

  private interface Copy {
    void run() throws IOException;
  }

  /**
   * Runs a copy and ends its line ({@code progress}, may be null): plain when done,
   * {@code failed} on an error (reported after it), {@code interrupted} on Ctrl-C; without a
   * line an interruption is reported on stderr, so it never looks like it completed. The MEGA65
   * ({@code card}, may be null) shows the line too, with or without {@code -v}.
   */
  private void copying(CopyLine progress, String src, Path target, Mega65FileSystem card, Copy copy)
      throws IOException {
    String text = "'" + src + "' -> '" + target + "'";
    // on the MEGA65 under the command line, indented, without quotes (room for the paths):
    //   .../dir/file -> /DIR/file
    if (card != null) {
      card.showOnMega65(INDENT + fitCopyLine(src, target.toString(), Request.Text.COLUMNS - INDENT.length()),
          Mega65FileSystem.LineKind.INFO, false, INDENT.length());
    }
    Runnable report = () -> {
      err.println(prog + ": interrupted: '" + src + "' -> '" + target + "' not completed");
      err.flush();
    };
    if (progress == null) {
      Interruption.running(report);
    }
    boolean ok = false;
    try {
      copy.run();
      ok = true;
    } finally {
      Interruption.done(report);
      if (progress != null) {
        progress.close(ok ? "" : " failed");
      }
      if (card != null && !ok) {
        try {
          card.showOnMega65(INDENT + fitCopyLine(src, target.toString(), Request.Text.COLUMNS - INDENT.length()
              - " failed".length()) + " failed", Mega65FileSystem.LineKind.ERROR, true, INDENT.length());
        } catch (IOException | RuntimeException e) {
          // the session may be what failed: the error itself is reported
        }
      }
    }
  }

  /** Lines on the MEGA65 that belong to the command line above them (files copied, moved). */
  static final String INDENT = "  ";

  /**
   * {@code src -> dst} in at most {@code width} characters (the MEGA65's screen; no quotes, to
   * leave room for the paths): a path that does not fit loses its start ({@code .../dir/file}),
   * so that both file names stay; the source (usually the long path on the computer) gives way
   * first.
   */
  static String fitCopyLine(String src, String dst, int width) {
    int frame = " -> ".length();
    if (src.length() + dst.length() + frame <= width) {
      return src + " -> " + dst;
    }
    int room = width - frame;
    int d = Math.min(dst.length(), Math.max(room / 2, room - src.length()));
    int s = room - d;
    return tail(src, s) + " -> " + tail(dst, d);
  }

  /** The end of {@code p} in {@code n} characters, after "..." if it is shortened. */
  private static String tail(String p, int n) {
    if (p.length() <= n) {
      return p;
    }
    return n <= 3 ? "...".substring(0, Math.max(0, n)) : "..." + p.substring(p.length() - (n - 3));
  }

  /**
   * The {@code -v} line of a copy. Progress counts what really moves to and from the card (for a
   * card target that happens when the file is closed, not while the stream is written).
   */
  private CopyLine copyLine(Path src, Path target, String text) throws IOException {
    Mega65FileSystem from = src.getFileSystem() instanceof Mega65FileSystem m ? m : null;
    Mega65FileSystem to = target.getFileSystem() instanceof Mega65FileSystem m ? m : null;
    long r0 = from == null ? 0 : from.bytesRead();
    long w0 = to == null ? 0 : to.bytesWritten();
    long total = Files.size(src) * ((from == null ? 0 : 1) + (to == null ? 0 : 1));
    return new CopyLine(out, text, liveProgress(),
        () -> (from == null ? 0 : from.bytesRead() - r0) + (to == null ? 0 : to.bytesWritten() - w0), total);
  }

  private boolean liveProgress() {
    return CopyLine.live(env, out);
  }

  /** {@code mv [-nfv] SOURCE... DEST} on the card, GNU mv semantics (overwrites by default). */
  private int mv(List<String> a) throws IOException {
    Set<Character> f = flags(a, "nfv");
    if (a.size() < 2) {
      throw new Failure(a.isEmpty() ? "missing file operand" : "missing destination file operand after '"
          + a.getFirst() + "'");
    }
    for (String s : a) {
      if (!isCard(s) && !s.startsWith("/") && Files.exists(Path.of(s))) {
        throw new Failure("works on the card only; use m65.cp and m65.rm to move between computer and card");
      }
    }
    try (var fs = openCard(false)) {
      Path dst = fs.getPath(cardPath(a.getLast()));
      boolean dstIsDir = Files.isDirectory(dst);
      List<String> sources = glob(fs, a.subList(0, a.size() - 1), true);
      if (sources.size() > 1 && !dstIsDir) {
        throw new Failure("target '" + a.getLast() + "' is not a directory");
      }
      for (String s : sources) {
        Path src = fs.getPath(cardPath(s));
        if (!Files.exists(src)) {
          warn("cannot stat '" + s + "': No such file or directory");
          continue;
        }
        Path target = dstIsDir ? into(dst, src) : dst;
        if (Files.exists(target) && !Files.isSameFile(src, target)) {
          if (f.contains('n')) {
            continue;
          }
          if (Files.isDirectory(target) && !Files.isDirectory(src)) {
            warn("cannot overwrite directory '" + target + "' with non-directory");
            continue;
          }
          Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
          Files.move(src, target);
        }
        if (f.contains('v')) {
          out.println("renamed '" + src + "' -> '" + target + "'");
        }
      }
      return 0;
    }
  }

  /** Default of {@code m65.dsplit -m}: well below the C64 core's limit (about 300 to 600 entries). */
  static final int SPLIT_MAX = 250;

  /**
   * {@code dsplit [-rv] [-m MAX] [--dry-run] DIR...}: moves the files of each DIR with more than
   * MAX files into subdirectories by name ({@link Splitter}); with {@code -r} also every
   * directory below. Subdirectories that are there already stay. Only directory entries change
   * (one session), so it is quick. DIR is on the card ({@code m65:/DIR} or {@code /DIR}), or
   * local if a local directory of that name exists (no MEGA65 needed then).
   */
  private int dsplit(List<String> a) throws IOException {
    boolean dry = a.remove("--dry-run");
    String m = option(a, "-m");
    int max;
    try {
      max = m == null ? SPLIT_MAX : Integer.parseInt(m);
    } catch (NumberFormatException e) {
      throw new Failure("-m needs a number: '" + m + "'");
    }
    if (max < 2) {
      throw new Failure("-m needs 2 or more");
    }
    Set<Character> f = flags(a, "rv");
    if (a.isEmpty()) {
      throw new Failure("missing directory operand");
    }
    boolean needCard = a.stream().anyMatch(d -> !isLocalDir(d));
    try (var fs = needCard ? openCard(dry) : null) {
      // all directories to look at first: the new subdirectories are not split again
      List<Path> dirs = new ArrayList<>();
      for (String d : a) {
        Path dir = isLocalDir(d) ? Path.of(d) : fs.getPath(cardPath(d));
        if (!Files.isDirectory(dir)) {
          warn("'" + d + "': " + (Files.exists(dir) ? "Not a directory" : "No such file or directory"));
          continue;
        }
        if (f.contains('r')) {
          addDirectories(dir, dirs);
        } else {
          dirs.add(dir);
        }
      }
      int split = 0;
      for (Path dir : dirs) {
        List<Path> entries;
        try (Stream<Path> l = Files.list(dir)) {
          entries = l.toList();
        }
        List<String> files = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Path e : entries) {
          String n = name(e);
          taken.add(n.toLowerCase(Locale.ROOT));
          if (Files.isRegularFile(e)) {
            files.add(n);
          }
        }
        if (files.size() <= max) {
          if (!f.contains('r')) {
            out.println(shown(dir) + ": " + files.size() + " files, nothing to split");
          }
          continue;
        }
        List<Splitter.Group> plan = Splitter.plan(files, max);
        for (Splitter.Group g : plan) {
          if (taken.contains(g.name().toLowerCase(Locale.ROOT))) {
            throw new Failure("'" + shown(dir.resolve(g.name())) + "' exists already; nothing moved in "
                + shown(dir));
          }
        }
        out.println(shown(dir) + ": " + files.size() + " files into " + plan.size() + " directories"
            + (dry ? " (dry run, nothing changed)" : ""));
        for (Splitter.Group g : plan) {
          out.printf("  %-8s %4d files  (%s ... %s)%n", g.name(), g.files().size(), g.files().getFirst(),
              g.files().getLast());
        }
        if (!dry) {
          splitInto(dir, plan, f.contains('v'));
        }
        split++;
      }
      if (f.contains('r') && split == 0) {
        out.println("no directory with more than " + max + " files");
      }
      return 0;
    }
  }

  /**
   * {@code dir} and every directory below it (with Files.list: Files.walk reads the attributes of
   * every entry in a way that takes minutes on a large card).
   */
  private static void addDirectories(Path dir, List<Path> dirs) throws IOException {
    dirs.add(dir);
    List<Path> children;
    try (Stream<Path> l = Files.list(dir)) {
      children = l.toList();
    }
    for (Path c : children) {
      if (Files.isDirectory(c)) {
        addDirectories(c, dirs);
      }
    }
  }

  /** A local directory that exists, and is not meant for the card ({@code m65:...}). */
  private static boolean isLocalDir(String d) {
    return !isCard(d) && Files.isDirectory(Path.of(d));
  }

  /** {@code m65:/DIR} on the card, the absolute path for a local one. */
  private static String shown(Path p) {
    return p.getFileSystem() instanceof Mega65FileSystem ? CARD_PREFIX + p.toAbsolutePath()
        : p.toAbsolutePath().normalize().toString();
  }

  /** Creates the plan's subdirectories and moves the files; Ctrl-C says how far it got. */
  private void splitInto(Path dir, List<Splitter.Group> plan, boolean verbose) throws IOException {
    Mega65FileSystem card = dir.getFileSystem() instanceof Mega65FileSystem m ? m : null;
    int total = plan.stream().mapToInt(g -> g.files().size()).sum();
    int[] moved = {0};
    Runnable report = () -> {
      err.println(prog + ": interrupted in " + shown(dir) + " after " + moved[0] + " of " + total
          + " files; the others are still in it");
      err.flush();
    };
    Interruption.running(report);
    try {
      for (Splitter.Group g : plan) {
        Path sub = dir.resolve(g.name());
        if (card != null) {
          card.showOnMega65(INDENT + sub.toAbsolutePath() + ": " + g.files().size() + " files",
              Mega65FileSystem.LineKind.INFO, false, INDENT.length());
        }
        Files.createDirectory(sub);
        for (String n : g.files()) {
          Files.move(dir.resolve(n), sub.resolve(n));
          moved[0]++;
          if (verbose) {
            out.println("renamed '" + dir.resolve(n) + "' -> '" + sub.resolve(n) + "'");
          }
        }
      }
    } finally {
      Interruption.done(report);
    }
  }

  private int rm(List<String> a) throws IOException {
    Set<Character> f = flags(a, "rRfv");
    boolean recursive = f.contains('r') || f.contains('R');
    if (a.isEmpty()) {
      if (f.contains('f')) {
        return 0;
      }
      throw new Failure("missing operand");
    }
    try (var fs = openCard(false)) {
      for (String s : glob(fs, a, true)) {
        Path p = fs.getPath(cardPath(s));
        if (p.toAbsolutePath().normalize().getNameCount() == 0) {
          warn("it is dangerous to operate recursively on '/'; refusing");
          continue;
        }
        if (!Files.exists(p)) {
          if (!f.contains('f')) {
            warn("cannot remove '" + s + "': No such file or directory");
          }
          continue;
        }
        if (Files.isDirectory(p)) {
          if (!recursive) {
            warn("cannot remove '" + s + "': Is a directory");
            continue;
          }
          List<Path> all;
          try (Stream<Path> w = Files.walk(p)) {
            all = w.sorted(Comparator.reverseOrder()).toList();
          }
          for (Path q : all) {
            Files.delete(q);
            if (f.contains('v')) {
              out.println("removed '" + q + "'");
            }
          }
        } else {
          Files.delete(p);
          if (f.contains('v')) {
            out.println("removed '" + p + "'");
          }
        }
      }
      return 0;
    }
  }

  private int mkdir(List<String> a) throws IOException {
    Set<Character> f = flags(a, "pv");
    if (a.isEmpty()) {
      throw new Failure("missing operand");
    }
    try (var fs = openCard(false)) {
      for (String s : a) {
        Path p = fs.getPath(cardPath(s));
        try {
          if (f.contains('p')) {
            Files.createDirectories(p);
          } else {
            Files.createDirectory(p);
          }
          if (f.contains('v')) {
            out.println("created directory '" + p + "'");
          }
        } catch (FileAlreadyExistsException e) {
          warn("cannot create directory '" + s + "': File exists");
        } catch (NoSuchFileException e) {
          warn("cannot create directory '" + s + "': No such file or directory");
        }
      }
      return 0;
    }
  }

  private int cat(List<String> a) throws IOException {
    flags(a, "");
    if (a.isEmpty()) {
      throw new Failure("missing operand");
    }
    try (var fs = openCard(true)) {
      for (String s : glob(fs, a, true)) {
        Path p = fs.getPath(cardPath(s));
        if (Files.isDirectory(p)) {
          warn(s + ": Is a directory");
        } else if (!Files.exists(p)) {
          warn(s + ": No such file or directory");
        } else {
          Files.copy(p, out);
        }
      }
      out.flush();
      return 0;
    }
  }

  private int stat(List<String> a) throws IOException {
    flags(a, "");
    if (a.isEmpty()) {
      throw new Failure("missing operand");
    }
    try (var fs = openCard(true)) {
      for (String s : glob(fs, a, true)) {
        Path p0 = fs.getPath(cardPath(s));
        if (!Files.exists(p0)) {
          warn("cannot stat '" + s + "': No such file or directory");
          continue;
        }
        Path p = p0.toRealPath();
        var at = Files.readAttributes(p, Mega65FileAttributes.class);
        out.println("  File: " + p);
        out.println("  Type: " + (at.isDirectory() ? "directory" : "regular file"));
        out.println("  Size: " + at.size());
        out.println("Modify: " + at.lastModifiedTime());
        out.println("Create: " + at.creationTime());
        out.println(" Short: " + at.shortName());
        out.println(" Attrs: " + String.join(" ", attrs(at)));
        out.println("Contig: " + (at.isContiguous() ? "yes" : "no (fragmented)"));
        out.println("Sector: " + at.startSector());
      }
      return 0;
    }
  }

  private static List<String> attrs(Mega65FileAttributes at) {
    List<String> l = new ArrayList<>();
    if (at.isReadOnly()) {
      l.add("read-only");
    }
    if (at.isHidden()) {
      l.add("hidden");
    }
    if (at.isSystem()) {
      l.add("system");
    }
    if (at.isArchive()) {
      l.add("archive");
    }
    return l.isEmpty() ? List.of("-") : l;
  }

  private int df(List<String> a) throws IOException {
    boolean contiguous = a.remove("--contiguous");
    flags(a, "h");
    noOperands(a);
    try (var fs = openCard(true)) {
      Mega65FileStore store = fs.fileStore();
      long total = store.getTotalSpace();
      long free = store.getUnallocatedSpace();
      out.printf("Volume       Size     Used    Avail%n%-10s %6d M %6d M %6d M%n", store.name(),
          total >> 20, (total - free) >> 20, free >> 20);
      if (contiguous) {
        out.printf("largest contiguous free space: %d M%n", store.getLargestContiguousSpace() >> 20);
      }
      return 0;
    }
  }

  private int defrag(List<String> a) {
    List<String> args = new ArrayList<>(a);
    String image = env.get("M65_IMAGE");
    if (image != null && !image.isBlank()) {
      args.addAll(0, List.of("--image", image));
    } else {
      try {
        args.addAll(0, List.of("--target", machine()));
      } catch (IOException e) {
        err.println(prog + ": " + describe(e));
        return 2;
      }
    }
    if (!resetAfter()) {
      args.addFirst("--no-reset");
    }
    return Defrag.run(args.toArray(String[]::new), out, err, env);
  }

  // ------------------------------------------------------------------ machine commands

  private int boot(List<String> a) throws IOException {
    MachineMode mode = mode(a, null);
    Set<Character> f = flags(a, "n");
    String source = oneOperand(a, "IMAGE.d81");
    String cardSource = isCard(source) ? cardPath(source) : null;
    Path local = cardSource == null ? Path.of(source) : null;
    if (local != null && !Files.isRegularFile(local)) {
      throw new Failure("'" + source + "': No such file (card images are written m65:/NAME.D81)");
    }
    try (var fs = openCard(false)) {
      Path onCard;
      if (local != null) {
        onCard = fs.getPath("/" + local.getFileName());
        if (!(Files.exists(onCard) && f.contains('n'))) {
          copyFile(local, onCard, Set.of(), "'" + local + "' -> '" + CARD_PREFIX + onCard + "'");
        }
      } else {
        onCard = fs.getPath(cardSource);
      }
      var boot = fs.closeAndBoot(onCard, mode);
      out.println("booting " + boot.image() + ": \"" + boot.programName() + "\" in " + boot.mode() + " mode");
      return 0;
    }
  }

  private int runPrg(List<String> a) throws IOException {
    var options = Mega65Launcher.Options.defaults();
    String mount = option(a, "--mount");
    if (mount != null) {
      // images are mounted from the root directory
      options = options.withMount(mount.startsWith(CARD_PREFIX) ? cardPath(mount).replaceFirst("^/+", "") : mount);
    }
    String jump = option(a, "--jump");
    if (jump != null) {
      options = options.withJump(Integer.parseInt(jump.replaceFirst("^(\\$|0x)", ""), 16));
    }
    if (a.remove("--no-run")) {
      options = options.withRun(false);
    }
    options = options.withVideo(video(a));
    String file = oneOperand(a, "FILE.prg");
    requireMachine();
    byte[] prg = Files.readAllBytes(Path.of(file));
    MachineMode m = Mega65Launcher.runPrg(freeTarget(), prg, options);
    out.println("started " + file + (m == null ? " (jump)" : " in " + m + " mode"));
    return 0;
  }

  private int reset(List<String> a) throws IOException {
    MachineMode mode = mode(a, MachineMode.MEGA65);
    Video v = video(a);
    noOperands(a);
    requireMachine();
    Mega65Launcher.reset(freeTarget(), mode, null, v);
    out.println("reset to " + mode + " mode" + (mode == MachineMode.MEGA65 ? " (drive 0 detached)" : ""));
    return 0;
  }

  private int mount(List<String> a) throws IOException {
    String name = oneOperand(a, "IMAGE.d81");
    requireMachine();
    try (var fs = openCard(false)) {
      Path image = fs.getPath(cardPath(name));
      boolean ok = fs.mountD81(image);
      if (!ok) {
        throw new Failure("the hypervisor refused to mount " + name);
      }
      out.println("mounted " + image.toAbsolutePath().normalize() + " as drive 0");
      return 0;
    }
  }

  private int peek(List<String> a) throws IOException {
    if (a.isEmpty() || a.size() > 2) {
      throw new Failure("usage: " + usageOf("peek"));
    }
    int addr = Integer.parseInt(a.get(0).replaceFirst("^(\\$|0x)", ""), 16);
    int count = a.size() == 2 ? Integer.parseInt(a.get(1)) : 16;
    requireMachine();
    try (var fs = openCard(true)) {
      for (int off = 0; off < count; off += 256) {
        int n = Math.min(256, count - off);
        byte[] b = fs.peek(addr + off, n);
        for (int i = 0; i < n; i += 16) {
          out.printf("%07X  %s%n", addr + off + i, HexFormat.ofDelimiter(" ").formatHex(b, i, Math.min(n, i + 16)));
        }
      }
      return 0;
    }
  }

  private int discover(List<String> a) throws IOException {
    noOperands(a);
    out.println(discoverAndRemember());
    return 0;
  }

  // ------------------------------------------------------------------ completion

  /**
   * {@code __complete LINE [WORDBREAKS [on|off]]} (hidden, called by the completion script): prints the
   * completions of LINE's last word, see {@link Completion}. Never fails: no answer is "none".
   */
  private int complete(List<String> a) {
    String line = a.size() > 1 ? a.get(1) : "";
    String breaks = a.size() > 2 ? a.get(2) : Completion.WORDBREAKS;
    boolean ignoreCase = a.size() > 3 && a.get(3).equals("on");
    Completion.Answer answer;
    try {
      answer = new Completion(this::listQuietly, breaks, ignoreCase).complete(line);
    } catch (RuntimeException e) {
      answer = Completion.Answer.NONE;
    }
    out.print(answer.text());
    out.flush();
    return 0;
  }

  /** {@code completion bash}: prints the bash completion script. */
  private int completion(List<String> a) {
    if (a.equals(List.of("completion", "bash"))) {
      out.print(Completion.bashScript(version()));
      out.flush();
      return 0;
    }
    err.println("usage: m65 completion bash      (load it with: eval \"$(m65 completion bash)\")");
    return 1;
  }

  /**
   * A card directory for completion, without disturbing anything: only an image (M65_IMAGE) or
   * a transfer helper that is already running and free, at M65_TARGET or the remembered
   * address; no discovery, no trigger, and nothing on the MEGA65's screen.
   */
  private List<Completion.Entry> listQuietly(String dir) throws IOException {
    Map<String, Object> e = new HashMap<>();
    e.put("readOnly", true);
    e.put("quitOnClose", false);
    Mega65FileSystem fs;
    String image = env.get("M65_IMAGE");
    if (image != null && !image.isBlank()) {
      fs = Mega65FileSystem.openImage(Path.of(image), e);
    } else {
      String t = env.get("M65_TARGET");
      if (t == null || t.isBlank()) {
        Path cache = targetCache();
        t = cache == null ? "" : Files.readString(cache).strip();
      }
      if (t.isEmpty()) {
        throw new IOException("no known MEGA65 address");
      }
      e.put("target", t);
      e.put("runningOnly", true);
      fs = (Mega65FileSystem) FileSystems.newFileSystem(URI.create("mega65://auto/"), e);
    }
    try (fs; Stream<Path> s = Files.list(fs.getPath(dir.isEmpty() ? "." : dir))) {
      List<Completion.Entry> entries = new ArrayList<>();
      for (Path p : s.toList()) {
        entries.add(new Completion.Entry(p.getFileName().toString(), Files.isDirectory(p)));
      }
      return entries;
    }
  }

  // ------------------------------------------------------------------ wildcards

  /**
   * The operands with wildcards in card paths expanded, as bash does for local paths (which it
   * cannot do for card paths): {@code *}, {@code ?} and {@code [...]} in any path component,
   * case-sensitive, sorted; an operand that matches nothing stays as it is (the tool then reports
   * it as missing). {@code plainIsCard}: plain operands are card paths too (card-only tools).
   */
  static List<String> glob(Mega65FileSystem fs, List<String> operands, boolean plainIsCard) throws IOException {
    List<String> out = new ArrayList<>();
    for (String s : operands) {
      if (fs == null || !(isCard(s) || plainIsCard) || !hasWildcard(s)) {
        out.add(s);
        continue;
      }
      String pre = isCard(s) ? CARD_PREFIX : "";
      List<String> found = globCard(fs, cardPath(s));
      if (found.isEmpty()) {
        out.add(s);
      } else {
        found.forEach(f -> out.add(pre + f));
      }
    }
    return out;
  }

  static boolean hasWildcard(String s) {
    return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
  }

  /** The card paths that {@code pattern} matches (relative ones relative to the working directory). */
  static List<String> globCard(Mega65FileSystem fs, String pattern) throws IOException {
    boolean dirOnly = pattern.endsWith("/");
    List<String> found = List.of(pattern.startsWith("/") ? "/" : "");
    String[] parts = pattern.split("/");
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty()) {
        continue;
      }
      boolean last = i == parts.length - 1;
      List<String> next = new ArrayList<>();
      for (String base : found) {
        if (!hasWildcard(part)) {
          next.add(join(base, part));
          continue;
        }
        Path dir = fs.getPath(base.isEmpty() ? "." : base);
        if (!Files.isDirectory(dir)) {
          continue;
        }
        java.util.regex.Pattern re = globRegex(part);
        if (re == null) {
          next.add(join(base, part)); // broken pattern: a literal name
          continue;
        }
        List<String> names;
        try (Stream<Path> l = Files.list(dir)) {
          names = l.filter(p -> !last || !dirOnly || Files.isDirectory(p)).filter(p -> last || Files.isDirectory(p))
              .map(p -> p.getFileName().toString())
              .filter(n -> !n.equals(".") && !n.equals("..") && re.matcher(n).matches()).sorted(NAME_ORDER).toList();
        }
        names.forEach(n -> next.add(join(base, n)));
      }
      found = next;
    }
    List<String> out = new ArrayList<>();
    for (String f : found) {
      if (!f.isEmpty() && !f.equals("/") && Files.exists(fs.getPath(f))) {
        out.add(dirOnly ? f + "/" : f);
      }
    }
    return out;
  }

  /** As ls sorts: by name regardless of case, then by case. */
  private static final Comparator<String> NAME_ORDER =
      Comparator.comparing((String n) -> n.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder());

  private static String join(String base, String name) {
    return base.isEmpty() ? name : base.endsWith("/") ? base + name : base + "/" + name;
  }

  /** A shell wildcard for one name ({@code * ? [abc] [!a-z] [^a-z]}), case-sensitive; null if broken. */
  static java.util.regex.Pattern globRegex(String glob) {
    try {
      return java.util.regex.Pattern.compile(Globs.toRegex(glob, true), java.util.regex.Pattern.DOTALL);
    } catch (java.util.regex.PatternSyntaxException e) {
      return null; // e.g. "[abc": taken literally, as bash does
    }
  }

  // ------------------------------------------------------------------ helpers

  static boolean isCard(String s) {
    return s.startsWith(CARD_PREFIX);
  }

  /**
   * "m65:/x" or "/x" -> "/x"; "m65:x" or "x" -> "x" (relative to the working directory, see
   * m65.cd); "m65:" -> "" (the working directory).
   */
  static String cardPath(String s) {
    return isCard(s) ? s.substring(CARD_PREFIX.length()) : s;
  }

  private static Path path(Mega65FileSystem fs, String s) {
    return isCard(s) ? fs.getPath(cardPath(s)) : Path.of(s);
  }

  /** dst/NAME, where NAME is the source's name as stored (card) or as given (local). */
  private static Path into(Path dir, Path src) throws IOException {
    String n = name(src);
    return n.isEmpty() ? dir : dir.resolve(n);
  }

  private static String name(Path p) throws IOException {
    Path real = p.getFileSystem() == FileSystems.getDefault() ? p.toAbsolutePath().normalize() : p.toRealPath();
    return real.getFileName() == null ? "" : real.getFileName().toString();
  }

  private boolean resetAfter() {
    String r = env.getOrDefault("M65_RESET", "");
    return r.equals("1") || r.equalsIgnoreCase("true") || r.equalsIgnoreCase("yes");
  }

  /** Opens the card; the MEGA65 shows the command line on its screen. */
  private Mega65FileSystem openCard(boolean readOnly) throws IOException {
    Map<String, Object> e = new HashMap<>();
    e.put("quitOnClose", resetAfter());
    e.put("readOnly", readOnly);
    String image = env.get("M65_IMAGE");
    if (image != null && !image.isBlank()) {
      return Mega65FileSystem.openImage(Path.of(image), e);
    }
    e.put("target", machine());
    e.put("force", force());
    var fs = (Mega65FileSystem) FileSystems.newFileSystem(URI.create("mega65://auto/"), e);
    try {
      fs.showOnMega65(commandLine, Mega65FileSystem.LineKind.COMMAND, false, commandName.length() + 1);
    } catch (IOException | RuntimeException ex) {
      fs.close();
      throw ex;
    }
    return fs;
  }

  private void requireMachine() {
    String image = env.get("M65_IMAGE");
    if (image != null && !image.isBlank()) {
      throw new Failure("needs a MEGA65, not an image file (M65_IMAGE is set)");
    }
  }

  private Target target() throws IOException {
    return Target.parse(machine());
  }

  /**
   * The MEGA65 for a tool that triggers it (run, reset): only if its transfer helper does not
   * serve another computer, whose session the trigger would end ({@code M65_FORCE=1}: anyway).
   */
  private Target freeTarget() throws IOException {
    Target t = target();
    RemoteSdSession.ensureFree(t, RemoteSdSession.Options.defaults().withForce(force()));
    return t;
  }

  /** {@code M65_FORCE=1}: take the MEGA65 over even if it serves another computer. */
  private boolean force() {
    return env.getOrDefault("M65_FORCE", "").equals("1");
  }


  /**
   * The MEGA65's address ({@code fe80::…%if}): {@code M65_TARGET}; else the address remembered
   * from the last discovery, if it is on a wired interface and answers a quick trigger and
   * ping; else discovery, which waits for the MEGA65's beacon (sent once a second), prefers a
   * wired interface and remembers the result.
   */
  private String machine() throws IOException {
    String t = env.get("M65_TARGET");
    if (t != null && !t.isBlank()) {
      return t;
    }
    Path cache = targetCache();
    if (cache != null) {
      try {
        String remembered = Files.readString(cache).strip();
        Target r = remembered.isEmpty() ? null : Target.parse(remembered);
        if (r != null && r.networkInterface() != null && !Mega65Locator.isWireless(r.networkInterface())
            && (RemoteSdSession.helperAnswers(r, Duration.ofMillis(150))
                || Etherload.answers(r, Duration.ofMillis(300)))) {
          return remembered;
        }
      } catch (IOException | IllegalArgumentException e) {
        // no usable remembered address: discover
      }
    }
    return discoverAndRemember();
  }

  private String discoverAndRemember() throws IOException {
    Target found = Mega65Locator.discover(Duration.ofSeconds(3)).orElseThrow(() -> new IOException(
        "no MEGA65 found (remote mode on? DIP switch 2 or SHIFT+£)"));
    String t = found.address().getHostAddress().replaceFirst("%.*", "") + "%" + found.networkInterface().getName();
    Path cache = targetCache();
    if (cache != null) {
      try {
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, t + "\n");
      } catch (IOException e) {
        // remembering is only an optimisation
      }
    }
    return t;
  }

  /**
   * {@code $XDG_CACHE_HOME/m65fs/target}, {@code ~/.cache/m65fs/target} or (Windows)
   * {@code %LOCALAPPDATA%\m65fs\target}; null without any of them.
   */
  private Path targetCache() {
    for (String[] c : new String[][] {{"XDG_CACHE_HOME", ""}, {"HOME", ".cache"}, {"LOCALAPPDATA", ""}}) {
      String base = env.get(c[0]);
      if (base != null && !base.isBlank()) {
        return c[1].isEmpty() ? Path.of(base, "m65fs", "target") : Path.of(base, c[1], "m65fs", "target");
      }
    }
    return null;
  }

  /** The release version (from the build), e.g. {@code 0.1.0}. */
  static String version() {
    try (var in = M65fs.class.getResourceAsStream("/io/github/paw/m65fs/version.properties")) {
      var p = new java.util.Properties();
      if (in != null) {
        p.load(in);
      }
      return p.getProperty("version", "unknown");
    } catch (IOException e) {
      return "unknown";
    }
  }

  /**
   * Removes and returns the short flags (clusters like {@code -rp} allowed) from the arguments;
   * {@code --} ends option parsing.
   */
  private Set<Character> flags(List<String> a, String allowed) {
    Set<Character> f = new HashSet<>();
    List<String> operands = new ArrayList<>();
    boolean done = false;
    for (String s : a) {
      if (!done && s.equals("--")) {
        done = true;
      } else if (!done && s.length() > 1 && s.startsWith("-")) {
        for (char c : s.substring(1).toCharArray()) {
          if (allowed.indexOf(c) < 0) {
            throw new Failure("invalid option -- '" + c + "'\nusage: " + usageOf(prog.substring(4)));
          }
          f.add(c);
        }
      } else {
        operands.add(s);
      }
    }
    a.clear();
    a.addAll(operands);
    return f;
  }

  private static MachineMode mode(List<String> a, MachineMode def) {
    String m = option(a, "--mode");
    if (m == null || m.equals("auto")) {
      return def;
    }
    return switch (m.toLowerCase(Locale.ROOT)) {
      case "c64", "go64" -> MachineMode.C64;
      case "mega65", "m65", "c65" -> MachineMode.MEGA65;
      default -> throw new Failure("--mode must be c64, mega65 or auto");
    };
  }

  private static Video video(List<String> a) {
    boolean pal = a.remove("--pal");
    boolean ntsc = a.remove("--ntsc");
    if (pal && ntsc) {
      throw new Failure("--pal and --ntsc exclude each other");
    }
    return pal ? Video.PAL : ntsc ? Video.NTSC : Video.UNCHANGED;
  }

  private static String option(List<String> a, String name) {
    int i = a.indexOf(name);
    if (i < 0) {
      return null;
    }
    if (i + 1 >= a.size()) {
      throw new Failure(name + " needs a value");
    }
    a.remove(i);
    return a.remove(i);
  }

  private String oneOperand(List<String> a, String what) {
    if (a.size() != 1 || a.getFirst().startsWith("-")) {
      throw new Failure((a.isEmpty() ? "missing " + what : "unexpected arguments") + "\nusage: "
          + usageOf(prog.substring(4)));
    }
    return a.removeFirst();
  }

  private void noOperands(List<String> a) {
    if (!a.isEmpty()) {
      throw new Failure("unexpected argument '" + a.getFirst() + "'");
    }
  }

  static String usageOf(String cmd) {
    return switch (cmd) {
      case "ls" -> "m65.ls [-lRd] [PATH...]";
      case "tree" -> "m65.tree [-d] [-L LEVEL] [DIR]";
      case "cd" -> "m65.cd [DIR]";
      case "pwd" -> "m65.pwd";
      case "cp" -> "m65.cp [-rRnfpv] SOURCE... DEST      (card paths: m65:/PATH; SOURCE - = standard input)";
      case "mv" -> "m65.mv [-nfv] SOURCE... DEST";
      case "rm" -> "m65.rm [-rRfv] PATH...";
      case "mkdir" -> "m65.mkdir [-pv] DIR...";
      case "dsplit" -> "m65.dsplit [-rv] [-m MAX] [--dry-run] DIR...";
      case "cat" -> "m65.cat FILE...";
      case "stat" -> "m65.stat PATH...";
      case "df" -> "m65.df [--contiguous]";
      case "defrag" -> "m65.defrag [--fix] [DIR]";
      case "boot" -> "m65.boot [-n] [--mode c64|mega65] IMAGE.d81 | m65:/IMAGE.D81";
      case "run" -> "m65.run [--mount IMAGE.D81] [--pal|--ntsc] [--no-run] [--jump HEX] FILE.prg";
      case "reset" -> "m65.reset [--mode mega65|c64] [--pal|--ntsc]";
      case "mount" -> "m65.mount IMAGE.D81";
      case "peek" -> "m65.peek ADDRESS_HEX [COUNT]";
      case "discover" -> "m65.discover";
      default -> "m65 COMMAND [ARGS]";
    };
  }

  private static void usage(PrintStream s) {
    s.println("""
        m65 tools: the MEGA65's SD card and machine over Ethernet, in the style of the Unix
        core utilities. Each tool is m65.COMMAND (or: m65 COMMAND).

        files (card paths: m65:/PATH, or m65:PATH relative to the working directory; tools
        that only use the card also take plain /PATH and PATH; wildcards * ? [...] work in
        card paths: m65.cp m65:/roms/* .)
          m65.ls [-lRd] [PATH...]             list; -l sizes/times/[fragmented], -R recursive
          m65.tree [-d] [-L LEVEL] [DIR]      tree view with sizes, dates, directory totals;
                                              -d directories only, -L depth
          m65.cd [DIR]                        change the working directory (root without DIR);
                                              the MEGA65 keeps it: same as CHDIR "DIR",U12
          m65.pwd                             print the working directory
          m65.cp [-rRnfpv] SOURCE... DEST     copy like cp: computer <-> card, card -> card;
                                              -r directories, -n keep existing, -p keep times;
                                              card files are always written contiguously
          m65.mv [-nfv] SOURCE... DEST        move/rename on the card (overwrites unless -n)
          m65.rm [-rRfv] PATH...              delete (-r directories, -f ignore missing)
          m65.mkdir [-pv] DIR...              create directories
          m65.cat FILE...                     print files
          m65.stat PATH...                    details: 8.3 name, start sector, contiguous
          m65.df [--contiguous]               size and free space (+ largest free run, slow)
          m65.dsplit [-rv] [-m MAX] [--dry-run] DIR...
                                              files of DIR into subdirectories of at most
                                              MAX (250) by name: 0-C, D-G, R, Sa-Sd ... (for
                                              cores that load only so many entries); -r
                                              every directory below DIR with more than MAX;
                                              on the card (m65:/DIR, /DIR) or a local
                                              directory (one that exists here)
          m65.defrag [--fix] [DIR]            list fragmented files; --fix defragments them

        machine
          m65.boot [-n] [--mode c64|mega65] IMAGE.d81
                                              copy a local .d81 to the card root (-n: keep an
                                              existing one), mount it, reset, run its first
                                              program; or boot m65:/IMAGE.D81 from the card
          m65.run [--mount IMAGE.D81] [--pal|--ntsc] [--no-run] [--jump HEX] FILE.prg
                                              load a PRG into memory and start it
                                              ($0801 -> C64 mode, $2001 -> MEGA65 mode)
          m65.reset [--mode mega65|c64]       reset; MEGA65 mode also detaches drive 0
          m65.mount IMAGE.D81                 attach a D81 from the card root as drive 0
          m65.peek ADDRESS_HEX [COUNT]        hex dump of memory (28-bit addresses)
          m65.discover                        print the MEGA65's address

        shell
          m65 completion bash                 bash tab completion, also for card paths (from
                                              a running transfer helper only); load it with
                                              eval "$(m65 completion bash)"

        environment
          M65_TARGET=fe80::...%eth0           MEGA65 address (default: found automatically
                                              and remembered in ~/.cache/m65fs/target)
          M65_IMAGE=card.img                  use an SD card image instead of a MEGA65
          M65_RESET=1                         reset the MEGA65 after each file command
                                              (default: it keeps showing the transfer screen
                                              until m65.reset, boot or run)
          M65_FORCE=1                         take the MEGA65 over even if it is busy with
                                              another command or reserved for another
                                              computer (until m65.reset there or RUN/STOP)

        exit status: 0 ok, 1 error (m65.defrag: 1 fragmented files remain, 2 error)""");
  }
}
