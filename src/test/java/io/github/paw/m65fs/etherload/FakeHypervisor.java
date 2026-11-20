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
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emulates the hypervisor's ethlet execution on {@code [::1]}: echo ethlets are echoed, DMA
 * load ethlets copy their data into {@link #memory} (first 2 MiB only) and are echoed. Incoming
 * and outgoing packets are dropped with probability {@code loss}; answers are duplicated with
 * probability {@code dup}.
 */
final class FakeHypervisor implements AutoCloseable {

  final byte[] memory = new byte[2 << 20];
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

  FakeHypervisor(double loss, double dup, long seed) throws IOException {
    this.loss = loss;
    this.dup = dup;
    this.rnd = new Random(seed);
    ch = DatagramChannel.open(StandardProtocolFamily.INET6);
    ch.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
    thread = Thread.ofPlatform().daemon().name("fake-hypervisor").start(this::run);
  }

  Target target() throws IOException {
    var local = (InetSocketAddress) ch.getLocalAddress();
    return new Target((Inet6Address) local.getAddress(), local.getPort(), null);
  }

  private void run() {
    ByteBuffer buf = ByteBuffer.allocate(2048);
    var dma = EthletProtocols.DmaLoad.INSTANCE;
    int doRun = Ethlet.ALL_DONE_BASIC2.offset("do_run");
    int endAddr = Ethlet.ALL_DONE_BASIC2.offset("data_end_address");
    byte[] allDone = Ethlet.ALL_DONE_BASIC2.newPacket();
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
        } else if (p.length == 1024 && p[0x10] == allDone[0x10] && p[0x200] == allDone[0x200]) {
          // crude detection of the all_done ethlet by its code bytes
          basicEnd = WindowProtocol.le16(p, endAddr);
          basicRun = p[doRun] != 0;
        } else if (p.length != 1024) {
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
