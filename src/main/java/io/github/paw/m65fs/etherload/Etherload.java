package io.github.paw.m65fs.etherload;

import io.github.paw.m65fs.transport.Mega65TimeoutException;
import io.github.paw.m65fs.transport.SlidingWindow;
import io.github.paw.m65fs.transport.UdpLink;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;

/**
 * Talks to the MEGA65 hypervisor's Ethernet remote control by sending ethlets: the bootstrap
 * phase of {@code mega65_ftp -e} that uploads and starts {@code remotesd_eth.prg}.
 */
public final class Etherload {

  private static final Logger LOG = System.getLogger(Etherload.class.getName());

  /** {@code ETHERNET_TIMEOUT} in mega65_ftp.c. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
  /** Load address of a C64 BASIC program. */
  public static final int BASIC_START = 0x0801;
  /** Load address of a MEGA65 (BASIC 65) program. */
  public static final int BASIC65_START = 0x2001;
  /** Payload bytes per DMA load packet (mega65_ftp uses 1 KiB blocks). */
  public static final int BLOCK_SIZE = 1024;
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
  private final EthletProtocols.DmaLoad dma = EthletProtocols.DmaLoad.INSTANCE;

  /**
   * Uses {@code window}'s link; switches the window to the DMA-load protocol and a capacity of
   * {@value #WINDOW}. The window must be idle.
   */
  public Etherload(SlidingWindow window) {
    this.window = Objects.requireNonNull(window);
    window.setProtocol(dma);
    window.setCapacity(WINDOW);
  }

  /** Creates a fresh window on {@code link} for the bootstrap. */
  public static Etherload on(UdpLink link) {
    return new Etherload(new SlidingWindow(link, EthletProtocols.DmaLoad.INSTANCE));
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
   * Traps the hypervisor and waits until it executes ethlets (ping). The trigger is repeated
   * if a ping goes unanswered, e.g. while the MEGA65 is still resetting (up to about 15 s).
   *
   * @return a ready instance on a fresh window
   * @throws Mega65TimeoutException with a hint if the MEGA65 never answers
   */
  public static Etherload connect(UdpLink link) throws IOException {
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

  byte[] dmaPacket(int address, byte[] data, int off, int n) {
    byte[] p = Ethlet.DMA_LOAD.newPacket();
    p[dma.romWriteEnable] = 0;
    WindowProtocol.putLe16(p, dma.destAddress, address);
    p[dma.destBank] = (byte) ((address >>> 16) & 0x0f);
    p[dma.destMb] = (byte) (address >>> 20);
    WindowProtocol.putLe16(p, dma.byteCount, n);
    System.arraycopy(data, off, p, dma.data, n);
    return p;
  }

  public void awaitAll(Duration timeout) throws IOException {
    window.awaitAll(timeout);
  }

  /**
   * Sends {@link Ethlet#ALL_DONE_BASIC2}: sets the BASIC end-of-program pointer and optionally
   * RUNs the program.
   */
  public void allDoneBasic2(int dataEndAddress, boolean run, boolean cartSignatureDetection, Duration timeout)
      throws IOException {
    singleCommand(basic2Packet(dataEndAddress, run, new Reset(null, Video.UNCHANGED, cartSignatureDetection)),
        timeout);
  }

  /** PAL/NTSC switch done by the reset ethlets. */
  public enum Video {
    UNCHANGED(0x00), PAL(0x01), NTSC(0xff);

    final int code;

    Video(int code) {
      this.code = code;
    }
  }

  /**
   * Options of the reset ethlets.
   *
   * @param mount D81 image in the SD card root to attach as drive 0, or {@code null}
   * @param video video standard to switch to
   * @param cartridgeDetection honour a CBM80 cartridge signature at $8004 on reset
   */
  public record Reset(String mount, Video video, boolean cartridgeDetection) {
    public Reset {
      Objects.requireNonNull(video);
    }

    public static Reset defaults() {
      return new Reset(null, Video.UNCHANGED, false);
    }

    public Reset withMount(String image) {
      return new Reset(image, video, cartridgeDetection);
    }

    public Reset withVideo(Video v) {
      return new Reset(mount, v, cartridgeDetection);
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
   * Resets into C64 mode ({@link Ethlet#ALL_DONE_BASIC2}).
   *
   * @param programEnd end address of a BASIC program loaded at $0801 (kept across the reset), or
   *     0 for none
   * @param run type RUN after the reset
   */
  public void resetToC64(int programEnd, boolean run, Reset options) throws IOException {
    singleCommand(basic2Packet(programEnd, run, options), Duration.ofSeconds(2));
  }

  private static byte[] basic2Packet(int programEnd, boolean run, Reset options) {
    Ethlet e = Ethlet.ALL_DONE_BASIC2;
    byte[] p = e.newPacket();
    WindowProtocol.putLe16(p, e.offset("data_end_address"), programEnd);
    p[e.offset("do_run")] = (byte) (run ? 1 : 0);
    p[e.offset("enable_cart_signature")] = (byte) (options.cartridgeDetection() ? 1 : 0);
    p[e.offset("restore_prg")] = (byte) (programEnd != 0 ? 1 : 0);
    p[e.offset("set_video_mode")] = (byte) options.video().code;
    putImageName(p, e.offset("d81filename"), options.mount(), 63);
    return p;
  }

  /**
   * Resets into MEGA65 mode ({@link Ethlet#ALL_DONE_BASIC65}). This always detaches the current
   * disk images first, so a reset without {@code mount} leaves drive 0 empty.
   *
   * @param programEnd end address of a BASIC 65 program loaded at $2001 (kept across the reset),
   *     or 0 for none
   * @param run type RUN after the reset
   */
  public void resetToMega65(int programEnd, boolean run, Reset options) throws IOException {
    Ethlet e = Ethlet.ALL_DONE_BASIC65;
    byte[] p = e.newPacket();
    int autostart = e.offset("autostart"); // filesize (LE16), do_run, enable_cart_signature
    WindowProtocol.putLe16(p, autostart, programEnd);
    p[autostart + 2] = (byte) (run ? 1 : 0);
    p[autostart + 3] = (byte) (options.cartridgeDetection() ? 1 : 0);
    p[e.offset("restore_prg")] = (byte) (programEnd != 0 ? 1 : 0);
    p[e.offset("set_video_mode")] = (byte) options.video().code;
    putImageName(p, e.offset("d81filename"), options.mount(), 63);
    singleCommand(p, Duration.ofSeconds(2));
  }

  /**
   * Optionally mounts a D81 (name of at most 15 characters) and jumps to {@code address}
   * ({@link Ethlet#ALL_DONE_JUMP}); no reset, the KERNAL is not initialised.
   */
  public void jump(int address, String mount) throws IOException {
    if (address < 0 || address > 0xffff) {
      throw new IllegalArgumentException("jump address must be $0000-$FFFF");
    }
    Ethlet e = Ethlet.ALL_DONE_JUMP;
    byte[] p = e.newPacket();
    WindowProtocol.putLe16(p, e.offset("jump_addr") + 1, address); // operand of JMP
    putImageName(p, e.offset("d81filename"), mount, 15);
    singleCommand(p, Duration.ofSeconds(2));
  }

  /** Longest disk image name the reset ethlets take; the jump ethlet takes 15. */
  public static final int MAX_IMAGE_NAME = 63;

  /** Validates a disk image name for the ethlets (printable ASCII, at most {@code maxLen}). */
  public static void checkImageName(String name, int maxLen) {
    if (name.isEmpty() || name.length() > maxLen || !name.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
      throw new IllegalArgumentException("disk image name must be 1.." + maxLen + " printable ASCII characters: "
          + name);
    }
  }

  /**
   * Hyppo's dos_setname expects the name as the ethlets get it from etherload.c: letters with
   * their case swapped (PETSCII), NUL-terminated.
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
   * The complete bootstrap of {@code mega65_ftp -e}: trigger the hypervisor, ping, upload
   * {@code prg} (a C64-style program file with 2-byte load address) to {@value #BASIC_START}
   * and RUN it.
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
   * Uploads {@code prg} (a program file loading at {@value #BASIC_START}) and RUNs it in C64
   * mode; the hypervisor must already execute ethlets (see {@link #connect}).
   */
  public void startHelper(byte[] prg) throws IOException {
    checkHelper(prg);
    LOG.log(Level.DEBUG, "uploading helper routine ({0} bytes)", prg.length - 2);
    writeMemory(BASIC_START, prg, 2, prg.length - 2, DEFAULT_TIMEOUT);
    awaitAll(DEFAULT_TIMEOUT);
    // mega65_ftp.c patches data_end_address with $0801, not with the real end address.
    allDoneBasic2(BASIC_START, true, false, Duration.ofSeconds(2));
    LOG.log(Level.DEBUG, "helper routine started");
  }

  private static void checkHelper(byte[] prg) {
    if (prg.length < 3 || WindowProtocol.le16(prg, 0) != BASIC_START) {
      throw new IllegalArgumentException("not a program file loading at $0801");
    }
  }

  /** Swaps in the single-command protocol for one packet, like {@code ethl_single_command}. */
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

  /** The bundled {@code remotesd_eth.prg} (see blobs/PROVENANCE.txt). */
  public static byte[] bundledHelper() {
    return Ethlet.Blobs.read("remotesd_eth.prg");
  }
}
