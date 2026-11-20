package io.github.paw.m65fs.fat32;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Long-name validation and 8.3 alias generation (VFAT rules, as used by Windows/Linux). */
final class ShortNames {

  static final int MAX_LONG_NAME = 255;
  private static final String INVALID_LONG = "\"*/:<>?\\|";
  private static final String VALID_SHORT_SPECIAL = "$%'-_@~`!(){}^#&";

  private ShortNames() {}

  /**
   * Checks that {@code name} can be stored as a FAT long file name.
   *
   * @throws InvalidPathException if not
   */
  static void validateLongName(String name) {
    if (name.isEmpty() || name.equals(".") || name.equals("..")) {
      throw new InvalidPathException(name, "invalid file name");
    }
    if (name.length() > MAX_LONG_NAME) {
      throw new InvalidPathException(name, "file name longer than " + MAX_LONG_NAME + " characters");
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < 0x20 || INVALID_LONG.indexOf(c) >= 0) {
        throw new InvalidPathException(name, "character not allowed in FAT file names", i);
      }
    }
    char last = name.charAt(name.length() - 1);
    if (last == ' ' || last == '.') {
      throw new InvalidPathException(name, "FAT file names cannot end with a space or dot");
    }
  }

  /**
   * The 11-byte 8.3 form of {@code name} if it is already a valid upper-case 8.3 name (then no
   * long-name entries are needed), otherwise {@code null}.
   */
  static byte[] exact(String name) {
    int dot = name.lastIndexOf('.');
    String base = dot < 0 ? name : name.substring(0, dot);
    String ext = dot < 0 ? "" : name.substring(dot + 1);
    if (base.isEmpty() || base.length() > 8 || ext.length() > 3 || (dot >= 0 && ext.isEmpty())) {
      return null;
    }
    if (!(base + ext).chars().allMatch(c -> isShortChar((char) c) && !Character.isLowerCase(c))) {
      return null;
    }
    return pack(base, ext);
  }

  /**
   * Generates a unique 8.3 alias for a long name: upper-cased, invalid characters replaced by
   * {@code _}, then {@code ~N} numeric tails until it does not collide.
   *
   * @param taken tests whether an 11-byte short name already exists in the directory
   */
  static byte[] alias(String longName, Predicate<byte[]> taken) {
    String upper = longName.toUpperCase(Locale.ROOT);
    // strip leading dots and all spaces
    String stripped = upper.replace(" ", "").replaceFirst("^\\.+", "");
    boolean lossy = !stripped.equals(upper);
    int dot = stripped.lastIndexOf('.');
    String rawBase = dot < 0 ? stripped : stripped.substring(0, dot);
    String rawExt = dot < 0 ? "" : stripped.substring(dot + 1);
    StringBuilder base = new StringBuilder();
    for (char c : rawBase.replace(".", "").toCharArray()) {
      base.append(isShortChar(c) ? c : '_');
    }
    lossy |= rawBase.contains(".") || !base.toString().equals(rawBase.replace(".", ""));
    StringBuilder ext = new StringBuilder();
    for (char c : rawExt.toCharArray()) {
      ext.append(isShortChar(c) ? c : '_');
    }
    lossy |= !ext.toString().equals(rawExt);
    if (base.length() > 8 || ext.length() > 3) {
      lossy = true;
    }
    String b = base.length() > 8 ? base.substring(0, 8) : base.toString();
    String e = ext.length() > 3 ? ext.substring(0, 3) : ext.toString();
    if (b.isEmpty()) {
      b = "_";
      lossy = true;
    }
    if (!lossy) {
      byte[] candidate = pack(b, e);
      if (!taken.test(candidate)) {
        return candidate;
      }
    }
    for (int n = 1; n < 1_000_000; n++) {
      String tail = "~" + n;
      String tb = b.length() + tail.length() > 8 ? b.substring(0, 8 - tail.length()) : b;
      byte[] candidate = pack(tb + tail, e);
      if (!taken.test(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no free 8.3 alias for " + longName);
  }

  static boolean isShortChar(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || VALID_SHORT_SPECIAL.indexOf(c) >= 0;
  }

  private static byte[] pack(String base, String ext) {
    byte[] raw = new byte[11];
    Arrays.fill(raw, (byte) ' ');
    byte[] b = base.getBytes(StandardCharsets.US_ASCII);
    byte[] e = ext.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(b, 0, raw, 0, b.length);
    System.arraycopy(e, 0, raw, 8, e.length);
    if ((raw[0] & 0xff) == DirectoryParser.DELETED) {
      raw[0] = 0x05;
    }
    return raw;
  }

  /** Set-based collision test helper. */
  static Predicate<byte[]> in(Set<String> existing11) {
    return raw -> existing11.contains(new String(raw, StandardCharsets.ISO_8859_1));
  }
}
