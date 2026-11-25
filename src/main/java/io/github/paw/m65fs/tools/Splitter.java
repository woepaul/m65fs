package io.github.paw.m65fs.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans {@code m65.split}: the files of a directory into groups of at most {@code max} by the
 * start of their names, e.g. {@code 0-C}, {@code D-G}, {@code R}; a first letter with more files
 * than {@code max} is divided by the second letter ({@code Sa-Sd}), and so on. As few groups as
 * {@code max} allows, as evenly filled as the prefixes allow. Letters are
 * compared ignoring case; everything else (digits, spaces, punctuation) counts as {@code 0},
 * before the letters.
 */
final class Splitter {

  /** A subdirectory to create and the files that go into it (in name order). */
  record Group(String name, List<String> files) {}

  /** Prefixes longer than this are not tried: such a group stays larger than {@code max}. */
  static final int MAX_PREFIX = 8;

  private Splitter() {}

  static List<Group> plan(List<String> names, int max) {
    if (max < 1) {
      throw new IllegalArgumentException("max must be at least 1: " + max);
    }
    List<Group> groups = new ArrayList<>();
    plan(names, 1, max, groups);
    return groups;
  }

  private static void plan(List<String> names, int depth, int max, List<Group> groups) {
    TreeMap<String, List<String>> buckets = new TreeMap<>();
    for (String n : names) {
      buckets.computeIfAbsent(key(n, depth), k -> new ArrayList<>()).add(n);
    }
    buckets.values().forEach(l -> l.sort(String.CASE_INSENSITIVE_ORDER));
    // as few groups as max allows, then the smallest capacity that still needs no more groups:
    // as few as possible, as even as possible
    int fewest = fill(buckets, depth, max, max, null);
    int lo = 1;
    int hi = max;
    while (lo < hi) {
      int mid = (lo + hi) / 2;
      if (fill(buckets, depth, max, mid, null) <= fewest) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    fill(buckets, depth, max, lo, groups);
  }

  /**
   * Groups consecutive prefixes up to {@code capacity} files (a prefix with more than
   * {@code max} is divided further, by the next character); returns the number of groups made
   * at this level and adds all groups to {@code groups} unless that is null (counting only).
   */
  private static int fill(TreeMap<String, List<String>> buckets, int depth, int max, int capacity,
      List<Group> groups) {
    int made = 0;
    String first = null;
    String last = null;
    List<String> run = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
      List<String> files = e.getValue();
      boolean divide = files.size() > max && depth < MAX_PREFIX;
      if (!run.isEmpty() && (divide || run.size() + files.size() > capacity)) {
        made++;
        if (groups != null) {
          groups.add(group(first, last, run));
        }
        run = new ArrayList<>();
      }
      if (divide) {
        if (groups != null) {
          plan(files, depth + 1, max, groups);
        }
        continue;
      }
      if (run.isEmpty()) {
        first = e.getKey();
      }
      last = e.getKey();
      run.addAll(files);
    }
    if (!run.isEmpty()) {
      made++;
      if (groups != null) {
        groups.add(group(first, last, run));
      }
    }
    return made;
  }

  /** The first {@code depth} characters, normalised: letters lower case, anything else '0'. */
  static String key(String name, int depth) {
    StringBuilder sb = new StringBuilder(depth);
    for (int i = 0; i < depth; i++) {
      char c = i < name.length() ? Character.toLowerCase(name.charAt(i)) : '0';
      sb.append(c >= 'a' && c <= 'z' ? c : '0');
    }
    return sb.toString();
  }

  private static Group group(String first, String last, List<String> files) {
    String name = first.equals(last) ? display(first) : display(first) + "-" + display(last);
    return new Group(name, files);
  }

  /** "sa" -> "Sa", "0" -> "0". */
  private static String display(String key) {
    return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
  }
}
