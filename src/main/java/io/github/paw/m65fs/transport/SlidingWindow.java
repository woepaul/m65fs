package io.github.paw.m65fs.transport;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Selective-repeat window over a {@link UdpLink}; a port of the reliability layer in
 * {@code etherload_common.c}.
 *
 * <ul>
 *   <li>Up to {@link #capacity()} unacknowledged packets (at most {@value #MAX_SLOTS}).
 *   <li>Each scheduled packet gets the next 16-bit sequence number embedded.
 *   <li>Every datagram from the target with a valid sequence number frees the first in-flight
 *       packet it {@linkplain WindowProtocol#matches matches} and is handed to that packet's
 *       callback.
 *   <li>Only the <em>oldest</em> unacknowledged packet is retransmitted, once the retransmit
 *       interval {@code clamp(2ms + retries * lastRxInterval, 50ms, 1s)} has elapsed since the
 *       last (re)send or ack.
 *   <li>If the oldest packet is unacknowledged for longer than {@link #ACK_TIMEOUT} and nothing
 *       at all arrived for that long, the link is considered dead.
 * </ul>
 *
 * <p>Single-threaded: all progress happens inside the calling thread's {@code await*} /
 * {@code schedule} calls. Not thread-safe.
 */
public final class SlidingWindow {

  public static final int MAX_SLOTS = 256;
  public static final Duration ACK_TIMEOUT = Duration.ofSeconds(4);

  private static final Logger LOG = System.getLogger(SlidingWindow.class.getName());
  private static final long MIN_RETX = TimeUnit.MILLISECONDS.toNanos(50);
  private static final long MAX_RETX = TimeUnit.SECONDS.toNanos(1);
  private static final long BASE_RETX = TimeUnit.MILLISECONDS.toNanos(2);

  /** An unacknowledged packet. */
  private record InFlight(byte[] packet, long sentAt, Consumer<ByteBuffer> onAck) {}

  private final UdpLink link;
  private final InFlight[] slots = new InFlight[MAX_SLOTS];
  private WindowProtocol protocol;
  private int capacity = 4;
  private int inFlightCount;
  private int nextSeq;

  private long lastResend = System.nanoTime();
  private long lastRx = -1;
  private long lastRxInterval;
  private int retxCount;
  private long retxInterval = MIN_RETX;
  private long retransmissions;

  public SlidingWindow(UdpLink link, WindowProtocol protocol) {
    this.link = Objects.requireNonNull(link);
    this.protocol = Objects.requireNonNull(protocol);
  }

  public UdpLink link() {
    return link;
  }

  public WindowProtocol protocol() {
    return protocol;
  }

  /** Replaces the packet format. Only allowed while nothing is in flight. */
  public void setProtocol(WindowProtocol protocol) {
    requireIdle("change protocol");
    this.protocol = Objects.requireNonNull(protocol);
  }

  public int capacity() {
    return capacity;
  }

  /** Sets the number of packets that may be unacknowledged at once. Only allowed while idle. */
  public void setCapacity(int capacity) {
    if (capacity < 1 || capacity > MAX_SLOTS) {
      throw new IllegalArgumentException("capacity must be 1.." + MAX_SLOTS + ": " + capacity);
    }
    if (capacity != this.capacity) {
      requireIdle("change capacity");
    }
    this.capacity = capacity;
  }

  /** The sequence number the next scheduled packet will get. */
  public int nextSeq() {
    return nextSeq;
  }

  public int inFlight() {
    return inFlightCount;
  }

  /** Total number of retransmitted packets (diagnostics). */
  public long retransmissions() {
    return retransmissions;
  }

  /**
   * Schedules {@code packet} (embedding the next sequence number into it) and sends it.
   * Ownership of the array passes to the window; do not modify it afterwards.
   */
  public void submit(byte[] packet, Consumer<ByteBuffer> onAck, Duration timeout) throws IOException {
    schedule(packet, onAck, timeout);
    link.send(packet);
  }

  /**
   * Registers {@code packet} as awaiting an answer without sending it now. It is only
   * transmitted if a retransmit becomes necessary. Used for read requests where one request
   * yields several answers.
   */
  public void schedule(byte[] packet, Consumer<ByteBuffer> onAck, Duration timeout) throws IOException {
    Objects.requireNonNull(packet);
    Objects.requireNonNull(onAck);
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      int free = -1;
      boolean duplicate = false;
      for (int i = 0; i < MAX_SLOTS; i++) {
        InFlight f = slots[i];
        if (f != null) {
          if (protocol.isDuplicate(packet, f.packet)) {
            duplicate = true;
            break;
          }
        } else if (free < 0 && i < capacity) {
          free = i;
        }
      }
      if (free >= 0 && !duplicate && inFlightCount < capacity) {
        protocol.embedSeq(packet, nextSeq);
        nextSeq = (nextSeq + 1) & 0xffff;
        long now = System.nanoTime();
        slots[free] = new InFlight(packet, now, onAck);
        inFlightCount++;
        lastResend = now;
        updateRetxInterval();
        return;
      }
      pump(deadline, "a free window slot");
    }
  }

  /** Sends a packet outside the window (no ack expected, no sequence number embedded). */
  public void sendUnscheduled(byte[] packet) throws IOException {
    link.send(packet);
  }

  /** Waits until at least {@code n} slots are free. */
  public void awaitFreeSlots(int n, Duration timeout) throws IOException {
    if (n > capacity) {
      throw new IllegalArgumentException("need " + n + " slots but capacity is " + capacity);
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    while (capacity - inFlightCount < n) {
      pump(deadline, n + " free window slots");
    }
  }

  /** Waits until every in-flight packet has been acknowledged. */
  public void awaitAll(Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (inFlightCount > 0) {
      pump(deadline, "acknowledgement of " + inFlightCount + " packet(s)");
    }
  }

  /** Processes incoming packets and due retransmits once, blocking until data or the next deadline. */
  private void pump(long deadline, String waitingFor) throws IOException {
    int before = inFlightCount;
    drain();
    resendIfDue();
    if (inFlightCount != before || inFlightCount == 0) {
      return; // progress: let the caller re-check its condition before blocking
    }
    long now = System.nanoTime();
    if (now - deadline >= 0) {
      throw new Mega65TimeoutException("timeout waiting for " + waitingFor);
    }
    long untilResend = lastResend + retxInterval - now;
    link.awaitReadable(Math.max(0, Math.min(untilResend, deadline - now)));
  }

  private void drain() throws IOException {
    ByteBuffer rx;
    while ((rx = link.poll()) != null) {
      onReceive(rx);
    }
  }

  private void onReceive(ByteBuffer rx) {
    int seq = protocol.seqOf(rx);
    if (seq < 0) {
      return;
    }
    long now = System.nanoTime();
    lastRxInterval = lastRx == -1 ? 0 : now - lastRx;
    lastRx = now;
    retxCount = 0;
    updateRetxInterval();
    for (int i = 0; i < MAX_SLOTS; i++) {
      InFlight f = slots[i];
      if (f != null && protocol.matches(rx.duplicate(), f.packet)) {
        slots[i] = null;
        inFlightCount--;
        lastResend = now;
        f.onAck.accept(rx.duplicate());
        return;
      }
    }
    LOG.log(Level.TRACE, "no in-flight packet matches received seq {0}", seq);
  }

  private void resendIfDue() throws IOException {
    InFlight oldest = null;
    for (InFlight f : slots) {
      if (f != null && (oldest == null || f.sentAt - oldest.sentAt < 0)) {
        oldest = f;
      }
    }
    if (oldest == null) {
      return;
    }
    long now = System.nanoTime();
    if (now - lastResend <= retxInterval) {
      return;
    }
    long ackTimeout = ACK_TIMEOUT.toNanos();
    if (now - oldest.sentAt > ackTimeout && lastRx != -1 && now - lastRx > ackTimeout) {
      throw new Mega65TimeoutException("MEGA65 stopped responding (no packet for " + ACK_TIMEOUT.toSeconds() + "s)");
    }
    retxCount++;
    retransmissions++;
    updateRetxInterval();
    byte[] packet = oldest.packet;
    LOG.log(Level.TRACE, () -> "retransmitting seq " + protocol.seqOf(ByteBuffer.wrap(packet)));
    link.send(packet);
    lastResend = System.nanoTime();
  }

  private void updateRetxInterval() {
    long v = BASE_RETX + retxCount * lastRxInterval;
    retxInterval = Math.clamp(v, MIN_RETX, MAX_RETX);
  }

  private void requireIdle(String what) {
    if (inFlightCount != 0) {
      throw new IllegalStateException("cannot " + what + " while " + inFlightCount + " packet(s) are in flight");
    }
  }
}
