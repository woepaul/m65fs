package io.github.paw.m65fs.remotesd;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.transport.WindowProtocol;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Answers from remotesd_eth: header {@code "mrsp", seq (LE16), opcode}. */
public sealed interface Response {

  int seq();

  record HelloAck(int seq) implements Response {}

  record QuitAck(int seq) implements Response {}

  /** One sector of a read request. */
  record SectorData(int seq, long sector, ByteBuffer data) implements Response {}

  /** Acknowledges one written sector; fields echo the request (sector = start + slot for single writes). */
  record WriteAck(int seq, int batchCounter, int countMinusOne, int slot, long sector) implements Response {}

  record MemoryData(int seq, int address, ByteBuffer data) implements Response {}

  /** {@code status} 0 = mounted, 1 = error. */
  record MountResult(int seq, int status) implements Response {}

  /**
   * Answer to {@link Request.Cwd}: {@code status} 0 = ok (then {@code drive} and {@code cluster}
   * describe the working directory; cluster 0 = root), otherwise the hypervisor's error code (for
   * example when it has no {@code trap_dos_getcwd}, or a directory was not found).
   */
  record CwdResult(int seq, int status, int drive, int cluster) implements Response {}

  /** Decodes a datagram, or returns empty if it is not a well-formed remotesd_eth answer. */
  static Optional<Response> parse(ByteBuffer rx) {
    ByteBuffer b = rx.slice();
    int len = b.remaining();
    if (len < Request.HEADER || b.get(0) != 'm' || b.get(1) != 'r' || b.get(2) != 's' || b.get(3) != 'p') {
      return Optional.empty();
    }
    int seq = WindowProtocol.le16(b, 4);
    int op = b.get(6) & 0xff;
    Response r = switch (op) {
      case Request.OP_HELLO -> len == 7 ? new HelloAck(seq) : null;
      case Request.OP_QUIT -> len == 7 ? new QuitAck(seq) : null;
      case Request.OP_READ_SECTORS -> len == 13 + BlockDevice.SECTOR_SIZE
          ? new SectorData(seq, WindowProtocol.le32(b, 9), b.slice(13, BlockDevice.SECTOR_SIZE).asReadOnlyBuffer())
          : null;
      case Request.OP_WRITE_SECTOR -> len == 14
          ? new WriteAck(seq, b.get(7) & 0xff, b.get(8) & 0xff, b.get(9) & 0xff, WindowProtocol.le32(b, 10))
          : null;
      case Request.OP_READ_MEMORY -> len >= 13 && len == 12 + (b.get(7) & 0xff) + 1
          ? new MemoryData(seq, (int) WindowProtocol.le32(b, 8), b.slice(12, len - 12).asReadOnlyBuffer())
          : null;
      case Request.OP_MOUNT -> len == 8 ? new MountResult(seq, b.get(7) & 0xff) : null;
      case Request.OP_CWD -> len == 13
          ? new CwdResult(seq, b.get(7) & 0xff, b.get(8) & 0xff, (int) WindowProtocol.le32(b, 9))
          : null;
      default -> null;
    };
    return Optional.ofNullable(r);
  }
}
