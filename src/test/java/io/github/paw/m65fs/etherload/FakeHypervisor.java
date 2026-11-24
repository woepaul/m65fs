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
 * Emulates the hypervisor's trigger and ETHLOAD's ethlet execution on {@code [::1]}: after a
 * trigger every ethlet is echoed (until START takes the machine over), LOAD ethlets also copy
 * their data into {@link #memory} (first 2 MiB only). Incoming
 * and outgoing packets are dropped with probability {@code loss}; answers are duplicated with
 * probability {@code dup}.
 */
public final class FakeHypervisor implements AutoCloseable {

  public final byte[] memory = new byte[2 << 20];
  /** Every command ethlet received (echo, reset and jump ethlets), in order, with its kind. */
  public final List<Command> commands = Collections.synchronizedList(new ArrayList<>());
  public final AtomicInteger triggers = new AtomicInteger();
  /** Whether ETHLOAD.M65 runs (after a trigger, until START takes the machine over). */
  volatile boolean ethloadRuns;

  /** A received ECHO or START ethlet. */
  public record Command(Ethlet kind, byte[] packet) {
    /** The NUL-terminated disk image name, case-swapped back to ASCII ("" = none). */
    public String imageName() {
      StringBuilder sb = new StringBuilder();
      for (int i = Ethlet.START_IMAGE; packet[i] != 0; i++) {
        int c = packet[i] & 0xff;
        int u = c & 0xdf;
        sb.append((char) (u >= 'A' && u <= 'Z' ? c ^ 0x20 : c));
      }
      return sb.toString();
    }

    public int mode() {
      return packet[Ethlet.START_MODE] & 0xff;
    }

    public boolean run() {
      return (packet[Ethlet.START_FLAGS] & Ethlet.START_FLAG_RUN) != 0;
    }

    public int video() {
      return packet[Ethlet.START_VIDEO] & 0xff;
    }

    /** End address of the program, or the jump address. */
    public int address() {
      return WindowProtocol.le16(packet, Ethlet.START_ADDRESS);
    }
  }

  public List<Command> commands(Ethlet kind) {
    synchronized (commands) {
      return commands.stream().filter(c -> c.kind() == kind).toList();
    }
  }

  /** START commands with the given mode. */
  public List<Command> starts(int mode) {
    return commands(Ethlet.START).stream().filter(c -> c.mode() == mode).toList();
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
        Ethlet kind = Ethlet.of(p);
        if (kind != null && !ethloadRuns) {
          continue; // no trigger yet (or the machine was taken over): nobody runs ethlets
        }
        if (kind == Ethlet.LOAD) {
          int addr = EthletProtocols.Load.loadAddress(p);
          int n = WindowProtocol.le16(p, Ethlet.LOAD_COUNT);
          System.arraycopy(p, Ethlet.LOAD_DATA, memory, addr, n);
          dmaPackets.incrementAndGet();
        } else if (kind != null) {
          commands.add(new Command(kind, p));
          if (kind == Ethlet.START && p[Ethlet.START_MODE] == Ethlet.START_MODE_C64) {
            basicEnd = WindowProtocol.le16(p, Ethlet.START_ADDRESS);
            basicRun = (p[Ethlet.START_FLAGS] & Ethlet.START_FLAG_RUN) != 0;
          }
        } else {
          if (p.length == 128) {
            triggers.incrementAndGet();
            ethloadRuns = true;
          }
          continue;
        }
        reply(p, from);
        if (rnd.nextDouble() < dup) {
          reply(p, from);
        }
        if (kind == Ethlet.START) {
          ethloadRuns = false; // reset or jump: ETHLOAD.M65 is gone
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
