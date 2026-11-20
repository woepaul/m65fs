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
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A session with remotesd_eth: raw access to the MEGA65 SD card plus a few extras (memory peek,
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
   * @param helperProgram the remotesd_eth program to bootstrap
   */
  public record Options(Duration timeout, OptionalInt writeWindow, boolean quitOnClose, byte[] helperProgram) {
    public Options {
      Objects.requireNonNull(timeout);
      Objects.requireNonNull(writeWindow);
      Objects.requireNonNull(helperProgram);
    }

    public static Options defaults() {
      return new Options(Etherload.DEFAULT_TIMEOUT, OptionalInt.empty(), true, Etherload.bundledHelper());
    }

    public Options withQuitOnClose(boolean q) {
      return new Options(timeout, writeWindow, q, helperProgram);
    }

    public Options withWriteWindow(int w) {
      return new Options(timeout, OptionalInt.of(w), quitOnClose, helperProgram);
    }

    public Options withTimeout(Duration t) {
      return new Options(t, writeWindow, quitOnClose, helperProgram);
    }
  }

  private enum Mode { IDLE, READ, WRITE, CONTROL }

  private final SlidingWindow window;
  private final Options options;
  private final Duration timeout;
  private int writeWindow = 3;
  private int modelId = -1;
  private Mode mode = Mode.IDLE;
  private int batchCounter;
  private boolean closed;
  private IOException failure;

  private RemoteSdSession(SlidingWindow window, Options options) {
    this.window = window;
    this.options = options;
    this.timeout = options.timeout();
  }

  /** Bootstraps remotesd_eth on {@code target} and opens a session. */
  public static RemoteSdSession connect(Target target, Options options) throws IOException {
    UdpLink link = UdpLink.open(target);
    try {
      Etherload.on(link).bootstrap(options.helperProgram());
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
      session.modelId = session.peek(MODEL_ID_ADDRESS, 1)[0] & 0xff;
    } catch (IOException | RuntimeException e) {
      link.close();
      throw e;
    }
    session.writeWindow = options.writeWindow()
        .orElse(LARGE_RX_BUFFER_MODELS.contains(session.modelId) ? 28 : 3);
    LOG.log(Level.DEBUG, "remotesd_eth session on {0}: model id ${1}, write window {2}",
        link.target(), Integer.toHexString(session.modelId), session.writeWindow);
    return session;
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
    control(new Request.Hello());
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
   * Asks the hypervisor to mount a D81 image located in the SD card root.
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
      if (options.quitOnClose()) {
        if (failure == null) {
          control(new Request.Quit());
        } else if (!quitBestEffort()) {
          LOG.log(Level.WARNING, "MEGA65 at {0} did not acknowledge quit after an earlier failure; "
              + "it may still run the transfer helper and need a manual reset", target());
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
