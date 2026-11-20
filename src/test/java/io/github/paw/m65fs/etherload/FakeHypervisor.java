package io.github.paw.m65fs.etherload;

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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emulates the hypervisor's ethlet execution on {@code [::1]}: echo ethlets are echoed, DMA
 * load ethlets copy their data into {@link #memory} (first 2 MiB only) and are echoed. Incoming
 * and outgoing packets are dropped with probability {@code loss}; answers are duplicated with
 * probability {@code dup}.
 */
public final class FakeHypervisor implements AutoCloseable {

  public final byte[] memory = new byte[2 << 20];
  /** Every command ethlet received (echo, reset and jump ethlets), in order, with its kind. */
  public final List<Command> commands = Collections.synchronizedList(new ArrayList<>());
  public final AtomicInteger triggers = new AtomicInteger();

  /** A received 1024-byte ethlet. */
  public record Command(Ethlet kind, byte[] packet) {
    /** The NUL-terminated disk image name at {@code label}, case-swapped back to ASCII. */
    public String imageName() {
      int off = kind.offset("d81filename");
      StringBuilder sb = new StringBuilder();
      for (int i = off; packet[i] != 0; i++) {
        int c = packet[i] & 0xff;
        int u = c & 0xdf;
        sb.append((char) (u >= 'A' && u <= 'Z' ? c ^ 0x20 : c));
      }
      return sb.toString();
    }

    public int u8(String label, int delta) {
      return packet[kind.offset(label) + delta] & 0xff;
    }

    public int le16(String label, int delta) {
      return WindowProtocol.le16(packet, kind.offset(label) + delta);
    }
  }

  private static final List<Ethlet> COMMAND_ETHLETS =
      List.of(Ethlet.ECHO, Ethlet.ALL_DONE_BASIC2, Ethlet.ALL_DONE_BASIC65, Ethlet.ALL_DONE_JUMP);

  /** The ethlet a 1024-byte packet was made from (most bytes in common with its image). */
  static Ethlet identify(byte[] p) {
    Ethlet best = null;
    int bestScore = -1;
    for (Ethlet e : COMMAND_ETHLETS) {
      byte[] img = e.newPacket();
      int score = 0;
      for (int i = 0; i < img.length; i++) {
        if (img[i] == p[i]) {
          score++;
        }
      }
      if (score > bestScore) {
        bestScore = score;
        best = e;
      }
    }
    return best;
  }

  public List<Command> commands(Ethlet kind) {
    synchronized (commands) {
      return commands.stream().filter(c -> c.kind() == kind).toList();
    }
  }
  final AtomicInteger dmaPackets = new AtomicInteger();
  final AtomicInteger dropped = new AtomicInteger();
  volatile boolean basicRun;
  volatile int basicEnd = -1;

  private final DatagramChannel ch;
  private final Thread thread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Random rnd;
  private final double loss;
  private final double dup;

  public FakeHypervisor(double loss, double dup, long seed) throws IOException {
    this.loss = loss;
    this.dup = dup;
    this.rnd = new Random(seed);
    ch = DatagramChannel.open(StandardProtocolFamily.INET6);
    ch.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
    thread = Thread.ofPlatform().daemon().name("fake-hypervisor").start(this::run);
  }

  public Target target() throws IOException {
    var local = (InetSocketAddress) ch.getLocalAddress();
    return new Target((Inet6Address) local.getAddress(), local.getPort(), null);
  }

  private void run() {
    ByteBuffer buf = ByteBuffer.allocate(2048);
    var dma = EthletProtocols.DmaLoad.INSTANCE;
    int doRun = Ethlet.ALL_DONE_BASIC2.offset("do_run");
    int endAddr = Ethlet.ALL_DONE_BASIC2.offset("data_end_address");
    while (running.get()) {
      try {
        buf.clear();
        SocketAddress from = ch.receive(buf);
        buf.flip();
        byte[] p = new byte[buf.remaining()];
        buf.get(p);
        if (rnd.nextDouble() < loss) {
          dropped.incrementAndGet();
          continue;
        }
        if (p.length == 1280) {
          int addr = dma.loadAddress(p);
          int n = WindowProtocol.le16(p, dma.byteCount);
          System.arraycopy(p, dma.data, memory, addr, n);
          dmaPackets.incrementAndGet();
        } else if (p.length == 1024) {
          Ethlet kind = identify(p);
          commands.add(new Command(kind, p));
          if (kind == Ethlet.ALL_DONE_BASIC2) {
            basicEnd = WindowProtocol.le16(p, endAddr);
            basicRun = p[doRun] != 0;
          }
        } else {
          if (p.length == 128) {
            triggers.incrementAndGet();
          }
          continue;
        }
        reply(p, from);
        if (rnd.nextDouble() < dup) {
          reply(p, from);
        }
      } catch (IOException e) {
        if (running.get()) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private void reply(byte[] p, SocketAddress to) throws IOException {
    if (rnd.nextDouble() < loss) {
      dropped.incrementAndGet();
      return;
    }
    ch.send(ByteBuffer.wrap(p), to);
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
