package io.github.paw.m65fs.transport;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A UDP socket talking to one {@link Target}. Datagrams from any other address or port are
 * silently dropped, as in {@code etherload_common.c}.
 *
 * <p>Not thread-safe; owned by a single {@link SlidingWindow}.
 */
public final class UdpLink implements AutoCloseable {

  private static final byte[] ALL_NODES = {(byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
  private static final int MAX_DATAGRAM = 2048;

  private final Target target;
  private final InetSocketAddress targetAddress;
  private final byte[] targetAddressBytes;
  private final DatagramChannel channel;
  private final Selector selector;
  private final ByteBuffer rxBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM);

  private UdpLink(Target target, DatagramChannel channel, Selector selector) {
    this.target = target;
    this.targetAddress = target.socketAddress();
    this.targetAddressBytes = target.address().getAddress();
    this.channel = channel;
    this.selector = selector;
  }

  /** Opens an unbound (ephemeral port) IPv6 UDP socket for {@code target}. */
  public static UdpLink open(Target target) throws IOException {
    DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET6);
    try {
      if (target.networkInterface() != null) {
        ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, target.networkInterface());
      }
      ch.bind(null);
      ch.configureBlocking(false);
      Selector sel = Selector.open();
      ch.register(sel, SelectionKey.OP_READ);
      return new UdpLink(target, ch, sel);
    } catch (IOException | RuntimeException e) {
      ch.close();
      throw e;
    }
  }

  public Target target() {
    return target;
  }

  /** Local port of the socket; the remotesd_eth session is bound to it after hello. */
  public int localPort() throws IOException {
    return ((InetSocketAddress) channel.getLocalAddress()).getPort();
  }

  /** Sends {@code len} bytes of {@code packet} to the target. */
  public void send(byte[] packet, int len) throws IOException {
    sendTo(targetAddress, ByteBuffer.wrap(packet, 0, len));
  }

  public void send(byte[] packet) throws IOException {
    send(packet, packet.length);
  }

  /** Sends to {@code ff02::1} on the target's interface (and port). */
  public void sendAllNodes(byte[] packet) throws IOException {
    if (target.networkInterface() == null) {
      throw new IOException("multicast needs a target network interface");
    }
    var group = Inet6Address.getByAddress(null, ALL_NODES, target.networkInterface());
    sendTo(new InetSocketAddress(group, target.port()), ByteBuffer.wrap(packet));
  }

  private void sendTo(InetSocketAddress to, ByteBuffer buf) throws IOException {
    // Non-blocking send returns 0 when the socket buffer is full (EAGAIN in C): retry shortly.
    while (channel.send(buf, to) == 0) {
      LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(20));
      checkInterrupt();
    }
  }

  /**
   * Returns the next pending datagram from the target, or {@code null} if none is queued.
   * The returned buffer is a private copy, positioned at 0 with limit = datagram length.
   */
  public ByteBuffer poll() throws IOException {
    while (true) {
      rxBuffer.clear();
      SocketAddress from = channel.receive(rxBuffer);
      if (from == null) {
        return null;
      }
      if (from instanceof InetSocketAddress isa
          && isa.getPort() == target.port()
          && Arrays.equals(isa.getAddress().getAddress(), targetAddressBytes)) {
        rxBuffer.flip();
        ByteBuffer copy = ByteBuffer.allocate(rxBuffer.remaining());
        copy.put(rxBuffer).flip();
        return copy;
      }
      // Dropping unexpected packet from another host/port
    }
  }

  /** Blocks up to {@code nanos} until a datagram may be readable. */
  public void awaitReadable(long nanos) throws IOException {
    checkInterrupt();
    if (nanos <= 0) {
      selector.selectNow();
    } else {
      selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }
    selector.selectedKeys().clear();
    checkInterrupt();
  }

  /**
   * Selector waits return immediately while the thread's interrupt flag is set, so waiting loops
   * would spin until their timeout; fail fast instead (the flag stays set).
   */
  private static void checkInterrupt() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("interrupted while talking to the MEGA65");
    }
  }

  @Override
  public void close() throws IOException {
    try (selector; channel) {
      // closes both
    }
  }
}
