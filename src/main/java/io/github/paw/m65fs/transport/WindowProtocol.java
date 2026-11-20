package io.github.paw.m65fs.transport;

import java.nio.ByteBuffer;

/**
 * Packet format knowledge the {@link SlidingWindow} needs; the Java counterpart of the
 * callbacks in {@code etherload_common.c}.
 */
public interface WindowProtocol {

  /** Sequence number of a received packet, or {@code -1} if it does not belong to this protocol. */
  int seqOf(ByteBuffer rx);

  /** Writes {@code seq} (16 bit) into an outgoing packet. */
  void embedSeq(byte[] tx, int seq);

  /** Whether {@code rx} acknowledges/answers the in-flight packet {@code tx}. */
  boolean matches(ByteBuffer rx, byte[] tx);

  /**
   * Whether {@code tx} must not be sent while {@code inFlight} is still unacknowledged
   * (e.g. two DMA loads to the same address).
   */
  default boolean isDuplicate(byte[] tx, byte[] inFlight) {
    return false;
  }

  static int le16(ByteBuffer b, int offset) {
    return (b.get(offset) & 0xff) | (b.get(offset + 1) & 0xff) << 8;
  }

  static int le16(byte[] b, int offset) {
    return (b[offset] & 0xff) | (b[offset + 1] & 0xff) << 8;
  }

  static void putLe16(byte[] b, int offset, int value) {
    b[offset] = (byte) value;
    b[offset + 1] = (byte) (value >>> 8);
  }

  static long le32(ByteBuffer b, int offset) {
    return (le16(b, offset) | (long) le16(b, offset + 2) << 16);
  }

  static long le32(byte[] b, int offset) {
    return (le16(b, offset) | (long) le16(b, offset + 2) << 16);
  }

  static void putLe32(byte[] b, int offset, long value) {
    putLe16(b, offset, (int) value);
    putLe16(b, offset + 2, (int) (value >>> 16));
  }
}
