package io.github.paw.m65fs.etherload;

import io.github.paw.m65fs.transport.Mega65TimeoutException;
import io.github.paw.m65fs.transport.SlidingWindow;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.UdpLink;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;

/**
 * Talks to the MEGA65 hypervisor's Ethernet remote control (ETHLOAD.M65) by sending ethlets:
 * loads data into memory and starts programs, including the transfer helper
 * {@code helper.prg}.
 */
public final class Etherload {

  private static final Logger LOG = System.getLogger(Etherload.class.getName());

  /** {@code ETHERNET_TIMEOUT} in mega65_ftp.c. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
  /** Load address of a C64 BASIC program. */
  public static final int BASIC_START = 0x0801;
  /** Load address of a MEGA65 (BASIC 65) program. */
  public static final int BASIC65_START = 0x2001;
  /** Payload bytes per LOAD packet. */
  public static final int BLOCK_SIZE = Ethlet.LOAD_MAX;
  /** Window size used during bootstrap ({@code queue_length} default in etherload_common.c). */
  public static final int WINDOW = 4;

  private static final int TRIGGER_LEN = 128;
  private static final int TRIGGER_OFFSET = 0x24;
  /**
   * {@code magic_string} in etherload_common.c: byte $65 (not the characters "65"), "GSKEYCODE",
   * then key code $8000 little-endian = Ethernet hypervisor trap.
   */
  private static final byte[] TRIGGER_MAGIC = {
      0x65, 'G', 'S', 'K', 'E', 'Y', 'C', 'O', 'D', 'E', 0x00, (byte) 0x80};

  private final SlidingWindow window;

  /**
   * Uses {@code window}'s link; switches the window to the LOAD protocol and a capacity of
   * {@value #WINDOW}. The window must be idle.
   */
  public Etherload(SlidingWindow window) {
    this.window = Objects.requireNonNull(window);
    window.setProtocol(EthletProtocols.Load.INSTANCE);
    window.setCapacity(WINDOW);
  }

  /** Creates a fresh window on {@code link} for the bootstrap. */
  public static Etherload on(UdpLink link) {
    return new Etherload(new SlidingWindow(link, EthletProtocols.Load.INSTANCE));
  }

  public SlidingWindow window() {
    return window;
  }

  /**
   * Multicasts the magic packet that traps the hypervisor into Ethernet remote control. Targets
   * without a network interface (simulators on loopback) get it unicast instead.
   */
  public void triggerHyperrupt() throws IOException {
    if (window.link().target().networkInterface() != null) {
      window.link().sendAllNodes(triggerPacket());
    } else {
      window.link().send(triggerPacket());
    }
    sleep(Duration.ofMillis(10));
  }

  /**
   * Makes sure the hypervisor executes ethlets: if ETHLOAD.M65 already runs (it answers a short
   * ping, e.g. right after {@link #answers}), it is used as it is; otherwise the hypervisor is
   * trapped and pinged. The trigger is repeated if a ping goes unanswered, e.g. while the MEGA65
   * is still resetting (up to about 15 s).
   *
   * <p>Not triggering twice matters: each trigger saves the working directory and changes to
   * the root, so a second trigger right after the first would save the root and lose it.
   *
   * @return a ready instance on a fresh window
   * @throws Mega65TimeoutException with a hint if the MEGA65 never answers
   */
  public static Etherload connect(UdpLink link) throws IOException {
    Etherload running = on(link);
    try {
      running.ping(ALREADY_RUNNING_PING);
      LOG.log(Level.DEBUG, "ETHLOAD.M65 already runs; not triggering again");
      return running;
    } catch (Mega65TimeoutException e) {
      // not running (BASIC, a program or the transfer helper): trigger
    }
    // Right after a reset the hypervisor can ignore the trigger for several seconds.
    Duration[] waits = {Duration.ofMillis(1500), Duration.ofMillis(1500), Duration.ofSeconds(3),
        Duration.ofSeconds(4), Duration.ofSeconds(5)};
    for (int attempt = 0; ; attempt++) {
      Etherload e = on(link);
      e.triggerHyperrupt();
      try {
        e.ping(waits[attempt]);
        return e;
      } catch (Mega65TimeoutException ex) {
        if (attempt == waits.length - 1) {
          throw new Mega65TimeoutException("no response from MEGA65 at " + link.target()
              + " (is remote mode active and ETHLOAD.M65 in the SD card root?)");
        }
        LOG.log(Level.DEBUG, "no answer to trigger, retrying");
      }
    }
  }

  /**
   * Whether a MEGA65 at {@code target} answers a trigger and a ping within {@code timeout}: a
   * quick check of a remembered address (no retries; right after a reset the hypervisor may
   * still ignore the trigger, then this says no).
   */
  public static boolean answers(Target target, Duration timeout) throws IOException {
    try (UdpLink link = UdpLink.open(target)) {
      Etherload e = on(link);
      e.triggerHyperrupt();
      e.ping(timeout);
      return true;
    } catch (Mega65TimeoutException e) {
      return false;
    }
  }

  /** How long {@link #connect} waits for ETHLOAD.M65 to answer before it triggers. */
  static final Duration ALREADY_RUNNING_PING = Duration.ofMillis(50);

  static byte[] triggerPacket() {
    byte[] packet = new byte[TRIGGER_LEN];
    System.arraycopy(TRIGGER_MAGIC, 0, packet, TRIGGER_OFFSET, TRIGGER_MAGIC.length);
    return packet;
  }

  /** Sends the echo ethlet and waits for its answer. */
  public void ping(Duration timeout) throws IOException {
    singleCommand(Ethlet.ECHO.newPacket(), timeout);
  }

  /**
   * Copies {@code data[off, off+len)} to MEGA65 memory at the 28-bit {@code address}, in
   * {@value #BLOCK_SIZE}-byte packets, never crossing a 1 MiB boundary within one packet.
   * Returns once all packets are scheduled; call {@link #awaitAll} to wait for the last acks.
   */
  public void writeMemory(int address, byte[] data, int off, int len, Duration timeout) throws IOException {
    Objects.checkFromIndexSize(off, len, data.length);
    if (address < 0 || address + (long) len > 1L << 28) {
      throw new IllegalArgumentException("address range outside 28 bit address space");
    }
    while (len > 0) {
      int toBoundary = ((address & 0xFFF00000) + 0x100000) - address;
      int n = Math.min(Math.min(len, BLOCK_SIZE), toBoundary);
      window.submit(dmaPacket(address, data, off, n), _ -> {}, timeout);
      address += n;
      off += n;
      len -= n;
    }
  }

  static byte[] dmaPacket(int address, byte[] data, int off, int n) {
    byte[] p = Ethlet.LOAD.newPacket();
    p[Ethlet.LOAD_DEST_MB] = (byte) (address >>> 20);
    p[Ethlet.LOAD_DEST_BANK] = (byte) ((address >>> 16) & 0x0f);
    WindowProtocol.putLe16(p, Ethlet.LOAD_DEST, address);
    WindowProtocol.putLe16(p, Ethlet.LOAD_COUNT, n);
    System.arraycopy(data, off, p, Ethlet.LOAD_DATA, n);
    return p;
  }

  public void awaitAll(Duration timeout) throws IOException {
    window.awaitAll(timeout);
  }

  /** PAL/NTSC switch done by the reset. */
  public enum Video {
    UNCHANGED(0), PAL(1), NTSC(2);

    final int code;

    Video(int code) {
      this.code = code;
    }
  }

  /**
   * Options of a reset.
   *
   * @param mount D81 image in the SD card root to attach as drive 0, or {@code null}
   * @param video video standard to switch to
   */
  public record Reset(String mount, Video video) {
    public Reset {
      Objects.requireNonNull(video);
    }

    public static Reset defaults() {
      return new Reset(null, Video.UNCHANGED);
    }

    public Reset withMount(String image) {
      return new Reset(image, video);
    }

    public Reset withVideo(Video v) {
      return new Reset(mount, v);
    }
  }

  /**
   * Writes a program file (2-byte load address + data) to memory at its load address and waits
   * for all acks.
   *
   * @return the end address (load address + data length), as BASIC's end-of-program pointer
   */
  public int writeProgram(byte[] prg) throws IOException {
    int load = loadAddress(prg);
    int len = prg.length - 2;
    writeMemory(load, prg, 2, len, DEFAULT_TIMEOUT);
    awaitAll(DEFAULT_TIMEOUT);
    return load + len;
  }

  /** The load address of a program file; checks that it fits below $10000. */
  public static int loadAddress(byte[] prg) {
    if (prg.length < 3) {
      throw new IllegalArgumentException("program file too short");
    }
    int load = WindowProtocol.le16(prg, 0);
    if (load + prg.length - 2 > 0x10000) {
      throw new IllegalArgumentException(String.format("program at $%04X with %d bytes extends beyond $FFFF",
          load, prg.length - 2));
    }
    return load;
  }

  /**
   * Resets into C64 mode ({@link Ethlet#START}). The working directory saved by the trigger is
   * restored after the optional mount.
   *
   * @param programEnd end address of a BASIC program loaded at $0801 (kept across the reset), or
   *     0 for none
   * @param run type RUN after the reset
   */
  public void resetToC64(int programEnd, boolean run, Reset options) throws IOException {
    start(Ethlet.START_MODE_C64, run, options.video(), programEnd, options.mount());
  }

  /**
   * Resets into MEGA65 mode ({@link Ethlet#START}). This always detaches the current disk images
   * first, so a reset without {@code mount} leaves drive 0 empty. The working directory saved
   * by the trigger is restored after the optional mount.
   *
   * @param programEnd end address of a BASIC 65 program loaded at $2001 (kept across the reset),
   *     or 0 for none
   * @param run type RUN after the reset
   */
  public void resetToMega65(int programEnd, boolean run, Reset options) throws IOException {
    start(Ethlet.START_MODE_MEGA65, run, options.video(), programEnd, options.mount());
  }

  /**
   * Optionally mounts a D81 and jumps to {@code address} ({@link Ethlet#START}); no reset, the
   * KERNAL is not initialised.
   */
  public void jump(int address, String mount) throws IOException {
    if (address < 0 || address > 0xffff) {
      throw new IllegalArgumentException("jump address must be $0000-$FFFF");
    }
    start(Ethlet.START_MODE_JUMP, false, Video.UNCHANGED, address, mount);
  }

  private void start(int mode, boolean run, Video video, int address, String mount) throws IOException {
    singleCommand(startPacket(mode, run, video, address, mount), Duration.ofSeconds(2));
  }

  static byte[] startPacket(int mode, boolean run, Video video, int address, String mount) {
    byte[] p = Ethlet.START.newPacket();
    p[Ethlet.START_MODE] = (byte) mode;
    p[Ethlet.START_FLAGS] = (byte) (run ? Ethlet.START_FLAG_RUN : 0);
    p[Ethlet.START_VIDEO] = (byte) video.code;
    WindowProtocol.putLe16(p, Ethlet.START_ADDRESS, address);
    putImageName(p, Ethlet.START_IMAGE, mount, Ethlet.START_IMAGE_MAX);
    return p;
  }

  /** Longest disk image name the ethlets take. */
  public static final int MAX_IMAGE_NAME = Ethlet.START_IMAGE_MAX;

  /** Validates a disk image name for the ethlets (printable ASCII, at most {@code maxLen}). */
  public static void checkImageName(String name, int maxLen) {
    if (name.isEmpty() || name.length() > maxLen || !name.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
      throw new IllegalArgumentException("disk image name must be 1.." + maxLen + " printable ASCII characters: "
          + name);
    }
  }

  /**
   * Hyppo's dos_setname expects PETSCII: letters with their case swapped, NUL-terminated.
   */
  static void putImageName(byte[] p, int off, String name, int maxLen) {
    if (name == null) {
      return;
    }
    checkImageName(name, maxLen);
    for (int i = 0; i < name.length(); i++) {
      int c = name.charAt(i);
      int upper = c & 0xdf;
      p[off + i] = (byte) (upper >= 'A' && upper <= 'Z' ? c ^ 0x20 : c);
    }
    p[off + name.length()] = 0;
  }

  /**
   * The complete bootstrap: trigger the hypervisor, ping, upload {@code prg} (a program file
   * with 2-byte load address) and start it at its load address.
   *
   * @throws Mega65TimeoutException with a hint if the MEGA65 does not answer the ping
   */
  public void bootstrap(byte[] prg) throws IOException {
    checkHelper(prg);
    triggerHyperrupt();
    try {
      ping(DEFAULT_TIMEOUT);
    } catch (Mega65TimeoutException e) {
      throw new Mega65TimeoutException("no response from MEGA65 at " + window.link().target()
          + " (is remote mode active and ETHLOAD.M65 in the SD card root?)");
    }
    startHelper(prg);
  }

  /**
   * Uploads {@code prg} (a program file) and jumps to its load address (START's jump mode: no
   * reset, no ROM initialisation; the helper sets up the machine itself). The hypervisor must
   * already execute ethlets (see {@link #connect}).
   */
  public void startHelper(byte[] prg) throws IOException {
    checkHelper(prg);
    LOG.log(Level.DEBUG, "uploading helper routine ({0} bytes)", prg.length - 2);
    writeProgram(prg);
    jump(loadAddress(prg), null);
    LOG.log(Level.DEBUG, "helper routine started");
  }

  private static void checkHelper(byte[] prg) {
    loadAddress(prg);
  }

  /** Swaps in the single-command protocol for one packet. */
  private void singleCommand(byte[] packet, Duration timeout) throws IOException {
    window.awaitAll(timeout);
    WindowProtocol previous = window.protocol();
    window.setProtocol(EthletProtocols.SINGLE_COMMAND);
    try {
      window.submit(packet, _ -> {}, timeout);
      window.awaitAll(timeout);
    } finally {
      if (window.inFlight() == 0) {
        window.setProtocol(previous);
      }
    }
  }

  private static void sleep(Duration d) throws IOException {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      var ie = new java.io.InterruptedIOException("interrupted");
      ie.initCause(e);
      throw ie;
    }
  }

  /** The bundled transfer helper {@code helper.prg} (src/main/asm/helper/). */
  public static byte[] bundledHelper() {
    return Ethlet.Blobs.read("helper.prg");
  }
}
