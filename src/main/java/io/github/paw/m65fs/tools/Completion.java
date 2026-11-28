package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tab completion for the m65 tools ({@code m65 __complete LINE WORDBREAKS}, called by the bash
 * script of {@code m65 completion bash}). Decides everything the shell script would otherwise
 * have to know: which tool, which word, and whether that word is a card path, a local path, an
 * option or an option's value.
 *
 * <p>A word is completed as a card path where the tool would read it as one: {@code m65:PATH}
 * everywhere, plain {@code PATH} only in tools that use the card alone; where the tool reads a
 * plain word as a local path ({@code cp}, {@code boot}, {@code run}, an existing local directory
 * for {@code dsplit}), bash completes it locally.
 *
 * <p>The answer is its kind on the first line, then the words: {@code words} (replace the word
 * with one of these, already quoted for bash; a directory ends with "/"), {@code files} or
 * {@code dirs} (let bash complete local files or directories) or {@code none}.
 */
final class Completion {

  /** A directory entry on the card. */
  record Entry(String name, boolean dir) {}

  /** The card's directories, read only when a card path is completed. */
  interface Card {
    /** The entries of {@code dir} ("" the working directory, "/x/" or "x/"). */
    List<Entry> list(String dir) throws IOException;
  }

  record Answer(String kind, List<String> words) {
    static final Answer NONE = new Answer("none", List.of());
    static final Answer FILES = new Answer("files", List.of());
    static final Answer DIRS = new Answer("dirs", List.of());

    String text() {
      StringBuilder sb = new StringBuilder(kind).append('\n');
      words.forEach(w -> sb.append(w).append('\n'));
      return sb.toString();
    }
  }

  /** bash's default COMP_WORDBREAKS. */
  static final String WORDBREAKS = " \t\n\"'><=;|&(:";

  /** Options that take a value (the next word). */
  private static final Set<String> VALUE_OPTIONS = Set.of("-L", "-m", "--mode", "--mount", "--jump");

  /** Card-only tools: a plain word is a card path. */
  private static final Set<String> CARD_ALL = Set.of("ls", "rm", "cat", "stat", "mv");
  private static final Set<String> CARD_DIRS = Set.of("cd", "tree", "mkdir", "defrag");

  private final Card card;
  private final String wordbreaks;
  private final boolean ignoreCase;

  /**
   * @param ignoreCase readline's {@code completion-ignore-case}: match names regardless of case
   *     (readline copes with that then); otherwise case-sensitive, as bash completes local files
   */
  Completion(Card card, String wordbreaks, boolean ignoreCase) {
    this.card = card;
    this.wordbreaks = wordbreaks;
    this.ignoreCase = ignoreCase;
  }

  Completion(Card card, String wordbreaks) {
    this(card, wordbreaks, false);
  }

  /** Completes the last word of {@code line} (the command line up to the cursor). */
  Answer complete(String line) {
    Words w = Words.split(line);
    List<String> v = w.values();
    if (v.size() < 2) {
      return Answer.NONE; // still the command name: bash's job
    }
    String command = Path.of(v.getFirst()).getFileName().toString().replaceFirst("(?i)\\.exe$", "");
    int first; // index of the tool's first argument
    String tool;
    if (command.equals("m65")) {
      if (v.size() == 2) {
        List<String> names = new ArrayList<>(M65fs.COMMANDS);
        names.addAll(List.of("completion", "help"));
        return words(names, w.last());
      }
      tool = v.get(1);
      first = 2;
    } else if (command.startsWith("m65.")) {
      tool = command.substring(4);
      first = 1;
    } else {
      return Answer.NONE;
    }
    String cur = w.last();
    List<String> before = v.subList(first, v.size() - 1);
    if (tool.equals("completion")) {
      return before.isEmpty() ? words(List.of("bash"), cur) : Answer.NONE;
    }
    if (!M65fs.COMMANDS.contains(tool)) {
      return Answer.NONE;
    }
    String prev = before.isEmpty() ? "" : before.getLast();
    if (VALUE_OPTIONS.contains(prev)) {
      return switch (prev) {
        case "--mode" -> words(tool.equals("boot") ? List.of("c64", "mega65") : List.of("mega65", "c64"), cur);
        case "--mount" -> card(w, e -> !e.dir() && isImage(e), true);
        default -> Answer.NONE;
      };
    }
    if (cur.startsWith("-") && !before.contains("--")) {
      return words(options(tool), cur);
    }
    boolean onCard = M65fs.isCard(cur);
    if (CARD_ALL.contains(tool)) {
      return card(w, e -> true, false);
    }
    if (CARD_DIRS.contains(tool)) {
      return card(w, Entry::dir, false);
    }
    return switch (tool) {
      case "cp" -> onCard ? card(w, e -> true, false) : Answer.FILES;
      case "boot" -> onCard ? card(w, e -> e.dir() || isImage(e), false) : Answer.FILES;
      case "mount" -> card(w, e -> e.dir() || isImage(e), false);
      case "run" -> onCard ? Answer.NONE : Answer.FILES; // run reads a local file only
      case "dsplit" -> onCard || !localDirMatches(cur) ? card(w, Entry::dir, false) : Answer.DIRS;
      default -> Answer.NONE; // pwd, df, reset, peek, discover: no operands to complete
    };
  }

  private static boolean isImage(Entry e) {
    return e.name().toLowerCase(Locale.ROOT).endsWith(".d81");
  }

  /** Whether a local directory starts with {@code word}: dsplit takes such a word as local. */
  private static boolean localDirMatches(String word) {
    int slash = word.lastIndexOf('/');
    String prefix = word.substring(slash + 1);
    Path parent = Path.of(slash < 0 ? "." : word.substring(0, slash + 1));
    try (Stream<Path> s = Files.list(parent)) {
      return s.anyMatch(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith(prefix));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** The tool's options, from its usage line ({@code [-lRd]}, {@code [-L LEVEL]}, {@code --fix}). */
  static List<String> options(String tool) {
    Set<String> o = new LinkedHashSet<>();
    String usage = M65fs.usageOf(tool).replaceFirst("\\s{2,}\\(.*", ""); // without the remark
    Matcher m = Pattern.compile("\\[-([A-Za-z]+)[] |]").matcher(usage);
    while (m.find()) {
      for (char c : m.group(1).toCharArray()) {
        o.add("-" + c);
      }
    }
    m = Pattern.compile("--[a-z][a-z-]*(=[a-z|]+)?").matcher(usage);
    while (m.find()) {
      if (m.group(1) == null) {
        o.add(m.group());
      } else { // --petscii=lower|upper
        String name = m.group().substring(0, m.group().indexOf('=') + 1);
        for (String v : m.group(1).substring(1).split("\\|")) {
          o.add(name + v);
        }
      }
    }
    o.add("--help");
    return List.copyOf(o);
  }

  /** The candidates that start with {@code cur} (plain words: options, values, commands). */
  private Answer words(List<String> candidates, String cur) {
    int cut = lastBreak(cur) + 1; // bash replaces only the part after "=" in "--petscii=lo"
    return new Answer("words", candidates.stream().filter(c -> c.startsWith(cur)).map(c -> c.substring(cut)).toList());
  }

  /**
   * Completes the last word as a card path ({@code m65:} optional). {@code inRoot}: a word
   * without "/" names an entry of the root directory (run --mount), not of the working one.
   */
  private Answer card(Words w, Predicate<Entry> wanted, boolean inRoot) {
    if (w.quoted()) {
      return Answer.NONE; // (quoted words: not yet; names with spaces complete with backslashes)
    }
    String raw = w.raw();
    String value = w.last();
    String pre = "";
    if (M65fs.isCard(value)) {
      if (!raw.startsWith(M65fs.CARD_PREFIX)) {
        return Answer.NONE;
      }
      pre = M65fs.CARD_PREFIX;
      raw = raw.substring(pre.length());
      value = value.substring(pre.length());
    }
    String dir = value.substring(0, value.lastIndexOf('/') + 1);
    String typedName = value.substring(dir.length());
    String name = typedName.toLowerCase(Locale.ROOT);
    String rawDir = raw.substring(0, raw.lastIndexOf('/') + 1);
    List<Entry> entries;
    try {
      entries = card.list(dir.isEmpty() && inRoot ? "/" : dir);
    } catch (IOException | RuntimeException e) {
      return new Answer("words", List.of()); // no free helper running, no such directory, ...
    }
    // bash replaces only the part after the last word break character (":" in "m65:/x")
    String typed = pre + w.raw().substring(pre.length());
    int cut = lastBreak(typed) + 1;
    List<Entry> matches = entries.stream().filter(e -> !e.name().equals(".") && !e.name().equals("..")
        && e.name().toLowerCase(Locale.ROOT).startsWith(name) && wanted.test(e)).toList();
    if (!ignoreCase) {
      // as bash completes local files: case-sensitive (readline would otherwise cut the word back
      // to the candidates' common prefix: "/c" with c64/ and CORE/ to "/")
      matches = matches.stream().filter(e -> e.name().startsWith(typedName)).toList();
    }
    List<String> out = new ArrayList<>();
    for (Entry e : matches) {
      String c = pre + rawDir + escape(e.name()) + (e.dir() ? "/" : "");
      if (c.startsWith(typed.substring(0, cut))) {
        out.add(c.substring(cut));
      }
    }
    out.sort(String.CASE_INSENSITIVE_ORDER);
    return new Answer("words", out);
  }

  /** The index of the last unescaped word break character, or -1. */
  private int lastBreak(String raw) {
    int k = -1;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\\') {
        i++;
      } else if (wordbreaks.indexOf(c) >= 0) {
        k = i;
      }
    }
    return k;
  }

  private static final String SPECIAL = " \t\n\"'\\$`!&;|<>()*?[]{}#~";

  /** {@code name} with bash's special characters escaped by backslashes. */
  static String escape(String name) {
    StringBuilder sb = new StringBuilder();
    for (char c : name.toCharArray()) {
      if (SPECIAL.indexOf(c) >= 0) {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * The words of a command line as bash splits them: {@code values} without quotes and escapes
   * (the last one is the word being completed, "" after a space), {@code raw} the last word as
   * typed, {@code quoted} whether it has quotes. A new command after {@code ; | &} starts over.
   */
  record Words(List<String> values, String raw, boolean quoted) {
    String last() {
      return values.getLast();
    }

    static Words split(String line) {
      List<String> values = new ArrayList<>();
      StringBuilder value = new StringBuilder();
      int start = 0; // of the current word in line
      boolean inWord = false;
      boolean quoted = false;
      char quote = 0;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (quote == '\'') {
          if (c == '\'') {
            quote = 0;
          } else {
            value.append(c);
          }
          continue;
        }
        if (quote == '"') {
          if (c == '"') {
            quote = 0;
          } else if (c == '\\' && i + 1 < line.length() && "$`\"\\\n".indexOf(line.charAt(i + 1)) >= 0) {
            value.append(line.charAt(++i));
          } else {
            value.append(c);
          }
          continue;
        }
        if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '|' || c == '&') {
          if (inWord) {
            values.add(value.toString());
            value.setLength(0);
            inWord = false;
          }
          if (c == ';' || c == '|' || c == '&') {
            values.clear(); // the next command
          }
          continue;
        }
        if (!inWord) {
          inWord = true;
          start = i;
          quoted = false;
        }
        if (c == '\\') {
          if (i + 1 < line.length()) {
            value.append(line.charAt(++i));
          }
        } else if (c == '\'' || c == '"') {
          quote = c;
          quoted = true;
        } else {
          value.append(c);
        }
      }
      if (!inWord) {
        start = line.length();
        quoted = false;
      }
      values.add(value.toString());
      return new Words(values, line.substring(start), quoted);
    }
  }

  /** The bash script that {@code m65 completion bash} prints (install/m65.bash, filled in). */
  static String bashScript(String version) {
    try {
      byte[] b = InstallFiles.read("m65.bash");
      if (b == null) {
        throw new IllegalStateException("this build has no bash completion script");
      }
      return new String(b, java.nio.charset.StandardCharsets.UTF_8).replace("@VERSION@", version)
          .replace("@COMMANDS@", String.join(" ", M65fs.COMMANDS.stream().map(c -> "m65." + c).toList()));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
