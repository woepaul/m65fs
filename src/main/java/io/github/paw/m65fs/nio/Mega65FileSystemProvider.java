package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * NIO provider for the scheme {@value #SCHEME}.
 *
 * <pre>{@code
 * try (FileSystem fs = FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())) {
 *   Files.list(fs.getPath("/")).forEach(System.out::println);
 *   Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"));
 * }
 * }</pre>
 *
 * The URI host is {@code auto} (discover the MEGA65 on the local link) or a bracketed IPv6
 * address with zone, e.g. {@code mega65://[fe80::4065:1ff:fe00:1%eth0]/} (the RFC 6874
 * spelling {@code %25eth0} is accepted too). See {@link Env}
 * for the supported {@code env} keys; {@code imageFile} uses a local image instead.
 * {@link #newFileSystem(Path, Map)} opens an image file directly.
 */
public final class Mega65FileSystemProvider extends FileSystemProvider {

  public static final String SCHEME = "mega65";

  private final Map<String, Mega65FileSystem> fileSystems = new HashMap<>();

  @Override
  public String getScheme() {
    return SCHEME;
  }

  // ------------------------------------------------------------------ file systems

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    String key = key(uri);
    synchronized (fileSystems) {
      if (fileSystems.containsKey(key)) {
        throw new FileSystemAlreadyExistsException(uri.toString());
      }
      Mega65FileSystem fs = Mega65FileSystem.open(this, key, authority(uri), new Env(env));
      fileSystems.put(key, fs);
      return fs;
    }
  }

  /** Opens an SD card image or bare FAT32 image file (not registered for {@link #getFileSystem}). */
  @Override
  public FileSystem newFileSystem(Path image, Map<String, ?> env) throws IOException {
    Map<String, Object> e = new HashMap<>(env == null ? Map.of() : env);
    e.put("imageFile", image);
    return Mega65FileSystem.open(this, null, "image", new Env(e));
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    synchronized (fileSystems) {
      Mega65FileSystem fs = fileSystems.get(key(uri));
      if (fs == null) {
        throw new FileSystemNotFoundException(uri.toString());
      }
      return fs;
    }
  }

  @Override
  public Path getPath(URI uri) {
    String p = uri.getPath();
    return getFileSystem(uri).getPath(p == null || p.isEmpty() ? "/" : p);
  }

  void removed(String key, Mega65FileSystem fs) {
    if (key != null) {
      synchronized (fileSystems) {
        fileSystems.remove(key, fs);
      }
    }
  }

  private static String key(URI uri) {
    return authority(uri).toLowerCase(java.util.Locale.ROOT);
  }

  /** The URI's authority; {@code auto} if absent; an RFC 6874 zone {@code %25if} becomes {@code %if}. */
  static String authority(URI uri) {
    if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("URI scheme is not '" + SCHEME + "': " + uri);
    }
    String a = uri.getAuthority();
    if (a == null || a.isEmpty()) {
      return "auto";
    }
    return a.startsWith("[") ? a.replace("%25", "%") : a;
  }

  // ------------------------------------------------------------------ content

  @Override
  public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    Mega65Path p = Mega65Path.cast(path).toAbsolutePath().normalize();
    Mega65FileSystem fs = p.getFileSystem();
    fs.ensureOpen();
    boolean append = options.contains(StandardOpenOption.APPEND);
    boolean write = append || options.contains(StandardOpenOption.WRITE);
    boolean read = options.contains(StandardOpenOption.READ) || !write;
    if (append && options.contains(StandardOpenOption.READ)) {
      throw new IllegalArgumentException("READ + APPEND not allowed");
    }
    if (append && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
      throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
    }
    if (attrs.length > 0) {
      throw new UnsupportedOperationException("initial file attributes are not supported");
    }
    var existing = fs.entry(p);
    if (existing.isPresent() && existing.get().isDirectory()) {
      throw new FileSystemException(p.toString(), null, "is a directory");
    }
    if (!write) {
      return new Channels.ReadChannel(fs, existing.orElseThrow(() -> new NoSuchFileException(p.toString())));
    }
    if (fs.isReadOnly()) {
      throw new ReadOnlyFileSystemException();
    }
    DirEntry entry;
    boolean created = false;
    if (existing.isPresent()) {
      if (options.contains(StandardOpenOption.CREATE_NEW)) {
        throw new FileAlreadyExistsException(p.toString());
      }
      entry = existing.get();
      if (entry.isReadOnly()) {
        throw new AccessDeniedException(p.toString(), null, "read-only attribute set");
      }
    } else {
      if (!options.contains(StandardOpenOption.CREATE) && !options.contains(StandardOpenOption.CREATE_NEW)) {
        throw new NoSuchFileException(p.toString());
      }
      entry = fs.volume().createFile(parentDirectory(p), p.names().getLast());
      created = true;
    }
    boolean truncate = created || options.contains(StandardOpenOption.TRUNCATE_EXISTING);
    return new Channels.WriteChannel(fs, p, entry, truncate, read && options.contains(StandardOpenOption.READ),
        append, options.contains(StandardOpenOption.DELETE_ON_CLOSE));
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
      throws IOException {
    Mega65Path p = Mega65Path.cast(dir);
    Mega65FileSystem fs = p.getFileSystem();
    DirEntry d = fs.existing(p);
    if (!d.isDirectory()) {
      throw new NotDirectoryException(p.toString());
    }
    List<Path> children = new ArrayList<>();
    for (DirEntry e : fs.volume().list(d)) {
      Path child = p.resolve(fs.getPath(e.name()));
      if (filter == null || filter.accept(child)) {
        children.add(child);
      }
    }
    return new DirectoryStream<>() {
      private boolean iterated;
      private boolean closed;

      @Override
      public Iterator<Path> iterator() {
        if (iterated || closed) {
          throw new IllegalStateException(closed ? "stream closed" : "iterator already obtained");
        }
        iterated = true;
        Iterator<Path> it = children.iterator();
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            return !closed && it.hasNext();
          }

          @Override
          public Path next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            return it.next();
          }
        };
      }

      @Override
      public void close() {
        closed = true;
      }
    };
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    Mega65Path p = Mega65Path.cast(dir).toAbsolutePath().normalize();
    if (attrs.length > 0) {
      throw new UnsupportedOperationException("initial file attributes are not supported");
    }
    if (p.names().isEmpty()) {
      throw new FileAlreadyExistsException("/");
    }
    p.getFileSystem().volume().createDirectory(parentDirectory(p), p.names().getLast());
  }

  @Override
  public void delete(Path path) throws IOException {
    Mega65Path p = Mega65Path.cast(path).toAbsolutePath().normalize();
    if (p.names().isEmpty()) {
      throw new FileSystemException("/", null, "cannot delete the root directory");
    }
    Mega65FileSystem fs = p.getFileSystem();
    fs.volume().delete(fs.existing(p));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    Mega65Path src = Mega65Path.cast(source).toAbsolutePath().normalize();
    Mega65Path dst = Mega65Path.cast(target).toAbsolutePath().normalize();
    Mega65FileSystem fs = src.getFileSystem();
    if (dst.getFileSystem() != fs) {
      throw new java.nio.file.ProviderMismatchException("copy between different MEGA65 file systems");
    }
    boolean replace = false;
    boolean copyAttributes = false;
    for (CopyOption o : options) {
      if (o == StandardCopyOption.REPLACE_EXISTING) {
        replace = true;
      } else if (o == StandardCopyOption.COPY_ATTRIBUTES) {
        copyAttributes = true;
      } else if (o != LinkOption.NOFOLLOW_LINKS) {
        throw new UnsupportedOperationException("copy option " + o);
      }
    }
    Fat32Volume vol = fs.volume();
    DirEntry from = fs.existing(src);
    if (src.equals(dst)) {
      return;
    }
    var existing = fs.entry(dst);
    if (existing.isPresent()) {
      if (!replace) {
        throw new FileAlreadyExistsException(dst.toString());
      }
      deleteForReplace(vol, existing.get(), dst);
    }
    DirEntry parent = parentDirectory(dst);
    DirEntry copy;
    if (from.isDirectory()) {
      copy = vol.createDirectory(parent, dst.names().getLast());
    } else {
      copy = vol.createFile(parent, dst.names().getLast());
      copy = vol.writeContent(copy, Channels.reader(vol, from), from.size());
    }
    if (copyAttributes) {
      vol.setTimes(copy, from.created(), from.modified(), from.accessed() == null ? null : from.accessed().atStartOfDay());
      vol.setAttributes(copy, from.attributes());
    }
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    Mega65Path src = Mega65Path.cast(source).toAbsolutePath().normalize();
    Mega65Path dst = Mega65Path.cast(target).toAbsolutePath().normalize();
    Mega65FileSystem fs = src.getFileSystem();
    if (dst.getFileSystem() != fs) {
      throw new java.nio.file.ProviderMismatchException("move between different MEGA65 file systems");
    }
    boolean replace = false;
    for (CopyOption o : options) {
      if (o == StandardCopyOption.ATOMIC_MOVE) {
        throw new AtomicMoveNotSupportedException(src.toString(), dst.toString(),
            "FAT moves write the new entry before removing the old one");
      }
      if (o == StandardCopyOption.REPLACE_EXISTING) {
        replace = true;
      }
    }
    if (src.names().isEmpty()) {
      throw new FileSystemException("/", null, "cannot move the root directory");
    }
    Fat32Volume vol = fs.volume();
    DirEntry from = fs.existing(src);
    DirEntry parent = parentDirectory(dst);
    var existing = fs.entry(dst);
    if (existing.isPresent() && !existing.get().location().equals(from.location())) {
      if (!replace) {
        throw new FileAlreadyExistsException(dst.toString());
      }
      deleteForReplace(vol, existing.get(), dst);
      parent = parentDirectory(dst); // may have changed by the delete
    }
    if (src.equals(dst) && src.names().getLast().equals(dst.names().getLast())) {
      return;
    }
    vol.move(from, parent, dst.names().getLast());
  }

  private static void deleteForReplace(Fat32Volume vol, DirEntry existing, Mega65Path dst) throws IOException {
    if (existing.isDirectory() && !vol.list(existing).isEmpty()) {
      throw new DirectoryNotEmptyException(dst.toString());
    }
    vol.delete(existing);
  }

  /** The directory entry of {@code p}'s parent; it must exist and be a directory. */
  private static DirEntry parentDirectory(Mega65Path p) throws IOException {
    Mega65Path parent = (Mega65Path) p.getParent();
    if (parent == null) {
      throw new FileSystemException(p.toString(), null, "no parent directory");
    }
    DirEntry d = p.getFileSystem().existing(parent);
    if (!d.isDirectory()) {
      throw new NotDirectoryException(parent.toString());
    }
    return d;
  }

  // ------------------------------------------------------------------ queries

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    if (!(path2 instanceof Mega65Path p2) || p2.getFileSystem() != Mega65Path.cast(path).getFileSystem()) {
      return false;
    }
    Mega65Path p1 = Mega65Path.cast(path);
    if (p1.toAbsolutePath().normalize().equals(p2.toAbsolutePath().normalize())) {
      return true;
    }
    return p1.getFileSystem().existing(p1).location().equals(p2.getFileSystem().existing(p2).location());
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    Mega65Path p = Mega65Path.cast(path);
    return p.getFileSystem().existing(p).isHidden();
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    Mega65Path p = Mega65Path.cast(path);
    p.getFileSystem().existing(p);
    return p.getFileSystem().fileStore();
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    Mega65Path p = Mega65Path.cast(path);
    DirEntry e = p.getFileSystem().existing(p);
    for (AccessMode m : modes) {
      if (m == AccessMode.WRITE) {
        if (p.getFileSystem().isReadOnly()) {
          throw new AccessDeniedException(p.toString(), null, "file system is read-only");
        }
        if (e.isReadOnly() && !e.isDirectory()) {
          throw new AccessDeniedException(p.toString(), null, "read-only attribute set");
        }
      }
    }
  }

  // ------------------------------------------------------------------ attributes

  @Override
  @SuppressWarnings("unchecked")
  public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
    Mega65Path p = Mega65Path.cast(path);
    if (type == BasicFileAttributeView.class || type == DosFileAttributeView.class
        || type == Mega65FileAttributeView.class) {
      return (V) new View(p);
    }
    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
      throws IOException {
    if (type.isAssignableFrom(FatAttributes.class)) {
      return (A) attributes(Mega65Path.cast(path));
    }
    throw new UnsupportedOperationException("attributes " + type.getName());
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
    int colon = attributes.indexOf(':');
    String view = colon < 0 ? "basic" : attributes.substring(0, colon);
    String names = colon < 0 ? attributes : attributes.substring(colon + 1);
    if (!Mega65FileSystem.VIEWS.contains(view)) {
      throw new UnsupportedOperationException("attribute view " + view);
    }
    return attributes(Mega65Path.cast(path)).toMap(view, names);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
    int colon = attribute.indexOf(':');
    String view = colon < 0 ? "basic" : attribute.substring(0, colon);
    String name = colon < 0 ? attribute : attribute.substring(colon + 1);
    if (!Mega65FileSystem.VIEWS.contains(view)) {
      throw new UnsupportedOperationException("attribute view " + view);
    }
    View v = new View(Mega65Path.cast(path));
    switch (name) {
      case "lastModifiedTime" -> v.setTimes((FileTime) value, null, null);
      case "lastAccessTime" -> v.setTimes(null, (FileTime) value, null);
      case "creationTime" -> v.setTimes(null, null, (FileTime) value);
      case "readonly", "hidden", "system", "archive" -> {
        if (view.equals("basic")) {
          throw new IllegalArgumentException("'" + name + "' is not a basic attribute");
        }
        v.setFlag(name, (Boolean) value);
      }
      default -> throw new IllegalArgumentException("cannot set attribute " + attribute);
    }
  }

  private static FatAttributes attributes(Mega65Path p) throws IOException {
    Mega65FileSystem fs = p.getFileSystem();
    return new FatAttributes(fs.existing(p), fs.volume(), fs.zone());
  }

  /** Basic, DOS and MEGA65 attribute view in one. */
  private static final class View implements Mega65FileAttributeView {
    private final Mega65Path path;

    View(Mega65Path path) {
      this.path = path;
    }

    @Override
    public Mega65FileAttributes readAttributes() throws IOException {
      return attributes(path);
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
      Mega65FileSystem fs = path.getFileSystem();
      DirEntry e = fs.existing(path);
      if (e.location().firstSlot() < 0) {
        return; // the root directory has no timestamps
      }
      fs.volume().setTimes(e, local(createTime, fs), local(lastModifiedTime, fs), local(lastAccessTime, fs));
    }

    void setFlag(String name, boolean on) throws IOException {
      Mega65FileSystem fs = path.getFileSystem();
      DirEntry e = fs.existing(path);
      int bit = switch (name) {
        case "readonly" -> DirEntry.ATTR_READ_ONLY;
        case "hidden" -> DirEntry.ATTR_HIDDEN;
        case "system" -> DirEntry.ATTR_SYSTEM;
        default -> DirEntry.ATTR_ARCHIVE;
      };
      fs.volume().setAttributes(e, on ? e.attributes() | bit : e.attributes() & ~bit);
    }

    @Override
    public void setReadOnly(boolean value) throws IOException {
      setFlag("readonly", value);
    }

    @Override
    public void setHidden(boolean value) throws IOException {
      setFlag("hidden", value);
    }

    @Override
    public void setSystem(boolean value) throws IOException {
      setFlag("system", value);
    }

    @Override
    public void setArchive(boolean value) throws IOException {
      setFlag("archive", value);
    }

    private static LocalDateTime local(FileTime t, Mega65FileSystem fs) {
      return t == null ? null : LocalDateTime.ofInstant(t.toInstant(), fs.zone());
    }
  }
}
