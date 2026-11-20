package io.github.paw.m65fs.etherload;

import io.github.paw.m65fs.transport.WindowProtocol;
import java.nio.ByteBuffer;

/** Window protocols for ethlet packets ({@code dmaload_*} / {@code singlecmd_*} in etherload_common.c). */
final class EthletProtocols {

  private EthletProtocols() {}

  /**
   * One-shot 1024-byte ethlets ({@link Ethlet#ECHO}, {@link Ethlet#ALL_DONE_BASIC2}): sequence
   * number at offset 1022, any 1024-byte answer acknowledges.
   */
  static final WindowProtocol SINGLE_COMMAND = new WindowProtocol() {
    private static final int LEN = 1024;
    private static final int SEQ = 1022;

    @Override
    public int seqOf(ByteBuffer rx) {
      return rx.remaining() == LEN ? WindowProtocol.le16(rx, SEQ) : -1;
    }

    @Override
    public void embedSeq(byte[] tx, int seq) {
      if (tx.length == LEN) {
        WindowProtocol.putLe16(tx, SEQ, seq);
      }
    }

    @Override
    public boolean matches(ByteBuffer rx, byte[] tx) {
      return rx.remaining() == LEN && tx.length == LEN;
    }
  };

  /**
   * {@link Ethlet#DMA_LOAD}: the ethlet echoes the complete 1280-byte packet; it is acknowledged
   * by a byte-identical answer. Two packets for the same destination address must not be in
   * flight at the same time.
   */
  static final class DmaLoad implements WindowProtocol {
    static final DmaLoad INSTANCE = new DmaLoad();

    final int len = Ethlet.DMA_LOAD.packetSize();
    final int seq = Ethlet.DMA_LOAD.offset("seq_num");
    final int destAddress = Ethlet.DMA_LOAD.offset("dest_address");
    final int destBank = Ethlet.DMA_LOAD.offset("dest_bank");
    final int destMb = Ethlet.DMA_LOAD.offset("dest_mb");
    final int byteCount = Ethlet.DMA_LOAD.offset("byte_count");
    final int data = Ethlet.DMA_LOAD.offset("data");
    final int romWriteEnable = Ethlet.DMA_LOAD.offset("rom_write_enable") + 1; // operand of LDA #$xx

    private DmaLoad() {}

    /** The 28-bit destination address encoded in a packet. */
    int loadAddress(byte[] p) {
      return (p[destMb] & 0xff) << 20 | (p[destBank] & 0x0f) << 16 | WindowProtocol.le16(p, destAddress);
    }

    @Override
    public int seqOf(ByteBuffer rx) {
      return rx.remaining() == len ? WindowProtocol.le16(rx, seq) : -1;
    }

    @Override
    public void embedSeq(byte[] tx, int seqNum) {
      if (tx.length == len) {
        WindowProtocol.putLe16(tx, seq, seqNum);
      }
    }

    @Override
    public boolean matches(ByteBuffer rx, byte[] tx) {
      return rx.remaining() == tx.length && rx.equals(ByteBuffer.wrap(tx));
    }

    @Override
    public boolean isDuplicate(byte[] tx, byte[] inFlight) {
      return tx.length == len && inFlight.length == len && loadAddress(tx) == loadAddress(inFlight);
    }
  }
}
