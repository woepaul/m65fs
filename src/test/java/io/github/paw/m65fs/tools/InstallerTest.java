package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** m65 install / uninstall with a stand-in binary in temporary directories. */
@DisabledOnOs(OS.WINDOWS) // symbolic links
class InstallerTest {

  @TempDir Path tmp;
  Path home;
  Path self;
  final Map<String, String> env = new HashMap<>();
  final ByteArrayOutputStream out = new ByteArrayOutputStream();
  final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectories(tmp.resolve("home"));
    self = tmp.resolve("unpacked/bin/m65");
    Files.createDirectories(self.getParent());
    Files.writeString(self, "the m65 binary");
    env.put("HOME", home.toString());
    env.put("PATH", "/usr/bin:/bin");
    env.put("SHELL", "/bin/bash");
  }

  Installer installer(boolean bashCompletion) {
    return new Installer(self, env, false, false, bashCompletion, new PrintStream(out), new PrintStream(err));
  }

  Path bin() {
    return home.resolve(".local/bin");
  }

  @Test
  void installsBinaryLinksAndCompletionAndRemovesThemAgain() throws IOException {
    Installer i = installer(true);
    assertEquals(bin(), i.defaultDir());
    assertEquals(0, i.install(bin(), true));

    assertEquals("the m65 binary", Files.readString(bin().resolve("m65")));
    assertTrue(Files.isExecutable(bin().resolve("m65")));
    for (String c : M65fs.COMMANDS) {
      Path l = bin().resolve("m65." + c);
      assertTrue(Files.isSymbolicLink(l), c);
      assertEquals(Path.of("m65"), Files.readSymbolicLink(l));
    }
    Path completions = home.resolve(".local/share/bash-completion/completions");
    assertTrue(Files.readString(completions.resolve("m65")).startsWith(Installer.SCRIPT_MARK));
    assertTrue(Files.readString(completions.resolve("m65")).contains("complete -F _m65_complete m65 m65.ls"));
    assertEquals(Path.of("m65"), Files.readSymbolicLink(completions.resolve("m65.cp")));
    assertTrue(out.toString().contains("is not on your PATH yet"), out.toString());
    // the command alone on its line, unindented, then "-----" and the explanation
    assertTrue(out.toString().contains("\n\necho 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc\n-----\nThen open"),
        out.toString());

    Files.writeString(bin().resolve("other-tool"), "not ours");
    assertEquals(0, i.uninstall(bin()));
    try (var s = Files.list(bin())) {
      assertEquals(List.of("other-tool"), s.map(p -> p.getFileName().toString()).toList());
    }
    try (var s = Files.list(completions)) {
      assertEquals(0, s.count());
    }
    assertTrue(Files.exists(self), "the unpacked binary stays");
  }

  @Test
  void noPathHintWhenOnThePath() throws IOException {
    env.put("PATH", "/usr/bin:" + bin());
    installer(true).install(bin(), false);
    assertFalse(out.toString().contains("PATH"), out.toString());
  }

  @Test
  void zshOnMacGetsTheProfileHint() throws IOException {
    env.put("SHELL", "/bin/zsh");
    new Installer(self, env, false, true, false, new PrintStream(out), new PrintStream(err)).install(bin(), false);
    assertTrue(out.toString().contains(">> ~/.zprofile"), out.toString());
  }

  @Test
  void withoutBashCompletionALineInBashrc() throws IOException {
    Files.writeString(home.resolve(".bashrc"), "alias ll='ls -l'\n");
    Installer i = installer(false);
    i.install(bin(), true);
    i.install(bin(), true); // again: still one line
    List<String> rc = Files.readAllLines(home.resolve(".bashrc"));
    assertEquals(2, rc.size(), rc.toString());
    assertEquals("alias ll='ls -l'", rc.getFirst());
    assertTrue(rc.get(1).contains(bin().resolve("m65") + "' completion bash") && rc.get(1).endsWith(Installer.BASHRC_MARK));

    i.uninstall(bin());
    assertEquals(List.of("alias ll='ls -l'"), Files.readAllLines(home.resolve(".bashrc")));
  }

  @Test
  void staleCommandsGoForeignFilesStay() throws IOException {
    Files.createDirectories(bin());
    Files.createSymbolicLink(bin().resolve("m65.split"), Path.of("m65"));  // renamed to m65.dsplit
    Files.writeString(bin().resolve("m65.ls"), "someone else's script");
    Files.createSymbolicLink(bin().resolve("m65.foo"), Path.of("elsewhere"));
    installer(true).install(bin(), false);

    assertFalse(Files.exists(bin().resolve("m65.split"), LinkOption.NOFOLLOW_LINKS));
    assertEquals("someone else's script", Files.readString(bin().resolve("m65.ls")));
    assertTrue(err.toString().contains("m65.ls exists and is not an m65 command"), err.toString());
    assertTrue(Files.isSymbolicLink(bin().resolve("m65.foo")));
    assertTrue(Files.isSymbolicLink(bin().resolve("m65.cp")));
  }

  @Test
  void installingFromTheTargetDirectoryKeepsTheBinary() throws IOException {
    Path dir = self.getParent();
    installer(true).install(dir, false);
    assertTrue(out.toString().contains("runs from " + dir + " already"), out.toString());
    assertEquals("the m65 binary", Files.readString(self));
    assertTrue(Files.isSymbolicLink(dir.resolve("m65.ls")));
  }

  @Test
  void windowsCopiesTheLauncher() throws IOException {
    Path exe = tmp.resolve("win/bin/m65.exe");
    Files.createDirectories(exe.getParent());
    Files.writeString(exe, "the m65.exe binary");
    byte[] launcher = ("MZ...\0" + "m65.exe").getBytes(StandardCharsets.UTF_16LE);
    Files.write(exe.resolveSibling("m65.ls.exe"), launcher); // as in the release zip (the JVM build has none embedded)
    env.put("LOCALAPPDATA", tmp.resolve("local").toString());
    env.put("Path", "C:\\Windows");
    var i = new Installer(exe, env, true, false, false, new PrintStream(out), new PrintStream(err));
    Path dir = i.defaultDir();
    assertEquals(tmp.resolve("local/Programs/m65"), dir);
    i.install(dir, true);

    assertEquals("the m65.exe binary", Files.readString(dir.resolve("m65.exe")));
    for (String c : M65fs.COMMANDS) {
      assertArrayEquals(launcher, Files.readAllBytes(dir.resolve("m65." + c + ".exe")), c);
    }
    assertTrue(out.toString().contains("\n\n[Environment]::SetEnvironmentVariable('Path'"), out.toString());
    assertTrue(out.toString().contains("'User')\n-----\n"), out.toString());
    i.uninstall(dir);
    try (var s = Files.list(dir)) {
      assertEquals(0, s.count());
    }
  }

  @Test
  void bashScriptComesFromTheInstallFiles() {
    String s = Completion.bashScript("9.9.9");
    assertTrue(s.startsWith(Installer.SCRIPT_MARK + " 9.9.9)"), s);
    assertTrue(s.contains("m65.discover\n"), s);
    assertFalse(s.contains("@VERSION@") || s.contains("@COMMANDS@"), s);
  }
}
