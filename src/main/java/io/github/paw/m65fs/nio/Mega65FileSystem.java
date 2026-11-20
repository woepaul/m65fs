package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.block.CachingBlockDevice;
import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.remotesd.RemoteSdBlockDevice;
import io.github.paw.m65fs.remotesd.RemoteSdSession;
import io.github.paw.m65fs.transport.Mega65Locator;
import io.github.paw.m65fs.transport.Target;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The FAT32 partition of a MEGA65 SD card (over Ethernet), or of a local SD card image.
 *
 * <p>Besides the standard {@link FileSystem} API it offers {@link #mountD81(Path)} and
 * {@link #peek(int, int)}.
 *
 * <p><b>When changes reach the card.</b> Every operation ({@code Files.write}, {@code copy},
 * {@code move}, {@code delete}, {@code createDirectory}, attribute changes, closing a write
 * channel) is complete on the SD card when it returns: data, both FATs, FSInfo and the directory
 * entry are written and acknowledged by the MEGA65, and nothing is cached for writing on the PC.
 * The only exception is a write channel that is still open, which keeps its content on the PC
 * until it is closed (so the file can be laid out contiguously). {@link #commit()} also writes
 * those, without closing anything or ending the session.
 *
 * <p>Closing the file system closes the write channels still open (writing their content) and
 * ends the session with the MEGA65, which (unless {@code quitOnClose=false}) resets the machine.
 */
public final class Mega65FileSystem extends FileSystem {

  static final Set<String> VIEWS = Set.of("basic", "dos", "mega65");

  private final Mega65FileSystemProvider provider;
  private final String key;
  private final String authority;
  private final Fat32Volume volume;
  private final RemoteSdSession session;
  private final ZoneId zone;
  private final int shadowMemoryLimit;
  private final Mega65FileStore store;
  private final Set<Channels.WriteChannel> writers = ConcurrentHashMap.newKeySet();
  private volatile boolean open = true;

  private Mega65FileSystem(Mega65FileSystemProvider provider, String key, String authority, Fat32Volume volume,
      RemoteSdSession session, Env env) {
    this.provider = provider;
    this.key = key;
    this.authority = authority;
    this.volume = volume;
    this.session = session;
    this.zone = env.zone();
    this.shadowMemoryLimit = env.integer("shadowMemoryLimit").orElse(Shadow.DEFAULT_MEMORY_LIMIT);
    this.store = new Mega65FileStore(this);
  }

  /** Opens the file system described by {@code authority} ("auto", "[fe80::…%if]") or {@code env}. */
  static Mega65FileSystem open(Mega65FileSystemProvider provider, String key, String authority, Env env)
      throws IOException {
    Optional<Path> image = env.path("imageFile");
    boolean readOnly = env.bool("readOnly", false);
    if (image.isPresent()) {
      BlockDevice dev = ImageFileBlockDevice.open(image.get(), readOnly);
      return withDevice(provider, key, authority, dev, null, env);
    }
    Target target = resolveTarget(authority, env);
    var options = RemoteSdSession.Options.defaults().withQuitOnClose(env.bool("quitOnClose", true));
    options = env.duration("timeout").map(options::withTimeout).orElse(options);
    options = env.integer("writeWindow").map(options::withWriteWindow).orElse(options);
    return attach(provider, key, authority, RemoteSdSession.connect(target, options), env);
  }

  /** Wraps an established session (package-private for tests with a simulated MEGA65). */
  static Mega65FileSystem attach(Mega65FileSystemProvider provider, String key, String authority,
      RemoteSdSession session, Env env) throws IOException {
    BlockDevice dev = new CachingBlockDevice(new RemoteSdBlockDevice(session),
        env.integer("readAhead").orElse(CachingBlockDevice.DEFAULT_READ_AHEAD), CachingBlockDevice.DEFAULT_CAPACITY);
    if (env.bool("readOnly", false)) {
      dev = BlockDevice.readOnly(dev);
    }
    return withDevice(provider, key, authority, dev, session, env);
  }

  private static Mega65FileSystem withDevice(Mega65FileSystemProvider provider, String key, String authority,
      BlockDevice dev, RemoteSdSession session, Env env) throws IOException {
    try {
      Fat32Volume volume = Fat32Volume.open(dev, Clock.system(env.zone()));
      return new Mega65FileSystem(provider, key, authority, volume, session, env);
    } catch (IOException | RuntimeException e) {
      dev.close();
      throw e;
    }
  }

  private static Target resolveTarget(String authority, Env env) throws IOException {
    Optional<String> explicit = env.string("target");
    if (explicit.isPresent()) {
      return Target.parse(explicit.get());
    }
    if (authority == null || authority.isEmpty() || authority.equalsIgnoreCase("auto")) {
      var timeout = env.duration("discoveryTimeout").orElse(Mega65Locator.DEFAULT_TIMEOUT);
      return Mega65Locator.discover(timeout).orElseThrow(() -> new IOException(
          "no MEGA65 found on the local network (remote mode active? DIP switch 2 on or SHIFT+£)"));
    }
    if (authority.startsWith("[") && authority.endsWith("]")) {
      return Target.parse(authority.substring(1, authority.length() - 1));
    }
    throw new IllegalArgumentException("URI host must be 'auto' or '[ipv6%interface]': " + authority);
  }

  // ------------------------------------------------------------------ FileSystem

  @Override
  public Mega65FileSystemProvider provider() {
    return provider;
  }

  @Override
  public void close() throws IOException {
    if (!open) {
      return;
    }
    IOException failure = null;
    try {
      for (var w : List.copyOf(writers)) {
        try {
          w.close();
        } catch (IOException e) {
          failure = addTo(failure, e);
        }
      }
    } finally {
      open = false;
      try {
        volume.close();
      } catch (IOException e) {
        failure = addTo(failure, e);
      } finally {
        provider.removed(key, this);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static IOException addTo(IOException first, IOException next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public boolean isReadOnly() {
    return volume.isReadOnly();
  }

  @Override
  public String getSeparator() {
    return "/";
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return List.of(root());
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    return List.of(store);
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return VIEWS;
  }

  @Override
  public Path getPath(String first, String... more) {
    return Mega65Path.parse(this, first, more);
  }

  /** Supports {@code glob:} and {@code regex:}; matching is case-insensitive like FAT. */
  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    int colon = syntaxAndPattern.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("expected syntax:pattern");
    }
    String syntax = syntaxAndPattern.substring(0, colon);
    String pattern = syntaxAndPattern.substring(colon + 1);
    String regex = switch (syntax.toLowerCase()) {
      case "glob" -> Globs.toRegex(pattern);
      case "regex" -> pattern;
      default -> throw new UnsupportedOperationException("syntax " + syntax);
    };
    Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    return path -> p.matcher(path.toString()).matches();
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    throw new UnsupportedOperationException("FAT has no owners");
  }

  @Override
  public WatchService newWatchService() {
    throw new UnsupportedOperationException("watch service not supported");
  }

  // ------------------------------------------------------------------ MEGA65 extras

  /**
   * Makes sure everything written so far is on the SD card, while the session stays open for
   * more work. Write channels that are still open write their current content (contiguously, like
   * on close) and stay usable; later writes to them are committed by the next {@code commit()}
   * or by closing them. Returns once the MEGA65 has acknowledged every sector.
   *
   * <p>All other operations are already committed when they return, so without open write
   * channels this is a cheap barrier.
   *
   * @throws io.github.paw.m65fs.fat32.NoContiguousSpaceException if an open channel's content
   *     does not fit in one contiguous run; the file then keeps its previous content on the card
   *     and the channel stays open
   */
  public void commit() throws IOException {
    ensureOpen();
    IOException failure = null;
    for (var w : List.copyOf(writers)) {
      try {
        w.commit();
      } catch (IOException e) {
        failure = addTo(failure, e);
      }
    }
    volume.flush();
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Asks the hypervisor to mount a D81 image as drive 0. The image must be in the root
   * directory (hypervisor limitation) and contiguous; pending writes are flushed first.
   *
   * @return true if the hypervisor reports success
   * @throws UnsupportedOperationException for image-file backed file systems
   */
  public boolean mountD81(Path image) throws IOException {
    ensureOpen();
    if (session == null) {
      throw new UnsupportedOperationException("mounting needs a connection to a MEGA65");
    }
    Mega65Path p = Mega65Path.cast(image).toAbsolutePath().normalize();
    if (p.getNameCount() != 1) {
      throw new IOException("only images in the root directory can be mounted: " + p);
    }
    DirEntry e = entry(p).orElseThrow(() -> new NoSuchFileException(p.toString()));
    if (!e.isRegularFile()) {
      throw new IOException("not a regular file: " + p);
    }
    if (!volume.isContiguous(e)) {
      throw new IOException("image is fragmented and cannot be mounted (rewrite it to defragment): " + p);
    }
    // Hyppo compares the upper-cased request with the upper-cased long name (or the 8.3 name)
    String name = e.name();
    if (name.length() > 63 || !name.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
      throw new IOException("name must be printable ASCII of at most 63 characters to be mounted: " + name);
    }
    volume.flush();
    return session.mount(name);
  }

  /** Reads 1..256 bytes of MEGA65 memory (28-bit address). */
  public byte[] peek(int address, int count) throws IOException {
    ensureOpen();
    if (session == null) {
      throw new UnsupportedOperationException("peek needs a connection to a MEGA65");
    }
    return session.peek(address, count);
  }

  /** The MEGA65 this file system talks to; empty for image files. */
  public Optional<Target> target() {
    return Optional.ofNullable(session).map(RemoteSdSession::target);
  }

  /** The SD card's FAT32 partition. */
  public Mega65FileStore fileStore() {
    return store;
  }

  @Override
  public String toString() {
    return "mega65://" + authority + "/";
  }

  // ------------------------------------------------------------------ internals

  Fat32Volume volume() {
    return volume;
  }

  ZoneId zone() {
    return zone;
  }

  void opened(Channels.WriteChannel w) {
    writers.add(w);
  }

  void closed(Channels.WriteChannel w) {
    writers.remove(w);
  }

  int shadowMemoryLimit() {
    return shadowMemoryLimit;
  }

  Mega65Path root() {
    return new Mega65Path(this, true, List.of());
  }

  void ensureOpen() {
    if (!open) {
      throw new ClosedFileSystemException();
    }
  }

  /** Looks up an entry; empty if any component is missing or not a directory. */
  Optional<DirEntry> entry(Mega65Path path) throws IOException {
    ensureOpen();
    Mega65Path p = path.toAbsolutePath().normalize();
    DirEntry cur = volume.root();
    for (String n : p.names()) {
      if (!cur.isDirectory()) {
        return Optional.empty();
      }
      Optional<DirEntry> next = volume.find(cur, n);
      if (next.isEmpty()) {
        return Optional.empty();
      }
      cur = next.get();
    }
    return Optional.of(cur);
  }

  DirEntry existing(Mega65Path path) throws IOException {
    return entry(path).orElseThrow(() -> new NoSuchFileException(path.toString()));
  }

  Path realPath(Mega65Path absolute) throws IOException {
    ensureOpen();
    DirEntry cur = volume.root();
    List<String> names = new ArrayList<>();
    for (String n : absolute.names()) {
      if (!cur.isDirectory()) {
        throw new NoSuchFileException(absolute.toString());
      }
      cur = volume.find(cur, n).orElseThrow(() -> new NoSuchFileException(absolute.toString()));
      names.add(cur.name());
    }
    return new Mega65Path(this, true, names);
  }

  URI uriFor(Mega65Path absolute) {
    try {
      return new URI(Mega65FileSystemProvider.SCHEME, authority, absolute.toString(), null, null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
