package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SplitterTest {

  static List<String> names(String letters, int each) {
    List<String> n = new ArrayList<>();
    for (char c : letters.toCharArray()) {
      for (int i = 0; i < each; i++) {
        n.add(c + "ame " + i + ".prg");
      }
    }
    return n;
  }

  static List<String> summary(List<Splitter.Group> plan) {
    return plan.stream().map(g -> g.name() + ":" + g.files().size()).toList();
  }

  @Test
  void consecutiveLettersAreMergedIntoEvenGroupsUpToMax() {
    List<String> n = names("ABCDEFG", 3);
    n.add("1st.prg");
    n.add("@home.prg");
    assertEquals(List.of("0-B:8", "C-E:9", "F-G:6"), summary(Splitter.plan(n, 11)));
  }

  @Test
  void caseDoesNotMatter() {
    var plan = Splitter.plan(List.of("apple", "Avocado", "banana", "Berry"), 2);
    assertEquals(List.of("A:2", "B:2"), summary(plan));
    assertEquals(List.of("apple", "Avocado"), plan.getFirst().files());
  }

  @Test
  void aLetterWithTooManyFilesIsDividedByTheNextOne() {
    List<String> n = new ArrayList<>(names("R", 2));
    for (char c : "abcdefghij".toCharArray()) {
      for (int i = 0; i < 3; i++) {
        n.add("S" + c + "x" + i);
      }
    }
    n.add("S 1"); // space: '0', before the letters
    n.addAll(names("T", 2));
    assertEquals(List.of("R:2", "S0-Sb:7", "Sc-Se:9", "Sf-Sh:9", "Si-Sj:6", "T:2"),
        summary(Splitter.plan(n, 10)));
  }

  @Test
  void everyFileIsPlannedOnceAndNoGroupIsTooLarge() {
    List<String> n = new ArrayList<>();
    for (int i = 0; i < 1380; i++) {
      n.add(Integer.toString(i * 7919 % 100000, 36) + " tune.prg");
    }
    var plan = Splitter.plan(n, 250);
    assertEquals(1380, plan.stream().mapToInt(g -> g.files().size()).sum());
    assertTrue(plan.stream().allMatch(g -> g.files().size() <= 250), summary(plan).toString());
    assertEquals(plan.size(), plan.stream().map(Splitter.Group::name).distinct().count(), "unique names");
  }
}
