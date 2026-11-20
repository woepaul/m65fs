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
  /** Load address of a C64/C65 BASIC program. */
  public static final int BASIC_START = 0x0801;
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

  /** Multicasts the magic packet that traps the hypervisor into Ethernet remote control. */
  public void triggerHyperrupt() throws IOException {
    window.link().sendAllNodes(triggerPacket());
    sleep(Duration.ofMillis(10));
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
    Ethlet e = Ethlet.ALL_DONE_BASIC2;
    byte[] p = e.newPacket();
    WindowProtocol.putLe16(p, e.offset("data_end_address"), dataEndAddress);
    p[e.offset("do_run")] = (byte) (run ? 1 : 0);
    p[e.offset("enable_cart_signature")] = (byte) (cartSignatureDetection ? 1 : 0);
    singleCommand(p, timeout);
  }

  /**
   * The complete bootstrap of {@code mega65_ftp -e}: trigger the hypervisor, ping, upload
   * {@code prg} (a C64-style program file with 2-byte load address) to {@value #BASIC_START}
   * and RUN it.
   *
   * @throws Mega65TimeoutException with a hint if the MEGA65 does not answer the ping
   */
  public void bootstrap(byte[] prg) throws IOException {
    if (prg.length < 3 || WindowProtocol.le16(prg, 0) != BASIC_START) {
      throw new IllegalArgumentException("not a program file loading at $0801");
    }
    triggerHyperrupt();
    try {
      ping(DEFAULT_TIMEOUT);
    } catch (Mega65TimeoutException e) {
      throw new Mega65TimeoutException("no response from MEGA65 at " + window.link().target()
          + " (is remote mode active and ETHLOAD.M65 in the SD card root?)");
    }
    LOG.log(Level.DEBUG, "uploading helper routine ({0} bytes)", prg.length - 2);
    writeMemory(BASIC_START, prg, 2, prg.length - 2, DEFAULT_TIMEOUT);
    awaitAll(DEFAULT_TIMEOUT);
    // mega65_ftp.c patches data_end_address with $0801, not with the real end address.
    allDoneBasic2(BASIC_START, true, false, Duration.ofSeconds(2));
    LOG.log(Level.DEBUG, "helper routine started");
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
