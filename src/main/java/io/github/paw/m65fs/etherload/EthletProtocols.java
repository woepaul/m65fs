package io.github.paw.m65fs.etherload;

import io.github.paw.m65fs.transport.WindowProtocol;
import java.nio.ByteBuffer;

/**
 * Window protocols for ethlet packets. The MEGA65 answers every ethlet with the unchanged
 * payload, so an answer acknowledges exactly the byte-identical request.
 */
final class EthletProtocols {

  private EthletProtocols() {}

  /** Byte-identical answers acknowledge; the sequence number sits at the ethlet's own offset. */
  private abstract static class Echoed implements WindowProtocol {
    @Override
    public boolean matches(ByteBuffer rx, byte[] tx) {
      return rx.remaining() == tx.length && rx.equals(ByteBuffer.wrap(tx));
    }

    static int seq(Ethlet e, ByteBuffer rx) {
      return rx.remaining() == e.packetSize() ? WindowProtocol.le16(rx, e.seqOffset()) : -1;
    }

    static void embed(Ethlet e, byte[] tx, int seq) {
      if (tx.length == e.packetSize()) {
        WindowProtocol.putLe16(tx, e.seqOffset(), seq);
      }
    }
  }

  /** One-shot ethlets ({@link Ethlet#ECHO}, {@link Ethlet#START}), one at a time. */
  static final WindowProtocol SINGLE_COMMAND = new Echoed() {
    @Override
    public int seqOf(ByteBuffer rx) {
      int s = seq(Ethlet.ECHO, rx);
      return s >= 0 ? s : seq(Ethlet.START, rx);
    }

    @Override
    public void embedSeq(byte[] tx, int seq) {
      embed(tx.length == Ethlet.ECHO.packetSize() ? Ethlet.ECHO : Ethlet.START, tx, seq);
    }
  };

  /**
   * {@link Ethlet#LOAD}: several in flight; two packets for the same destination address must
   * not be in flight at the same time.
   */
  static final class Load extends Echoed {
    static final Load INSTANCE = new Load();

    private Load() {}

    /** The 28-bit destination address encoded in a packet. */
    static int loadAddress(byte[] p) {
      return (p[Ethlet.LOAD_DEST_MB] & 0xff) << 20 | (p[Ethlet.LOAD_DEST_BANK] & 0x0f) << 16
          | WindowProtocol.le16(p, Ethlet.LOAD_DEST);
    }

    @Override
    public int seqOf(ByteBuffer rx) {
      return seq(Ethlet.LOAD, rx);
    }

    @Override
    public void embedSeq(byte[] tx, int seqNum) {
      embed(Ethlet.LOAD, tx, seqNum);
    }

    @Override
    public boolean isDuplicate(byte[] tx, byte[] inFlight) {
      return tx.length == Ethlet.LOAD.packetSize() && inFlight.length == tx.length
          && loadAddress(tx) == loadAddress(inFlight);
    }
  }
}
