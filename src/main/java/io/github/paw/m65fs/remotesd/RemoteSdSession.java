package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.etherload.Etherload;
import io.github.paw.m65fs.transport.Mega65TimeoutException;
import io.github.paw.m65fs.transport.SlidingWindow;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A session with the transfer helper (src/main/asm/helper/): raw access to the MEGA65 SD card plus a few extras (memory peek,
 * D81 mount). Thread-safe (all operations are serialized).
 *
 * <p>Protocol rules enforced here (violating them makes the helper stop with a fatal error):
 * reads and writes are never in flight at the same time; a new write batch starts only after the
 * previous one is fully acknowledged; read requests cover at most 128 sectors.
 *
 * <p>If an operation fails (timeout, interrupt, socket error), the session is marked failed: the
 * helper's state is unknown (e.g. half a write batch), so every later operation throws. {@link
 * #close()} then still tries to send quit (without waiting the full timeout).
 */
public final class RemoteSdSession implements AutoCloseable {

  private static final Logger LOG = System.getLogger(RemoteSdSession.class.getName());

  /** Hardware model register; A200T boards have more Ethernet RX buffers. */
  public static final int MODEL_ID_ADDRESS = 0xFFD3629;
  private static final Set<Integer> LARGE_RX_BUFFER_MODELS = Set.of(0x01, 0x03, 0x04, 0x21);
  private static final int READ_WINDOW = 256;
  private static final int MAX_WRITE_BATCH = 128;

  /**
   * Session options.
   *
   * @param timeout per-operation timeout ({@code ETHERNET_TIMEOUT} = 3 s in mega65_ftp)
   * @param writeWindow override for the write window size (default: 3 or 28 depending on the model)
   * @param quitOnClose send quit on {@link #close()}, which resets the MEGA65
   * @param helperProgram the transfer helper program to bootstrap
   * @param force take the MEGA65 over (trigger) even if its helper serves another command or computer
   */
  public record Options(Duration timeout, OptionalInt writeWindow, boolean quitOnClose, byte[] helperProgram,
      boolean force) {
    public Options {
      Objects.requireNonNull(timeout);
      Objects.requireNonNull(writeWindow);
      Objects.requireNonNull(helperProgram);
    }

    public static Options defaults() {
      return new Options(Etherload.DEFAULT_TIMEOUT, OptionalInt.empty(), true, Etherload.bundledHelper(), false);
    }

    public Options withQuitOnClose(boolean q) {
      return new Options(timeout, writeWindow, q, helperProgram, force);
    }

    public Options withWriteWindow(int w) {
      return new Options(timeout, OptionalInt.of(w), quitOnClose, helperProgram, force);
    }

    public Options withTimeout(Duration t) {
      return new Options(t, writeWindow, quitOnClose, helperProgram, force);
    }

    public Options withForce(boolean f) {
      return new Options(timeout, writeWindow, quitOnClose, helperProgram, f);
    }
  }

  private enum Mode { IDLE, READ, WRITE, CONTROL }

  private final SlidingWindow window;
  private final Options options;
  private Duration timeout;
  private int helperVersion = -1;
  private Response.HelloBusy busy;
  private int writeWindow = 3;
  private int modelId = -1;
  private Mode mode = Mode.IDLE;
  private int batchCounter;
  private boolean closed;
  private boolean quitOnClose;
  private IOException failure;

  private RemoteSdSession(SlidingWindow window, Options options) {
    this.window = window;
    this.options = options;
    this.timeout = options.timeout();
    this.quitOnClose = options.quitOnClose();
  }

  /**
   * Whether {@link #close()} sends quit (resetting the MEGA65). Turned off when the machine is
   * taken over right afterwards anyway (booting a disk), which saves a reset.
   */
  public synchronized void setQuitOnClose(boolean quit) {
    this.quitOnClose = quit;
  }

  /**
   * The version of the bundled helper (its hello answer); only such a helper is reused. Raised
   * with every change of the helper (together with {@code HELPER_VERSION} in helper.a).
   */
  public static final int HELPER_VERSION = 5;


  /** How long {@link #connect} waits for a running helper to answer hello. */
  static final Duration RUNNING_HELPER_TIMEOUT = Duration.ofMillis(150);

  /**
   * Opens a session on {@code target}: with the transfer helper that is still running there
   * (left by an earlier session that ended with release, see {@link #close()}), or else after
   * bootstrapping it (trigger, upload, start).
   *
   * @throws Mega65BusyException if the helper serves another command or computer (unless
   *     {@code force})
   */
  public static RemoteSdSession connect(Target target, Options options) throws IOException {
    var running = attachRunning(target, options, RUNNING_HELPER_TIMEOUT);
    if (running.isPresent()) {
      return running.get();
    }
    UdpLink link = UdpLink.open(target);
    try {
      Etherload.connect(link).startHelper(options.helperProgram());
      return attach(link, options);
    } catch (IOException | RuntimeException e) {
      link.close();
      throw e;
    }
  }

  /**
   * Opens a session on a link to an already started, not yet greeted helper: sends hello and
   * determines the window size. Takes ownership of {@code link}.
   */
  public static RemoteSdSession attach(UdpLink link, Options options) throws IOException {
    var session = new RemoteSdSession(new SlidingWindow(link, SessionProtocol.INSTANCE), options);
    try {
      session.hello();
      session.init();
    } catch (IOException | RuntimeException e) {
      link.close();
      throw e;
    }
    return session;
  }

  /** After hello: model id and write window. */
  private void init() throws IOException {
    modelId = peek(MODEL_ID_ADDRESS, 1)[0] & 0xff;
    writeWindow = options.writeWindow().orElse(LARGE_RX_BUFFER_MODELS.contains(modelId) ? 28 : 3);
    LOG.log(Level.DEBUG, "helper session on {0}: model id ${1}, write window {2}, helper version {3}",
        target(), Integer.toHexString(modelId), writeWindow, helperVersion);
  }

  /**
   * A session with the helper running on {@code target}, if one of our version answers hello
   * within {@code wait} (it is unlocked: its last session ended with release); otherwise empty
   * (nothing answers, or the helper serves another client or is of another version).
   */
  public static java.util.Optional<RemoteSdSession> attachRunning(Target target, Options options, Duration wait)
      throws IOException {
    UdpLink link = UdpLink.open(target);
    var session = new RemoteSdSession(new SlidingWindow(link, SessionProtocol.INSTANCE), options);
    session.timeout = wait;
    try {
      session.hello();
      if (session.busy != null) {
        String owner = ownerText(session.busy.owner());
        if (options.force()) {
          LOG.log(Level.DEBUG, "helper on {0} is busy (status {1}, {2}): taken over (force)", target,
              session.busy.status(), owner);
          link.close();
          return java.util.Optional.empty();
        }
        throw new Mega65BusyException(session.busy.status(), owner);
      }
    } catch (Mega65TimeoutException e) {
      link.close();
      return java.util.Optional.empty();
    } catch (IOException | RuntimeException e) {
      link.close();
      throw e;
    }
    session.timeout = options.timeout();
    if (session.helperVersion != HELPER_VERSION) {
      LOG.log(Level.DEBUG, "running helper on {0} has version {1}, not {2}: starting a new one", target,
          session.helperVersion, HELPER_VERSION);
      link.close(); // it stays locked to this link; the bootstrap replaces it
      return java.util.Optional.empty();
    }
    try {
      session.init();
    } catch (IOException | RuntimeException e) {
      link.close();
      throw e;
    }
    LOG.log(Level.DEBUG, "reusing the running helper on {0}", target);
    return java.util.Optional.of(session);
  }

  /** An IPv6 address as usually written (RFC 5952: the longest run of zero groups as ::). */
  static String ownerText(byte[] address) {
    if (address.length != 16) {
      return "?";
    }
    int[] g = new int[8];
    for (int i = 0; i < 8; i++) {
      g[i] = (address[2 * i] & 0xff) << 8 | address[2 * i + 1] & 0xff;
    }
    int best = -1;
    int bestLen = 1; // a single zero group stays
    for (int i = 0; i < 8; ) {
      int j = i;
      while (j < 8 && g[j] == 0) {
        j++;
      }
      if (j - i > bestLen) {
        best = i;
        bestLen = j - i;
      }
      i = j == i ? i + 1 : j;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == best) {
        sb.append("::");
        i += bestLen - 1;
        continue;
      }
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
        sb.append(':');
      }
      sb.append(Integer.toHexString(g[i]));
    }
    return sb.toString();
  }

  /**
   * Makes sure a trigger does not end another command's session: asks a running helper first.
   * For tools that trigger the MEGA65 (run, reset).
   *
   * @throws Mega65BusyException if the helper serves another command or computer (unless
   *     {@code force})
   */
  public static void ensureFree(Target target, Options options) throws IOException {
    attachRunning(target, options.withQuitOnClose(false), RUNNING_HELPER_TIMEOUT).ifPresent(s -> {
      try {
        s.close();
      } catch (IOException e) {
        // release is best effort
      }
    });
  }

  /**
   * Whether a transfer helper answers on {@code target} (a quick check of a remembered address
   * that, unlike a trigger, leaves the MEGA65's screen alone). If it lets us in, the session is
   * released again; a busy answer counts as well.
   */
  public static boolean helperAnswers(Target target, Duration wait) throws IOException {
    try (UdpLink link = UdpLink.open(target)) {
      var session = new RemoteSdSession(new SlidingWindow(link, SessionProtocol.INSTANCE),
          Options.defaults().withQuitOnClose(false).withTimeout(wait));
      try {
        session.hello();
      } catch (Mega65TimeoutException e) {
        return false;
      }
      if (session.busy == null) {
        session.close(); // release
      }
      return true;
    }
  }

  /** The helper's version from its hello answer. */
  public int helperVersion() {
    return helperVersion;
  }

  public Target target() {
    return window.link().target();
  }

  /** Hardware model id read from {@code $FFD3629}. */
  public int modelId() {
    return modelId;
  }

  public int writeWindow() {
    return writeWindow;
  }

  /** Total retransmissions so far (diagnostics). */
  public long retransmissions() {
    return window.retransmissions();
  }

  private void hello() throws IOException {
    control(new Request.Hello(), rx -> {
      switch (Response.parse(rx).orElse(null)) {
        case Response.HelloAck(_, int v) -> helperVersion = v;
        case Response.HelloBusy b -> {
          helperVersion = b.version();
          busy = b;
        }
        case null, default -> { }
      }
    });
  }

  /**
   * Reads {@code dst.remaining() / 512} sectors starting at {@code lba} into {@code dst}.
   */
  public synchronized void readSectors(long lba, ByteBuffer dst) throws IOException {
    ensureOpen();
    int n = BlockDevice.sectors(lba, dst);
    guarded(() -> {
      enter(Mode.READ);
      int base = dst.position();
      for (int done = 0; done < n; ) {
        int k = Math.min(Request.ReadSectors.MAX_COUNT, n - done);
        readBatch(lba + done, dst.slice(base + done * BlockDevice.SECTOR_SIZE, k * BlockDevice.SECTOR_SIZE), k);
        done += k;
      }
      dst.position(base + n * BlockDevice.SECTOR_SIZE);
    });
  }

  /**
   * One read request for {@code k} sectors. As in mega65_ftp, one virtual single-sector request
   * per sector is registered (consecutive sequence numbers, matched by sector number), so a lost
   * answer is retransmitted as a single-sector read; then the real request is sent once.
   */
  private void readBatch(long start, ByteBuffer out, int k) throws IOException {
    window.awaitAll(timeout); // the helper serves one read batch at a time
    int seq0 = window.nextSeq();
    for (int i = 0; i < k; i++) {
      window.schedule(new Request.ReadSectors(start + i, 1).encode(), rx -> {
        if (Response.parse(rx).orElse(null) instanceof Response.SectorData(_, long sector, ByteBuffer data)) {
          out.put((int) (sector - start) * BlockDevice.SECTOR_SIZE, data, 0, BlockDevice.SECTOR_SIZE);
        }
      }, timeout);
    }
    byte[] request = new Request.ReadSectors(start, k).encode();
    SessionProtocol.INSTANCE.embedSeq(request, seq0);
    window.sendUnscheduled(request);
    window.awaitAll(timeout);
  }

  /**
   * Writes {@code src.remaining() / 512} sectors starting at {@code lba}: runs of up to 128
   * sectors as one batch (single sectors use the synchronous single-write command). Returns when
   * the helper has acknowledged everything; the helper verifies each batch by reading it back.
   */
  public synchronized void writeSectors(long lba, ByteBuffer src) throws IOException {
    ensureOpen();
    int n = BlockDevice.sectors(lba, src);
    guarded(() -> {
      enter(Mode.WRITE);
      for (int done = 0; done < n; ) {
        int k = Math.min(MAX_WRITE_BATCH, n - done);
        writeBatch(lba + done, src, k);
        done += k;
      }
    });
  }

  private void writeBatch(long start, ByteBuffer src, int k) throws IOException {
    window.awaitAll(timeout);
    int counter = batchCounter;
    batchCounter = (batchCounter + 1) & 0xff;
    for (int slot = 0; slot < k; slot++) {
      byte[] data = new byte[BlockDevice.SECTOR_SIZE];
      src.get(data);
      window.submit(new Request.WriteSector(counter, k - 1, slot, start, data).encode(), _ -> {}, timeout);
    }
    window.awaitAll(timeout);
  }

  /** Reads 1..256 bytes of MEGA65 memory. */
  public synchronized byte[] peek(int address, int count) throws IOException {
    ensureOpen();
    byte[][] result = new byte[1][];
    guardedControl(new Request.ReadMemory(address, count), rx -> {
      if (Response.parse(rx).orElse(null) instanceof Response.MemoryData(_, _, ByteBuffer data)) {
        byte[] b = new byte[data.remaining()];
        data.get(b);
        result[0] = b;
      }
    });
    if (result[0] == null || result[0].length != count) {
      throw new IOException("invalid memory read response");
    }
    return result[0];
  }

  /**
   * Asks the hypervisor to mount a D81 image located in the SD card root. This leaves the
   * hypervisor in the root directory.
   *
   * @return true if mounted
   */
  public synchronized boolean mount(String imageName) throws IOException {
    ensureOpen();
    int[] status = {-1};
    guardedControl(new Request.Mount(imageName), rx -> {
      if (Response.parse(rx).orElse(null) instanceof Response.MountResult(_, int s)) {
        status[0] = s;
      }
    });
    if (status[0] < 0) {
      throw new IOException("no valid mount response");
    }
    return status[0] == 0;
  }

  /**
   * Shows a line in the log on the helper's screen (see {@link Request.Text}): one round trip.
   */
  public synchronized void showText(Request.Text text) throws IOException {
    ensureOpen();
    boolean[] ok = {false};
    guardedControl(text, rx -> ok[0] |= Response.parse(rx).orElse(null) instanceof Response.TextAck);
    if (!ok[0]) {
      throw new IOException("no valid text response");
    }
  }

  /**
   * The hypervisor's current drive (partition) and working directory, as the cluster where the
   * directory starts (0 or the root cluster = root directory).
   */
  public record WorkingDirectory(int drive, int cluster) {}

  /**
   * Asks the hypervisor for its working directory: the one chosen with {@code CHDIR} in BASIC or
   * by an earlier {@link #changeWorkingDirectory}. The helper returns to it when it starts (the
   * Ethernet remote trap changes to the root directory to load the helper).
   *
   * @return empty if the hypervisor cannot tell (it has no {@code trap_dos_getcwd})
   */
  public synchronized Optional<WorkingDirectory> workingDirectory() throws IOException {
    Response.CwdResult r = cwd(Request.Cwd.get());
    return r.status() == 0 ? Optional.of(new WorkingDirectory(r.drive(), r.cluster())) : Optional.empty();
  }

  /**
   * Changes the hypervisor's working directory (on its current drive), as {@code CHDIR} in BASIC
   * would, to the directory reached from the root through {@code names} (each the long name if the
   * entry has one, otherwise the 8.3 name, as the hypervisor compares them; empty = root
   * directory). Mounting an image changes to the root directory, see {@link #mount}.
   *
   * @return the new working directory
   * @throws IOException if the hypervisor does not support it or a directory was not found (the
   *     working directory is then undefined)
   */
  public synchronized WorkingDirectory changeWorkingDirectory(List<String> names) throws IOException {
    Response.CwdResult r = cwd(Request.Cwd.set(names));
    if (r.status() != 0) {
      throw new IOException("the hypervisor could not change to /" + String.join("/", names)
          + " (error $" + Integer.toHexString(r.status()) + "; does its hypervisor support a working directory?)");
    }
    return new WorkingDirectory(r.drive(), r.cluster());
  }

  private Response.CwdResult cwd(Request.Cwd request) throws IOException {
    ensureOpen();
    Response.CwdResult[] result = new Response.CwdResult[1];
    guardedControl(request, rx -> {
      if (Response.parse(rx).orElse(null) instanceof Response.CwdResult r) {
        result[0] = r;
      }
    });
    if (result[0] == null) {
      throw new IOException("no valid working directory response");
    }
    return result[0];
  }

  /**
   * Sends quit (if configured) and closes the socket. The MEGA65 resets after quit. After a
   * failure, quit is sent best-effort a few times and the session closes without an exception if
   * it stays unanswered (a warning is logged: the MEGA65 may then need a manual reset).
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (quitOnClose) {
        if (failure == null) {
          control(new Request.Quit());
        } else if (!quitBestEffort()) {
          LOG.log(Level.WARNING, "MEGA65 at {0} did not acknowledge quit after an earlier failure; "
              + "it may still run the transfer helper and need a manual reset", target());
        }
      } else if (failure == null) {
        // the helper keeps running: unlock it for the next session (best effort; without it
        // the next session just starts a new helper)
        try {
          control(new Request.Release());
        } catch (IOException e) {
          LOG.log(Level.DEBUG, "release not acknowledged: {0}", e.toString());
        }
      }
    } finally {
      window.link().close();
      LOG.log(Level.DEBUG, "session with {0} closed ({1} retransmissions)", target(), window.retransmissions());
    }
  }

  /** Sends quit outside the (possibly clogged) window; true if a quit answer arrived. */
  private boolean quitBestEffort() {
    UdpLink link = window.link();
    byte[] quit = new Request.Quit().encode();
    SessionProtocol.INSTANCE.embedSeq(quit, window.nextSeq());
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        link.send(quit);
        long deadline = System.nanoTime() + 300_000_000L;
        for (long left; (left = deadline - System.nanoTime()) > 0; ) {
          link.awaitReadable(left);
          for (ByteBuffer rx; (rx = link.poll()) != null; ) {
            if (Response.parse(rx).orElse(null) instanceof Response.QuitAck _) {
              return true;
            }
          }
        }
      }
    } catch (IOException e) {
      LOG.log(Level.DEBUG, "best-effort quit failed: {0}", e.toString());
    }
    return false;
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws IOException;
  }

  /** Runs a protocol exchange; any failure marks the session failed. */
  private void guarded(IoAction action) throws IOException {
    try {
      action.run();
    } catch (Mega65TimeoutException e) {
      var t = new Mega65TimeoutException("MEGA65 at " + target() + ": " + e.getMessage());
      t.initCause(e);
      throw fail(t);
    } catch (IOException e) {
      throw fail(e);
    } catch (RuntimeException e) {
      fail(new IOException(e));
      throw e;
    }
  }

  private <E extends IOException> E fail(E e) {
    if (failure == null) {
      failure = e;
      LOG.log(Level.DEBUG, "session with {0} failed: {1}", target(), e.toString());
    }
    return e;
  }

  private void guardedControl(Request r, java.util.function.Consumer<ByteBuffer> onAck) throws IOException {
    guarded(() -> control(r, onAck));
  }

  private void control(Request r) throws IOException {
    control(r, _ -> {});
  }

  private void control(Request r, java.util.function.Consumer<ByteBuffer> onAck) throws IOException {
    enter(Mode.CONTROL);
    window.submit(r.encode(), onAck, timeout);
    window.awaitAll(timeout);
  }

  /** Switches between read/write/control traffic; never lets them overlap. */
  private void enter(Mode m) throws IOException {
    if (m == mode) {
      return;
    }
    window.awaitAll(timeout);
    window.setCapacity(m == Mode.READ ? READ_WINDOW : writeWindow);
    mode = m;
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("session closed");
    }
    if (failure != null) {
      throw new IOException("session with MEGA65 at " + target() + " failed earlier (" + failure.getMessage()
          + "); close it and connect again", failure);
    }
  }
}
