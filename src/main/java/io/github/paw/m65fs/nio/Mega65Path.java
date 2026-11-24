package io.github.paw.m65fs.nio;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A path on the MEGA65 SD card. Separator {@code /}; relative paths are resolved against the
 * file system's {@linkplain Mega65FileSystem#workingDirectory() working directory}.
 * Comparison is case-insensitive, like FAT name lookup.
 */
record Mega65Path(Mega65FileSystem fileSystem, boolean absolute, List<String> names) implements Path {

  Mega65Path {
    Objects.requireNonNull(fileSystem);
    names = List.copyOf(names);
  }

  /** Parses {@code first/more...}; empty components are ignored. */
  static Mega65Path parse(Mega65FileSystem fs, String first, String... more) {
    StringBuilder sb = new StringBuilder(first);
    for (String m : more) {
      if (!m.isEmpty()) {
        if (!sb.isEmpty()) {
          sb.append('/');
        }
        sb.append(m);
      }
    }
    String s = sb.toString();
    int nul = s.indexOf('\0');
    if (nul >= 0) {
      throw new InvalidPathException(s, "NUL character not allowed", nul);
    }
    List<String> names = Arrays.stream(s.split("/")).filter(n -> !n.isEmpty()).toList();
    return new Mega65Path(fs, s.startsWith("/"), names);
  }

  static Mega65Path cast(Path p) {
    if (p instanceof Mega65Path m) {
      return m;
    }
    throw new ProviderMismatchException(String.valueOf(p));
  }

  private Mega65Path of(boolean abs, List<String> n) {
    return new Mega65Path(fileSystem, abs, n);
  }

  @Override
  public Mega65FileSystem getFileSystem() {
    return fileSystem;
  }

  @Override
  public boolean isAbsolute() {
    return absolute;
  }

  @Override
  public Path getRoot() {
    return absolute ? of(true, List.of()) : null;
  }

  @Override
  public Path getFileName() {
    return names.isEmpty() ? null : of(false, List.of(names.getLast()));
  }

  @Override
  public Path getParent() {
    if (names.isEmpty()) {
      return null;
    }
    if (names.size() == 1) {
      return absolute ? of(true, List.of()) : null;
    }
    return of(absolute, names.subList(0, names.size() - 1));
  }

  @Override
  public int getNameCount() {
    return names.size();
  }

  @Override
  public Path getName(int index) {
    if (index < 0 || index >= names.size()) {
      throw new IllegalArgumentException("index " + index);
    }
    return of(false, List.of(names.get(index)));
  }

  @Override
  public Path subpath(int beginIndex, int endIndex) {
    if (beginIndex < 0 || endIndex > names.size() || beginIndex >= endIndex) {
      throw new IllegalArgumentException(beginIndex + ".." + endIndex);
    }
    return of(false, names.subList(beginIndex, endIndex));
  }

  @Override
  public boolean startsWith(Path other) {
    if (!(other instanceof Mega65Path o) || o.fileSystem != fileSystem || o.absolute != absolute
        || o.names.size() > names.size()) {
      return false;
    }
    return namesEqual(names.subList(0, o.names.size()), o.names);
  }

  @Override
  public boolean endsWith(Path other) {
    if (!(other instanceof Mega65Path o) || o.fileSystem != fileSystem) {
      return false;
    }
    if (o.absolute) {
      return equals(o);
    }
    if (o.names.size() > names.size() || o.names.isEmpty()) {
      return o.names.isEmpty() && names.isEmpty() && !absolute;
    }
    return namesEqual(names.subList(names.size() - o.names.size(), names.size()), o.names);
  }

  @Override
  public Mega65Path normalize() {
    List<String> out = new ArrayList<>();
    for (String n : names) {
      if (n.equals(".")) {
        continue;
      }
      if (n.equals("..")) {
        if (!out.isEmpty() && !out.getLast().equals("..")) {
          out.removeLast();
          continue;
        }
        if (absolute) {
          continue; // ".." at the root stays at the root
        }
      }
      out.add(n);
    }
    return of(absolute, out);
  }

  @Override
  public Path resolve(Path other) {
    Mega65Path o = cast(other);
    if (o.absolute) {
      return o;
    }
    if (o.names.isEmpty()) {
      return this;
    }
    List<String> n = new ArrayList<>(names);
    n.addAll(o.names);
    return of(absolute, n);
  }

  @Override
  public Path relativize(Path other) {
    Mega65Path o = cast(other);
    if (o.absolute != absolute) {
      throw new IllegalArgumentException("cannot relativize between absolute and relative paths");
    }
    Mega65Path a = normalize();
    Mega65Path b = o.normalize();
    int common = 0;
    while (common < a.names.size() && common < b.names.size()
        && a.names.get(common).equalsIgnoreCase(b.names.get(common))) {
      common++;
    }
    List<String> n = new ArrayList<>();
    for (int i = common; i < a.names.size(); i++) {
      n.add("..");
    }
    n.addAll(b.names.subList(common, b.names.size()));
    return of(false, n);
  }

  @Override
  public URI toUri() {
    return fileSystem.uriFor(toAbsolutePath().normalize());
  }

  @Override
  public Mega65Path toAbsolutePath() {
    if (absolute) {
      return this;
    }
    List<String> n = new ArrayList<>(((Mega65Path) fileSystem.workingDirectory()).names);
    n.addAll(names);
    return of(true, n);
  }

  /** The existing file's path with names spelled as stored on the card. */
  @Override
  public Path toRealPath(LinkOption... options) throws IOException {
    return fileSystem.realPath(toAbsolutePath().normalize());
  }

  @Override
  public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
    throw new UnsupportedOperationException("watch service not supported");
  }

  @Override
  public int compareTo(Path other) {
    return String.CASE_INSENSITIVE_ORDER.compare(toString(), cast(other).toString());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Mega65Path p && p.fileSystem == fileSystem && p.absolute == absolute
        && p.names.size() == names.size() && namesEqual(names, p.names);
  }

  @Override
  public int hashCode() {
    int h = Boolean.hashCode(absolute);
    for (String n : names) {
      for (int i = 0; i < n.length(); i++) {
        h = 31 * h + Character.toLowerCase(Character.toUpperCase(n.charAt(i)));
      }
      h = 31 * h + '/';
    }
    return h;
  }

  @Override
  public String toString() {
    String joined = String.join("/", names);
    return absolute ? "/" + joined : joined;
  }

  private static boolean namesEqual(List<String> a, List<String> b) {
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).equalsIgnoreCase(b.get(i))) {
        return false;
      }
    }
    return true;
  }
}
