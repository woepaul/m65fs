package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code m65 install} / {@code m65 uninstall} / {@code m65 completion install|uninstall}: puts the
 * running binary, its {@code m65.*} commands and the bash completion in place, and takes exactly
 * those away again.
 *
 * <ul>
 *   <li>The binary: copied to DIR (default {@code ~/.local/bin}; Windows
 *       {@code %LOCALAPPDATA%\Programs\m65}) unless it runs from there already.
 *   <li>The commands: relative links {@code m65.ls -> m65} (Linux, macOS), or copies of the
 *       launcher {@code m65.ls.exe} that the Windows image carries (Windows). Entries of
 *       commands that no longer exist are removed; a file of such a name that is not ours stays.
 *   <li>Bash completion: the script in bash-completion's user directory
 *       ({@code ~/.local/share/bash-completion/completions/m65}, plus a link per command, which
 *       is how bash-completion finds it on the first TAB), if bash-completion is installed;
 *       otherwise one marked line in {@code ~/.bashrc}.
 * </ul>
 */
final class Installer {

  /** The end of the line that the fallback adds to ~/.bashrc (and uninstall looks for). */
  static final String BASHRC_MARK = "# m65fs completion";
  /** The first line of the completion script (marks the file as ours). */
  static final String SCRIPT_MARK = "# bash completion for the m65 tools (m65fs";

  private final Path self;
  private final Map<String, String> env;
  private final boolean windows;
  private final boolean mac;
  private final boolean bashCompletion;
  private final PrintStream out;
  private final PrintStream err;

  /**
   * @param self the running binary
   * @param bashCompletion whether the bash-completion package is installed
   */
  Installer(Path self, Map<String, String> env, boolean windows, boolean mac, boolean bashCompletion,
      PrintStream out, PrintStream err) {
    this.self = self;
    this.env = env;
    this.windows = windows;
    this.mac = mac;
    this.bashCompletion = bashCompletion;
    this.out = out;
    this.err = err;
  }

  /** For the running binary on this computer. */
  static Installer forThisComputer(Path self, Map<String, String> env, PrintStream out, PrintStream err) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    boolean windows = os.startsWith("windows");
    boolean mac = os.contains("mac");
    boolean bc = !windows && List.of("/usr/share/bash-completion/bash_completion", "/etc/bash_completion",
        "/usr/local/share/bash-completion/bash_completion", "/opt/homebrew/share/bash-completion/bash_completion",
        "/usr/local/etc/profile.d/bash_completion.sh", "/opt/homebrew/etc/profile.d/bash_completion.sh")
        .stream().anyMatch(f -> Files.exists(Path.of(f)));
    return new Installer(self, env, windows, mac, bc, out, err);
  }

  private String binaryName() {
    return windows ? "m65.exe" : "m65";
  }

  private Path home() {
    return Path.of(env.getOrDefault(windows ? "USERPROFILE" : "HOME", System.getProperty("user.home")));
  }

  /** {@code ~/.local/bin}; Windows {@code %LOCALAPPDATA%\Programs\m65}. */
  Path defaultDir() {
    if (windows) {
      String local = env.get("LOCALAPPDATA");
      return local != null ? Path.of(local, "Programs", "m65") : home().resolve("AppData/Local/Programs/m65");
    }
    return home().resolve(".local/bin");
  }

  // ------------------------------------------------------------------ install

  int install(Path dir, boolean completion) throws IOException {
    dir = dir.toAbsolutePath().normalize();
    boolean created = !Files.isDirectory(dir);
    Files.createDirectories(dir);
    Path bin = dir.resolve(binaryName());
    if (Files.exists(bin) && Files.isSameFile(bin, self)) {
      out.println("m65: runs from " + dir + " already");
    } else {
      Path tmp = dir.resolve("." + binaryName() + ".tmp");
      Files.copy(self, tmp, StandardCopyOption.REPLACE_EXISTING);
      if (!windows) {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      try {
        Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw new IOException("cannot replace " + bin + " (running?): " + e.getMessage(), e);
      }
      out.println("installed " + bin);
    }
    commands(dir);
    if (completion && !windows) {
      completionInstall(bin);
    }
    pathHint(dir, created);
    return 0;
  }

  /** Creates the m65.CMD entries in {@code dir} and removes those of commands that no longer exist. */
  private void commands(Path dir) throws IOException {
    Path launcher = windows ? launcherTemplate() : null;
    int made = 0;
    for (String c : M65fs.COMMANDS) {
      Path e = entry(dir, c);
      if (Files.exists(e, LinkOption.NOFOLLOW_LINKS) && !isOurs(e)) {
        err.println("m65: " + e + " exists and is not an m65 command: left alone");
        continue;
      }
      if (windows) {
        Files.copy(launcher, e, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.deleteIfExists(e);
        Files.createSymbolicLink(e, Path.of(binaryName()));
      }
      made++;
    }
    for (Path e : ours(dir)) {
      if (!M65fs.COMMANDS.contains(command(e))) {
        Files.delete(e);
        out.println("removed " + e + " (no longer a command)");
      }
    }
    out.println((windows ? "launchers " : "links ") + "m65.{" + String.join(",", M65fs.COMMANDS) + "}"
        + (windows ? ".exe" : " -> m65") + " in " + dir + " (" + made + ")");
  }

  /**
   * The launcher to copy for every command (Windows): {@code launcher.exe} of the install files
   * (only the Windows image has it), written to a temporary file; else one next to the running
   * binary.
   */
  private Path launcherTemplate() throws IOException {
    byte[] embedded = InstallFiles.read("launcher.exe");
    if (embedded != null) {
      Path tmp = Files.createTempFile("m65-launcher", ".exe");
      tmp.toFile().deleteOnExit();
      Files.write(tmp, embedded);
      return tmp;
    }
    Path from = self.toAbsolutePath().getParent();
    for (String c : M65fs.COMMANDS) {
      Path l = entry(from, c);
      if (Files.isRegularFile(l) && isLauncher(l)) {
        return l;
      }
    }
    throw new IOException("this m65.exe has no launcher for the m65.COMMAND.exe commands (build it with "
        + "tools/build-native.sh on Windows)");
  }

  private Path entry(Path dir, String command) {
    return dir.resolve("m65." + command + (windows ? ".exe" : ""));
  }

  /** The command of an m65.CMD entry. */
  private String command(Path e) {
    String n = e.getFileName().toString().substring(4);
    return windows ? n.replaceFirst("(?i)\\.exe$", "") : n;
  }

  /** The m65.CMD entries in {@code dir} that are ours. */
  private List<Path> ours(Path dir) throws IOException {
    List<Path> l = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return l;
    }
    try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "m65.*")) {
      for (Path e : s) {
        if (!e.getFileName().toString().equalsIgnoreCase(binaryName()) && isOurs(e)) {
          l.add(e);
        }
      }
    }
    return l;
  }

  /** A link to "m65" (Linux, macOS) or an m65 launcher (Windows). */
  private boolean isOurs(Path e) throws IOException {
    if (windows) {
      return Files.isRegularFile(e) && isLauncher(e);
    }
    return Files.isSymbolicLink(e) && Files.readSymbolicLink(e).toString().equals(binaryName());
  }

  /** The launcher: a few KB with "m65.exe" in it as UTF-16 (m65.exe itself is many MB). */
  static boolean isLauncher(Path f) throws IOException {
    if (Files.size(f) > 64 * 1024) {
      return false;
    }
    byte[] want = "m65.exe".getBytes(StandardCharsets.UTF_16LE);
    byte[] b = Files.readAllBytes(f);
    outer:
    for (int i = 0; i + want.length <= b.length; i++) {
      for (int j = 0; j < want.length; j++) {
        if (b[i + j] != want[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  /** Says how to put {@code dir} on the PATH, if it is not on it. */
  private void pathHint(Path dir, boolean created) {
    String path = env.getOrDefault(windows ? "Path" : "PATH", env.getOrDefault("PATH", ""));
    for (String p : path.split(windows ? ";" : ":")) {
      try {
        if (!p.isEmpty() && Path.of(p).toAbsolutePath().normalize().equals(dir)) {
          return;
        }
      } catch (RuntimeException e) {
        // not a path
      }
    }
    // the command on a line of its own, without indentation (easy to copy), the explanation after "-----"
    out.println();
    if (windows) {
      out.println(dir + " is not on your PATH yet. To add it, run in PowerShell:");
      out.println();
      out.println("[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', "
          + "'User') + ';" + dir + "', 'User')");
      out.println("-----");
      out.println("This adds it to your user PATH. Then open a new terminal.");
      return;
    }
    String shown = dir.startsWith(home()) ? "$HOME/" + home().relativize(dir) : dir.toString();
    String shell = Path.of(env.getOrDefault("SHELL", "bash")).getFileName().toString();
    out.println(dir + " is not on your PATH yet. To add it, run:");
    out.println();
    switch (shell) {
      case "zsh" -> out.println("echo 'export PATH=\"" + shown + ":$PATH\"' >> ~/" + (mac ? ".zprofile" : ".zshrc"));
      case "fish" -> out.println("fish_add_path " + dir);
      default -> out.println("echo 'export PATH=\"" + shown + ":$PATH\"' >> ~/" + (mac ? ".bash_profile" : ".bashrc"));
    }
    out.println("-----");
    out.println("Then open a new terminal." + (created && !mac && dir.equals(home().resolve(".local/bin"))
        ? " Many Linux distributions add ~/.local/bin by themselves once it exists, so logging in again"
            + " may be enough." : ""));
  }

  // ------------------------------------------------------------------ completion

  /** bash-completion's user directory for completion scripts. */
  private Path completionDir() {
    String user = env.get("BASH_COMPLETION_USER_DIR");
    if (user != null && !user.isBlank()) {
      return Path.of(user, "completions");
    }
    String data = env.get("XDG_DATA_HOME");
    return (data != null && !data.isBlank() ? Path.of(data) : home().resolve(".local/share"))
        .resolve("bash-completion/completions");
  }

  private Path bashrc() {
    return home().resolve(".bashrc");
  }

  /** Installs bash completion; {@code bin} is the installed binary (for the ~/.bashrc fallback). */
  int completionInstall(Path bin) throws IOException {
    if (windows) {
      out.println("m65: no bash completion on Windows (it works in Git Bash: eval \"$(m65 completion bash)\")");
      return 0;
    }
    if (bashCompletion) {
      Path d = completionDir();
      Files.createDirectories(d);
      Path script = d.resolve("m65");
      if (Files.exists(script, LinkOption.NOFOLLOW_LINKS) && !isOurScript(script)) {
        err.println("m65: " + script + " exists and is not m65's completion: left alone");
        return 1;
      }
      Files.writeString(script, Completion.bashScript(M65fs.version()));
      for (String c : M65fs.COMMANDS) { // bash-completion loads a script by the command's name
        Path l = d.resolve("m65." + c);
        if (Files.exists(l, LinkOption.NOFOLLOW_LINKS) && !(Files.isSymbolicLink(l)
            && Files.readSymbolicLink(l).toString().equals("m65"))) {
          continue;
        }
        Files.deleteIfExists(l);
        Files.createSymbolicLink(l, Path.of("m65"));
      }
      out.println("bash completion: " + script + " (in new shells)");
      return 0;
    }
    String line = "[ -x '" + bin + "' ] && eval \"$('" + bin + "' completion bash)\"  " + BASHRC_MARK;
    Path rc = bashrc();
    List<String> lines = Files.exists(rc) ? Files.readAllLines(rc) : new ArrayList<>();
    lines = new ArrayList<>(lines.stream().filter(l -> !l.endsWith(BASHRC_MARK)).toList());
    lines.add(line);
    Files.write(rc, lines);
    out.println("bash completion: a line in " + rc + " (no bash-completion package found; in new shells)");
    return 0;
  }

  /** Removes what {@link #completionInstall} added. */
  int completionUninstall() throws IOException {
    if (windows) {
      return 0;
    }
    Path d = completionDir();
    Path script = d.resolve("m65");
    if (Files.exists(script, LinkOption.NOFOLLOW_LINKS) && isOurScript(script)) {
      for (String c : allEntryNames(d)) {
        Path l = d.resolve(c);
        if (Files.isSymbolicLink(l) && Files.readSymbolicLink(l).toString().equals("m65")) {
          Files.delete(l);
        }
      }
      Files.delete(script);
      out.println("removed bash completion " + script);
    }
    Path rc = bashrc();
    if (Files.exists(rc)) {
      List<String> lines = Files.readAllLines(rc);
      List<String> kept = lines.stream().filter(l -> !l.endsWith(BASHRC_MARK)).toList();
      if (kept.size() != lines.size()) {
        Files.write(rc, kept);
        out.println("removed the completion line from " + rc);
      }
    }
    return 0;
  }

  /** The names of the m65.* entries in {@code d} (also of commands that no longer exist). */
  private static List<String> allEntryNames(Path d) throws IOException {
    List<String> n = new ArrayList<>();
    try (DirectoryStream<Path> s = Files.newDirectoryStream(d, "m65.*")) {
      s.forEach(p -> n.add(p.getFileName().toString()));
    }
    return n;
  }

  private static boolean isOurScript(Path f) throws IOException {
    if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    List<String> l = Files.readAllLines(f);
    return !l.isEmpty() && l.getFirst().startsWith(SCRIPT_MARK);
  }

  // ------------------------------------------------------------------ uninstall

  /** Removes the m65.* entries in {@code dir}, the binary there and the completion. */
  int uninstall(Path dir) throws IOException {
    dir = dir.toAbsolutePath().normalize();
    int n = 0;
    for (Path e : ours(dir)) {
      Files.delete(e);
      n++;
    }
    if (n > 0) {
      out.println("removed " + n + " m65.* " + (windows ? "launchers" : "links") + " from " + dir);
    }
    completionUninstall();
    Path bin = dir.resolve(binaryName());
    if (Files.isRegularFile(bin, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(bin)) {
      try {
        Files.delete(bin);
        out.println("removed " + bin);
      } catch (IOException e) {
        if (windows && Files.isSameFile(bin, self)) {
          // Windows cannot delete a running program: a detached cmd does it once this one has ended
          new ProcessBuilder("cmd.exe", "/c", "ping -n 3 127.0.0.1 >nul & del /f /q \"" + bin + "\" & rmdir \""
              + dir + "\" 2>nul").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
          out.println("removing " + bin + " in a moment (it is the running program)");
          return 0;
        }
        err.println("m65: cannot remove " + bin + " (" + e.getMessage() + "): delete it by hand");
        return 1;
      }
    } else if (n == 0) {
      out.println("m65: nothing of m65 in " + dir);
    }
    return 0;
  }
}
