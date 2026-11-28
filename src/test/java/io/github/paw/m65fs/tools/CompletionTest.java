package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.fat32.FatImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code m65 __complete} on an SD card image. */
class CompletionTest {

  @TempDir Path tmp;
  String image;

  @BeforeEach
  void card() throws IOException {
    image = FatImages.partitioned(Files.createDirectories(tmp.resolve("img")), 64).file().toString();
    Path local = Files.createDirectories(tmp.resolve("local"));
    Files.write(local.resolve("GAME.D81"), new byte[819_200]);
    Files.write(local.resolve("readme.txt"), "hi".getBytes());
    Files.createDirectories(local.resolve("My Games/Disks"));
    Files.write(local.resolve("My Games/Disks/Disk 1.d81"), new byte[819_200]);
    run("cp", "-r", local + "/GAME.D81", local + "/readme.txt", local + "/My Games", "m65:/");
    run("mkdir", "m65:/C64");
  }

  String run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int s = M65fs.run(args, Map.of("M65_IMAGE", image), new PrintStream(out), new PrintStream(err));
    assertEquals(0, s, err.toString());
    return out.toString();
  }

  /** The answer for {@code line} (the cursor at its end), with bash's default word breaks. */
  List<String> complete(String line) {
    return List.of(run("__complete", line).split("\n"));
  }

  @Test
  void cardPathsInCardOnlyTools() {
    assertEquals(List.of("words", "/C64/", "/GAME.D81", "/My\\ Games/", "/readme.txt"), complete("m65.ls /"));
    assertEquals(List.of("words", "C64/", "GAME.D81", "My\\ Games/", "readme.txt"), complete("m65.ls "));
    assertEquals(List.of("words", "/My\\ Games/"), complete("m65.rm /M"));
    assertEquals(List.of("words", "/My\\ Games/Disks/"), complete("m65.ls /My\\ Games/"));
    // bash splits "m65:/x" at ":" and replaces only the part after it
    assertEquals(List.of("words", "/C64/"), complete("m65.ls m65:/C"));
    // without ":" in the word breaks, the whole word is replaced
    assertEquals("words\nm65:/C64/\n", run("__complete", "m65.ls m65:/C", " \t\n"));
  }

  @Test
  void caseSensitiveUnlessReadlineIgnoresCase() {
    run("mkdir", "m65:/c128", "m65:/CORE");
    // readline would shorten "/c" to the common prefix "/" of c128/, C64/ and CORE/
    assertEquals(List.of("words", "/c128/"), complete("m65.ls /c"));
    assertEquals(List.of("words"), complete("m65.ls /co"));                 // case-sensitive
    assertEquals(List.of("words", "/CORE/"), complete("m65.ls /CO"));
    // with completion-ignore-case on, readline copes: all of them
    assertEquals("words\n/c128/\n/C64/\n/CORE/\n", run("__complete", "m65.ls /c", Completion.WORDBREAKS, "on"));
  }

  @Test
  void directoriesOnlyWhereTheToolWantsThem() {
    assertEquals(List.of("words", "C64/", "My\\ Games/"), complete("m65.cd "));
    assertEquals(List.of("words", "C64/", "My\\ Games/"), complete("m65 tree -L 2 "));
  }

  @Test
  void plainWordsAreLocalWhereTheToolReadsThemAsLocal() {
    assertEquals(List.of("files"), complete("m65.cp "));
    assertEquals(List.of("files"), complete("m65.cp -r rea"));
    assertEquals(List.of("files"), complete("m65.boot "));
    assertEquals(List.of("words", "/C64/", "/GAME.D81", "/My\\ Games/"), complete("m65.boot m65:/"));
    assertEquals(List.of("files"), complete("m65.run "));
    assertEquals(List.of("none"), complete("m65.run m65:/"));
  }

  @Test
  void cpCompletesEverythingOnTheCard() {
    assertEquals(List.of("words", "/C64/", "/GAME.D81", "/My\\ Games/", "/readme.txt"), complete("m65.cp x m65:/"));
  }

  @Test
  void dsplitPrefersAnExistingLocalDirectory() {
    assertEquals(List.of("dirs"), complete("m65.dsplit " + tmp.resolve("loc")));
    assertEquals(List.of("words", "C64/", "My\\ Games/"), complete("m65.dsplit m65:"));
    // no such local directory: a card path (here: not on the card either)
    assertEquals(List.of("words"), complete("m65.dsplit /no-such-dir-m65fs/x"));
  }

  @Test
  void mountValuesAreImagesInTheRoot() {
    assertEquals(List.of("words", "GAME.D81"), complete("m65.run --mount "));
    assertEquals(List.of("words", "c64", "mega65"), complete("m65.boot --mode "));
  }

  @Test
  void options() {
    assertEquals(List.of("words", "-l", "-R", "-d", "--help"), complete("m65.ls -"));
    assertEquals(List.of("words", "--dry-run"), complete("m65 dsplit --d"));
    assertEquals(List.of("words", "--fix", "--help"), complete("m65.defrag --"));
  }

  @Test
  void commandsAfterM65() {
    List<String> c = complete("m65 d");
    assertEquals(List.of("words", "df", "defrag", "dsplit", "discover"), c);
    assertEquals(List.of("words", "bash"), complete("m65 completion "));
  }

  @Test
  void noCardWithoutAnswer() {
    // no image and no known address: nothing, no error, no waiting
    var out = new ByteArrayOutputStream();
    M65fs.run(new String[] {"__complete", "m65.ls /"}, Map.of("XDG_CACHE_HOME", tmp.resolve("none").toString()),
        new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));
    assertEquals("words\n", out.toString());
  }

  @Test
  void wordsAreSplitAsBashDoes() {
    var w = Completion.Words.split("m65.cp 'a b' \"c\\\"d\" e\\ f m65:/My\\ G");
    assertEquals(List.of("m65.cp", "a b", "c\"d", "e f", "m65:/My G"), w.values());
    assertEquals("m65:/My\\ G", w.raw());
    assertEquals(List.of("m65.ls", ""), Completion.Words.split("m65.ls ").values());
    assertEquals(List.of("m65.ls", "x"), Completion.Words.split("true; m65.ls x").values());
    assertTrue(Completion.Words.split("m65.ls 'My G").quoted());
  }

  @Test
  void escaping() {
    assertEquals("My\\ Game\\ \\(1\\)\\&\\$x", Completion.escape("My Game (1)&$x"));
  }

  @Test
  void scriptRegistersEveryCommand() {
    String s = run("completion", "bash");
    assertTrue(s.contains("complete -F _m65_complete m65 m65.ls m65.tree"), s);
    assertTrue(s.contains("m65.discover\n"), s);
  }
}
