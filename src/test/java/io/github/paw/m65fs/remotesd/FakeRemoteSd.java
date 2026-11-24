package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.fat32.DirEntry;
import io.github.paw.m65fs.fat32.Fat32Volume;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates the transfer helper (src/main/asm/helper/helper.a) on {@code [::1]}, backed by a
 * {@link BlockDevice}. It mirrors the helper's state machine (session lock on hello, single vs. batch writes,
 * batch counters, one read batch at a time, read/write mixing) and records every condition where
 * the real helper would call {@code stop_fatal()} in {@link #fatalErrors()} — after which it stops
 * answering, like the real thing.
 *
 * <p>Packets are dropped in both directions with probability {@code loss} and answers duplicated
 * with probability {@code dup}.
 */
public final class FakeRemoteSd implements AutoCloseable {

  private static final int SECTOR = BlockDevice.SECTOR_SIZE;

  private final BlockDevice dev;
  private final DatagramChannel ch;
  private final Thread thread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Random rnd;
  private final double loss;
  private final double dup;
  private final List<String> fatal = Collections.synchronizedList(new ArrayList<>());
  private final List<String> mounted = Collections.synchronizedList(new ArrayList<>());

  volatile int modelId = 0x03;
  public volatile int mountStatus = 0;
  /** Whether the simulated hypervisor has trap_dos_getcwd (opcode 0x13 answers an error otherwise). */
  public volatile boolean cwdSupported = true;
  /** The simulated hypervisor's working directory cluster (0 = root) and drive. */
  public volatile int cwdCluster = 0;
  public volatile int cwdDrive = 0;
  public volatile boolean quit;
  /** Ignores every packet (the MEGA65 hangs or the cable is pulled). */
  public volatile boolean silent;
  volatile int sectorsWritten;
  volatile int readRequests;

  // session
  private SocketAddress client;
  // write state
  private int currentBatchCounter = 0xff;
  private boolean writeBatchActive;
  private int writeBatchMaxId;
  private int batchLeft;
  private long sectorNumberWrite;
  private final boolean[] slotReceived = new boolean[256];
  private final byte[][] cache = new byte[128][];

  public FakeRemoteSd(BlockDevice dev, double loss, double dup, long seed) throws IOException {
    this.dev = dev;
    this.loss = loss;
    this.dup = dup;
    this.rnd = new Random(seed);
    ch = DatagramChannel.open(StandardProtocolFamily.INET6);
    ch.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
    thread = Thread.ofPlatform().daemon().name("fake-remotesd").start(this::run);
  }

  public Target target() throws IOException {
    var local = (InetSocketAddress) ch.getLocalAddress();
    return new Target((Inet6Address) local.getAddress(), local.getPort(), null);
  }

  public List<String> fatalErrors() {
    return List.copyOf(fatal);
  }

  public List<String> mounted() {
    return List.copyOf(mounted);
  }

  private void run() {
    ByteBuffer buf = ByteBuffer.allocate(2048);
    while (running.get()) {
      try {
        buf.clear();
        SocketAddress from = ch.receive(buf);
        buf.flip();
        byte[] p = new byte[buf.remaining()];
        buf.get(p);
        if (silent || !fatal.isEmpty() || rnd.nextDouble() < loss) {
          continue;
        }
        handle(p, from);
      } catch (IOException e) {
        if (running.get()) {
          fatal.add("I/O: " + e);
        }
      }
    }
  }

  private void handle(byte[] p, SocketAddress from) throws IOException {
    if (p.length < 7 || p[0] != 'm' || p[1] != 'r' || p[2] != 'e' || p[3] != 'q') {
      return;
    }
    int op = p[6] & 0xff;
    if (client == null) {
      if (op != Request.OP_HELLO) {
        return; // unauthenticated
      }
    } else if (!client.equals(from)) {
      if (op == Request.OP_HELLO) {
        fatal.add("hello requests from multiple clients");
      }
      return;
    }
    switch (op) {
      case Request.OP_HELLO -> {
        client = from;
        reply(header(p, 7), from);
      }
      case Request.OP_QUIT -> {
        quit = true;
        reply(header(p, 7), from);
        // the MEGA65 resets; the next session starts a new helper
        client = null;
        currentBatchCounter = 0xff;
        writeBatchActive = false;
      }
      case Request.OP_READ_MEMORY -> {
        if (p.length != 12) {
          return;
        }
        int n = (p[7] & 0xff) + 1;
        int addr = (int) WindowProtocol.le32(p, 8);
        byte[] r = header(p, 12 + n);
        System.arraycopy(p, 7, r, 7, 5);
        for (int i = 0; i < n; i++) {
          r[12 + i] = (byte) (addr + i == RemoteSdSession.MODEL_ID_ADDRESS ? modelId : 0);
        }
        reply(r, from);
      }
      case Request.OP_MOUNT -> {
        int end = 7;
        while (end < p.length && p[end] != 0) {
          end++;
        }
        if (end == p.length) {
          return; // not null-terminated
        }
        mounted.add(new String(p, 7, end - 7, java.nio.charset.StandardCharsets.ISO_8859_1));
        byte[] r = header(p, 8);
        r[7] = (byte) mountStatus;
        cwdCluster = rootCluster(); // the helper's mount changes to the root directory
        reply(r, from);
      }
      case Request.OP_CWD -> cwd(p, from);
      case Request.OP_READ_SECTORS -> readSectors(p, from);
      case Request.OP_WRITE_SECTOR -> writeSector(p, from);
      default -> { }
    }
  }

  /**
   * Like the hypervisor: cdroot, then setname/findfile/chdir for each name, compared ignoring case
   * with the long name if there is one, otherwise with the 8.3 name.
   */
  private void cwd(byte[] p, SocketAddress from) throws IOException {
    if (p.length < 8 || p[7] > 1 || (p[7] == 1 && (p.length < 9 || p[p.length - 1] != 0))) {
      return;
    }
    byte[] r = header(p, 13);
    int status = cwdSupported ? 0 : 0x81;
    if (status == 0 && p[7] == 1) {
      Fat32Volume volume = Fat32Volume.open(dev);
      DirEntry cur = volume.root();
      String path = new String(p, 8, p.length - 9, java.nio.charset.StandardCharsets.ISO_8859_1);
      for (String n : path.split("/")) {
        if (n.isEmpty()) {
          continue;
        }
        DirEntry d = cur;
        cur = volume.list(d).stream().filter(e -> e.name().equalsIgnoreCase(n)).findFirst().orElse(null);
        if (cur == null || !cur.isDirectory()) {
          status = 0x88; // file not found
          break;
        }
      }
      if (cur != null) {
        cwdCluster = cur.firstCluster();
      }
    }
    r[7] = (byte) status;
    if (status == 0) {
      r[8] = (byte) cwdDrive;
      for (int i = 0; i < 4; i++) {
        r[9 + i] = (byte) (cwdCluster >>> (8 * i));
      }
    }
    reply(r, from);
  }

  /** The root directory cluster, or 0 if the device holds no FAT32 file system. */
  private int rootCluster() {
    try {
      return Fat32Volume.open(dev).root().firstCluster();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  private void readSectors(byte[] p, SocketAddress from) throws IOException {
    if (writeBatchActive) {
      fatal.add("error: read/write requests mixed up");
      return;
    }
    if (p.length != 13) {
      return;
    }
    if (p[8] != 0) {
      fatal.add("error: sector count > 255 not supported");
      return;
    }
    int n = (p[7] & 0xff) + 1;
    if (n > 128) {
      fatal.add("batchleft out of bounds");
      return;
    }
    readRequests++;
    long sector = WindowProtocol.le32(p, 9);
    int seq = WindowProtocol.le16(p, 4);
    for (int i = 0; i < n; i++) {
      ByteBuffer data = ByteBuffer.allocate(SECTOR);
      dev.read(sector + i, data);
      byte[] r = new byte[13 + SECTOR];
      r[0] = 'm'; r[1] = 'r'; r[2] = 's'; r[3] = 'p';
      WindowProtocol.putLe16(r, 4, seq + i);
      r[6] = Request.OP_READ_SECTORS;
      WindowProtocol.putLe32(r, 9, sector + i);
      System.arraycopy(data.array(), 0, r, 13, SECTOR);
      reply(r, from);
    }
  }

  private void writeSector(byte[] p, SocketAddress from) throws IOException {
    if (p.length != 14 + SECTOR) {
      return;
    }
    int counter = p[7] & 0xff;
    int nm1 = p[8] & 0xff;
    int slot = p[9] & 0xff;
    long start = WindowProtocol.le32(p, 10);
    byte[] data = Arrays.copyOfRange(p, 14, 14 + SECTOR);

    if (nm1 == 0) {
      if (writeBatchActive) {
        if (outdated(currentBatchCounter, counter)) {
          return; // late retransmit of an old batch: ignored, no answer
        }
        fatal.add("error: single/multi write conflict");
        return;
      }
      if (outdated(currentBatchCounter, counter)) {
        return; // late retransmission: the host has the answer already, ignored
      }
      currentBatchCounter = counter;
      dev.write(start + slot, ByteBuffer.wrap(data));
      sectorsWritten++;
      byte[] r = header(p, 14);
      r[7] = (byte) counter;
      r[8] = 0;
      r[9] = 0;
      WindowProtocol.putLe32(r, 10, start + slot);
      reply(r, from);
      return;
    }

    if (!writeBatchActive) {
      if (counter != currentBatchCounter) {
        int size = nm1 + 1;
        if (size < 2 || size > 128) {
          fatal.add("error: batch size out of bounds");
          return;
        }
        writeBatchActive = true;
        writeBatchMaxId = nm1;
        batchLeft = size;
        sectorNumberWrite = start;
        currentBatchCounter = counter;
        Arrays.fill(slotReceived, false);
      }
    }
    if (counter == currentBatchCounter && writeBatchActive) {
      if (slot > writeBatchMaxId) {
        fatal.add("error: slots written out of bounds");
        return;
      }
      if (slotReceived[slot]) {
        if (!Arrays.equals(cache[slot], data)) {
          fatal.add("duplicate packet with different data");
          return;
        }
      } else {
        slotReceived[slot] = true;
        cache[slot] = data;
        if (--batchLeft == 0) {
          for (int i = 0; i <= writeBatchMaxId; i++) {
            dev.write(sectorNumberWrite + i, ByteBuffer.wrap(cache[i]));
            sectorsWritten++;
          }
          writeBatchActive = false;
        }
      }
    }
    byte[] r = header(p, 14);
    r[7] = (byte) counter;
    r[8] = (byte) nm1;
    r[9] = (byte) slot;
    WindowProtocol.putLe32(r, 10, start);
    reply(r, from);
  }

  static boolean outdated(int previous, int received) {
    if (received == previous) {
      return false;
    }
    return ((previous - received) & 0xff) < 0x80;
  }

  private static byte[] header(byte[] request, int len) {
    byte[] r = new byte[len];
    r[0] = 'm'; r[1] = 'r'; r[2] = 's'; r[3] = 'p';
    r[4] = request[4];
    r[5] = request[5];
    r[6] = request[6];
    return r;
  }

  private void reply(byte[] r, SocketAddress to) throws IOException {
    if (rnd.nextDouble() < loss) {
      return;
    }
    ch.send(ByteBuffer.wrap(r), to);
    if (rnd.nextDouble() < dup) {
      ch.send(ByteBuffer.wrap(r), to);
    }
  }

  @Override
  public void close() throws IOException {
    running.set(false);
    ch.close();
    try {
      thread.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
