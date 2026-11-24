package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.transport.WindowProtocol;
import java.nio.ByteBuffer;

/**
 * Window protocol for the remotesd_eth session ({@code ethernet_*} callbacks in mega65_ftp.c).
 * In addition to the C matching rules, the response opcode must equal the request opcode.
 */
final class SessionProtocol implements WindowProtocol {

  static final SessionProtocol INSTANCE = new SessionProtocol();

  private SessionProtocol() {}

  @Override
  public int seqOf(ByteBuffer rx) {
    if (rx.remaining() < Request.HEADER || rx.get(0) != 'm' || rx.get(1) != 'r' || rx.get(2) != 's' || rx.get(3) != 'p') {
      return -1;
    }
    return WindowProtocol.le16(rx, 4);
  }

  @Override
  public void embedSeq(byte[] tx, int seq) {
    WindowProtocol.putLe16(tx, 4, seq);
  }

  @Override
  public boolean matches(ByteBuffer rx, byte[] tx) {
    int len = rx.remaining();
    if (len < Request.HEADER || tx.length < Request.HEADER || seqOf(rx) < 0 || rx.get(6) != tx[6]) {
      return false;
    }
    return switch (tx[6] & 0xff) {
      // batch counter, batch size, slot, sector
      case Request.OP_WRITE_SECTOR -> len == 14 && equal(rx, tx, 7, 7);
      // sector number (one virtual request per sector)
      case Request.OP_READ_SECTORS -> len == 13 + 512 && equal(rx, tx, 9, 4);
      // byte count, address
      case Request.OP_READ_MEMORY -> len >= 13 && equal(rx, tx, 7, 5);
      // same sequence number
      case Request.OP_MOUNT -> len == 8 && equal(rx, tx, 4, 2);
      case Request.OP_CWD -> len == 13 && equal(rx, tx, 4, 2);
      case Request.OP_HELLO, Request.OP_QUIT -> len == 7;
      default -> false;
    };
  }

  private static boolean equal(ByteBuffer rx, byte[] tx, int off, int len) {
    for (int i = 0; i < len; i++) {
      if (rx.get(off + i) != tx[off + i]) {
        return false;
      }
    }
    return true;
  }
}
