package io.github.paw.m65fs.nio;

import java.util.regex.PatternSyntaxException;

/**
 * Converts {@code glob:} patterns (as documented in {@link java.nio.file.FileSystem#getPathMatcher})
 * to regular expressions. Also used by the m65 tools for wildcards in card paths.
 */
public final class Globs {

  private Globs() {}

  /** As documented for {@code getPathMatcher}: {@code [!a-z]} negates, {@code ^} is literal. */
  public static String toRegex(String glob) {
    return toRegex(glob, false);
  }

  /**
   * {@code shell}: also {@code [^a-z]} negates, as in bash.
   *
   * @throws java.util.regex.PatternSyntaxException for a broken pattern ({@code [abc})
   */
  public static String toRegex(String glob, boolean shell) {
    StringBuilder re = new StringBuilder("^");
    boolean inGroup = false;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '*' -> {
          if (next(glob, i) == '*') {
            re.append(".*");
            i++;
          } else {
            re.append("[^/]*");
          }
        }
        case '?' -> re.append("[^/]");
        case '[' -> i = bracket(glob, i, re, shell);
        case '{' -> {
          if (inGroup) {
            throw new PatternSyntaxException("nested groups not supported", glob, i);
          }
          inGroup = true;
          re.append("(?:");
        }
        case '}' -> {
          re.append(inGroup ? ")" : "\\}");
          inGroup = false;
        }
        case ',' -> re.append(inGroup ? "|" : ",");
        case '\\' -> {
          if (i + 1 >= glob.length()) {
            throw new PatternSyntaxException("trailing escape", glob, i);
          }
          re.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(++i))));
        }
        default -> re.append(isRegexMeta(c) ? "\\" + c : String.valueOf(c));
      }
    }
    if (inGroup) {
      throw new PatternSyntaxException("missing '}'", glob, glob.length());
    }
    return re.append('$').toString();
  }

  /** Translates {@code [...]} starting at {@code start}; returns the index of the closing bracket. */
  private static int bracket(String glob, int start, StringBuilder re, boolean shell) {
    int i = start + 1;
    re.append("(?=[^/])["); // a bracket expression never matches the separator
    if (i < glob.length() && (glob.charAt(i) == '!' || shell && glob.charAt(i) == '^')) {
      re.append('^');
      i++;
    }
    boolean empty = true;
    for (; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == ']' && !empty) {
        re.append(']');
        return i;
      }
      empty = false;
      if (c == '\\' || c == '[' || c == '&' || c == '^') {
        re.append('\\');
      }
      re.append(c);
    }
    throw new PatternSyntaxException("missing ']'", glob, start);
  }

  private static boolean isRegexMeta(char c) {
    return "().+^$|]".indexOf(c) >= 0;
  }

  private static char next(String s, int i) {
    return i + 1 < s.length() ? s.charAt(i + 1) : 0;
  }
}
