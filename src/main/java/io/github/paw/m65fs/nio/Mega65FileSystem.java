package io.github.paw.m65fs.nio;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.block.CachingBlockDevice;
import io.github.paw.m65fs.block.CountingBlockDevice;
import io.github.paw.m65fs.block.ImageFileBlockDevice;
import io.github.paw.m65fs.etherload.Etherload;
import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.launch.D81Image;
import io.github.paw.m65fs.launch.MachineMode;
import io.github.paw.m65fs.launch.Mega65Launcher;
import io.github.paw.m65fs.remotesd.RemoteSdBlockDevice;
import io.github.paw.m65fs.remotesd.RemoteSdSession;
import io.github.paw.m65fs.remotesd.Request;
import io.github.paw.m65fs.transport.Mega65Locator;
import io.github.paw.m65fs.transport.Target;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.lang.System.Logger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private static final Logger LOG = System.getLogger(Mega65FileSystem.class.getName());
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
  private final CountingBlockDevice traffic;
  private volatile Mega65Path workingDirectory;
  /** Whether the hypervisor keeps the working directory (so that it is shared with BASIC). */
  private volatile boolean hypervisorCwd;
  private volatile boolean open = true;

  private Mega65FileSystem(Mega65FileSystemProvider provider, String key, String authority, Fat32Volume volume,
      RemoteSdSession session, CountingBlockDevice traffic, Env env) {
    this.provider = provider;
    this.traffic = traffic;
    this.key = key;
    this.authority = authority;
    this.volume = volume;
    this.session = session;
    this.zone = env.zone();
    this.shadowMemoryLimit = env.integer("shadowMemoryLimit").orElse(Shadow.DEFAULT_MEMORY_LIMIT);
    this.store = new Mega65FileStore(this);
    this.workingDirectory = root();
  }

  /** Opens the file system described by {@code authority} ("auto", "[fe80::…%if]") or {@code env}. */
  static Mega65FileSystem open(Mega65FileSystemProvider provider, String key, String authority, Env env)
      throws IOException {
    Optional<Path> image = env.path("imageFile");
    boolean readOnly = env.bool("readOnly", false);
    if (image.isPresent()) {
      var dev = new CountingBlockDevice(ImageFileBlockDevice.open(image.get(), readOnly));
      return withDevice(provider, key, authority, dev, dev, null, env);
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
    var traffic = new CountingBlockDevice(new RemoteSdBlockDevice(session));
    BlockDevice dev = new CachingBlockDevice(traffic,
        env.integer("readAhead").orElse(CachingBlockDevice.DEFAULT_READ_AHEAD), CachingBlockDevice.DEFAULT_CAPACITY);
    if (env.bool("readOnly", false)) {
      dev = BlockDevice.readOnly(dev);
    }
    return withDevice(provider, key, authority, dev, traffic, session, env);
  }

  private static Mega65FileSystem withDevice(Mega65FileSystemProvider provider, String key, String authority,
      BlockDevice dev, CountingBlockDevice traffic, RemoteSdSession session, Env env) throws IOException {
    try {
      Fat32Volume volume = Fat32Volume.open(dev, Clock.system(env.zone()));
      var fs = new Mega65FileSystem(provider, key, authority, volume, session, traffic, env);
      fs.adoptHypervisorWorkingDirectory();
      return fs;
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

  /**
   * Opens an SD card image file (MBR with a FAT32 partition, or a bare FAT32 volume). Unlike
   * {@code FileSystems.newFileSystem(path, env)}, which asks every installed provider, this
   * goes to m65fs directly. {@code env}: the options of the README (e.g. {@code readOnly}).
   */
  public static Mega65FileSystem openImage(Path image, Map<String, ?> env) throws IOException {
    return Mega65FileSystemProvider.installed().openImage(image, env);
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
   * Bytes read from the card (or image) so far: what really came over the network, not what
   * the cache answered. With {@link #bytesWritten()} the basis for progress displays.
   */
  public long bytesRead() {
    return traffic.bytesRead();
  }

  /** Bytes written to the card (or image) so far, including FAT and directory sectors. */
  public long bytesWritten() {
    return traffic.bytesWritten();
  }

  // ------------------------------------------------------------------ working directory

  /**
   * The directory relative paths are resolved against. On a MEGA65 with a hypervisor that keeps
   * the working directory it is the hypervisor's (shared with {@code CHDIR "DIR",U12} in BASIC and
   * with later sessions); otherwise, and for image files, it starts at the root and lives only as
   * long as this file system.
   */
  public Path workingDirectory() {
    return workingDirectory;
  }

  /** Whether {@link #setWorkingDirectory} changes the MEGA65's own working directory. */
  public boolean isWorkingDirectoryShared() {
    return hypervisorCwd;
  }

  /**
   * Changes the working directory (relative paths are resolved against the current one). On a
   * MEGA65 that keeps it, this is like {@code CHDIR} in BASIC and lasts beyond this session.
   *
   * @throws NoSuchFileException if {@code dir} does not exist
   * @throws NotDirectoryException if it is not a directory
   */
  public void setWorkingDirectory(Path dir) throws IOException {
    ensureOpen();
    Mega65Path abs = Mega65Path.cast(dir).toAbsolutePath().normalize();
    List<DirEntry> entries = directoryEntries(abs);
    if (hypervisorCwd) {
      if (!volume.isReadOnly()) {
        volume.flush(); // the hypervisor reads the directories from the card
      }
      var cwd = session.changeWorkingDirectory(entries.stream().map(DirEntry::name).toList());
      int expected = entries.isEmpty() ? volume.root().firstCluster() : entries.getLast().firstCluster();
      if (cwd.cluster() != expected && !(entries.isEmpty() && cwd.cluster() == 0)) {
        throw new IOException("the hypervisor changed to cluster " + cwd.cluster() + " instead of " + expected);
      }
    }
    workingDirectory = new Mega65Path(this, true, entries.stream().map(DirEntry::name).toList());
  }

  /** The entries from the root down to the directory {@code abs} (absolute and normalized). */
  private List<DirEntry> directoryEntries(Mega65Path abs) throws IOException {
    List<DirEntry> entries = new ArrayList<>();
    DirEntry cur = volume.root();
    for (String n : abs.names()) {
      cur = volume.find(cur, n).orElseThrow(() -> new NoSuchFileException(abs.toString()));
      if (!cur.isDirectory()) {
        throw new NotDirectoryException(abs.toString());
      }
      entries.add(cur);
    }
    return entries;
  }

  /** Starts in the hypervisor's working directory, if it keeps one on the partition we use. */
  private void adoptHypervisorWorkingDirectory() throws IOException {
    if (session == null) {
      return;
    }
    var cwd = session.workingDirectory();
    if (cwd.isEmpty()) {
      LOG.log(Level.DEBUG, "hypervisor does not report a working directory; using the root");
      return;
    }
    if (cwd.get().drive() != 0) {
      LOG.log(Level.DEBUG, "hypervisor works on drive {0}, not on the first partition; using the root",
          cwd.get().drive());
      return;
    }
    hypervisorCwd = true;
    try {
      workingDirectory = new Mega65Path(this, true,
          volume.directoryPath(cwd.get().cluster()).stream().map(DirEntry::name).toList());
    } catch (FileSystemException e) {
      // e.g. the directory was deleted since; don't leave the hypervisor (and BASIC) in it
      LOG.log(Level.DEBUG, "hypervisor working directory (cluster {0}) not found ({1}); changing to the root",
          cwd.get().cluster(), e.getMessage());
      session.changeWorkingDirectory(List.of());
    }
  }

  /** How a line on the MEGA65's screen looks (see {@link #showOnMega65}). */
  public enum LineKind {
    /** The command being run (white). */
    COMMAND(Request.Text.WHITE),
    /** What is being done (green). */
    INFO(Request.Text.GREEN),
    /** Something that went wrong (red). */
    ERROR(Request.Text.RED);

    final int colour;

    LineKind(int colour) {
      this.colour = colour;
    }
  }

  /**
   * Shows a line in the log on the transfer helper's screen (80 columns; longer lines show
   * their end), so that the MEGA65 tells what is being done to its card. One round trip; does
   * nothing for image files.
   *
   * @param replace replace the last line (e.g. to append a result) instead of adding one
   */
  public void showOnMega65(String line, LineKind kind, boolean replace) throws IOException {
    ensureOpen();
    if (session != null) {
      session.showText(new Request.Text(kind.colour, replace, line));
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
    requireSession("mounting");
    String name = mountableName(image);
    volume.flush();
    boolean mounted = session.mount(name);
    if (hypervisorCwd) {
      // mounting changes to the root directory; go back
      setWorkingDirectory(workingDirectory);
    }
    return mounted;
  }

  /**
   * Boots a disk image: ends the session (as {@link #close()} does, so the MEGA65 is reset),
   * then mounts the image, resets into C64 or MEGA65 mode and loads and runs the first program
   * on it. The mode follows the program's load address ($2001 = MEGA65, otherwise C64 as with
   * {@code GO64}). The image must be in the root directory and contiguous (see
   * {@link #mountD81}).
   *
   * <pre>{@code
   * Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"), REPLACE_EXISTING);
   * fs.closeAndBoot(fs.getPath("/GAME.D81"));
   * }</pre>
   *
   * @return what was started
   * @throws IOException if the image has no program; the file system then stays open
   */
  public Mega65Launcher.Boot closeAndBoot(Path image) throws IOException {
    return closeAndBoot(image, null);
  }

  /**
   * Like {@link #closeAndBoot(Path)} with an explicit mode ({@code null} = by load address).
   */
  public Mega65Launcher.Boot closeAndBoot(Path image, MachineMode mode) throws IOException {
    ensureOpen();
    requireSession("booting");
    Mega65Path p = Mega65Path.cast(image).toAbsolutePath().normalize();
    String name = mountableName(p);
    Mega65Launcher.Boot boot;
    try (var ch = provider.newByteChannel(p, Set.of(java.nio.file.StandardOpenOption.READ))) {
      boot = Mega65Launcher.plan(name, D81Image.read(ch), mode, null);
    }
    Target target = endSessionForLaunch();
    Mega65Launcher.boot(target, boot, Etherload.Video.UNCHANGED);
    return boot;
  }

  /**
   * Ends the session and resets the MEGA65 into {@code mode} with no disk image attached (a
   * MEGA65-mode reset detaches drive 0; the reset done by a plain {@link #close()} keeps it).
   */
  public void closeAndReset(MachineMode mode) throws IOException {
    ensureOpen();
    requireSession("resetting");
    Target target = endSessionForLaunch();
    Mega65Launcher.reset(target, mode, null, Etherload.Video.UNCHANGED);
  }

  /**
   * Closes the file system without the quit's reset: the launcher takes over the machine right
   * away, and the hypervisor may ignore its trigger for a while after a reset.
   */
  private Target endSessionForLaunch() throws IOException {
    Target target = session.target();
    session.setQuitOnClose(false);
    close();
    return target;
  }

  /**
   * Ends the session (as {@link #close()} does) and runs a program file straight from memory
   * (see {@link Mega65Launcher#runPrg}): $0801 programs in C64 mode, $2001 programs in MEGA65
   * mode. Nothing is written to the SD card.
   *
   * @return the mode the program runs in ({@code null} for a jump)
   */
  public MachineMode closeAndRun(byte[] prg, Mega65Launcher.Options options) throws IOException {
    ensureOpen();
    requireSession("running programs");
    Etherload.loadAddress(prg); // validate before closing
    Target target = session.target();
    if (options.mount() != null) {
      mountableName(getPath("/" + options.mount()));
    }
    endSessionForLaunch();
    return Mega65Launcher.runPrg(target, prg, options);
  }

  private void requireSession(String what) {
    if (session == null) {
      throw new UnsupportedOperationException(what + " needs a connection to a MEGA65");
    }
  }

  /** Checks that the hypervisor can mount the image and returns the name to ask for. */
  private String mountableName(Path image) throws IOException {
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
    return name;
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
