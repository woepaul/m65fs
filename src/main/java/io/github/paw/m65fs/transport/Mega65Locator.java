package io.github.paw.m65fs.transport;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finds a MEGA65 in remote-control mode on the local link. While in that mode (DIP switch 2
 * on, or SHIFT+£ pressed) it multicasts the 6-byte payload {@code "mega65"} to
 * {@code ff02::1} port 4510; the sender's address is the target.
 */
public final class Mega65Locator {

  private static final Logger LOG = System.getLogger(Mega65Locator.class.getName());
  private static final byte[] BEACON = "mega65".getBytes(StandardCharsets.US_ASCII);

  private Mega65Locator() {}

  /** Default discovery timeout used by mega65_ftp. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(2500);

  /**
   * Listens on all up, non-loopback interfaces with a link-local IPv6 address. A MEGA65 is
   * often heard on several interfaces (wired and WLAN on the same network): a beacon heard over
   * a wireless interface is only taken if no wired one brings a beacon within one beacon period
   * ({@link #WIRED_GRACE}), since the wired path is faster and loses fewer packets.
   *
   * @return the MEGA65 heard from, or empty if none within {@code timeout}
   */
  public static Optional<Target> discover(Duration timeout) throws IOException {
    List<NetworkInterface> nifs = candidateInterfaces();
    if (nifs.isEmpty()) {
      throw new IOException("no up interface with a link-local IPv6 address; discovery needs IPv6 on the MEGA65 network");
    }
    try (DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET6);
        Selector sel = Selector.open()) {
      ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      if (ch.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
        ch.setOption(StandardSocketOptions.SO_REUSEPORT, true);
      }
      ch.bind(new InetSocketAddress(Target.MEGA65_PORT));
      InetAddress allNodes = InetAddress.getByName("ff02::1");
      for (NetworkInterface nif : nifs) {
        try {
          ch.join(allNodes, nif);
        } catch (IOException e) {
          LOG.log(Level.DEBUG, "cannot join ff02::1 on {0}: {1}", nif.getName(), e.getMessage());
        }
      }
      ch.configureBlocking(false);
      ch.register(sel, SelectionKey.OP_READ);

      ByteBuffer buf = ByteBuffer.allocate(2048);
      long deadline = System.nanoTime() + timeout.toNanos();
      Target wireless = null;
      while (true) {
        buf.clear();
        SocketAddress from = ch.receive(buf);
        if (from == null) {
          long left = deadline - System.nanoTime();
          if (left <= 0) {
            return Optional.ofNullable(wireless);
          }
          sel.select(Math.max(1, left / 1_000_000));
          sel.selectedKeys().clear();
          continue;
        }
        buf.flip();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        if (Arrays.equals(payload, BEACON)
            && from instanceof InetSocketAddress isa
            && isa.getAddress() instanceof Inet6Address v6
            && v6.getScopeId() != 0) {
          NetworkInterface nif = NetworkInterface.getByIndex(v6.getScopeId());
          if (nif != null) {
            Target t = Target.of(v6, nif);
            if (!isWireless(nif)) {
              LOG.log(Level.DEBUG, "found MEGA65 at {0}", t);
              return Optional.of(t);
            }
            if (wireless == null) {
              LOG.log(Level.DEBUG, "found MEGA65 at {0} (wireless; waiting for a wired interface)", t);
              wireless = t;
              deadline = Math.min(deadline, System.nanoTime() + WIRED_GRACE.toNanos());
            }
          }
        }
      }
    }
  }

  /** How long a wired interface may take to bring a beacon after a wireless one did. */
  static final Duration WIRED_GRACE = Duration.ofMillis(1200);

  /** Linux: {@code /sys/class/net/IF/wireless}; elsewhere: names starting with "wl". */
  public static boolean isWireless(NetworkInterface nif) {
    Path sys = Path.of("/sys/class/net", nif.getName(), "wireless");
    if (Files.isDirectory(Path.of("/sys/class/net"))) {
      return Files.exists(sys);
    }
    return nif.getName().startsWith("wl");
  }

  static List<NetworkInterface> candidateInterfaces() throws IOException {
    List<NetworkInterface> result = new ArrayList<>();
    for (NetworkInterface nif : NetworkInterface.networkInterfaces().toList()) {
      if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) {
        continue;
      }
      boolean hasLinkLocal = nif.inetAddresses()
          .anyMatch(a -> a instanceof Inet6Address v6 && v6.isLinkLocalAddress());
      if (hasLinkLocal) {
        result.add(nif);
      }
    }
    return result;
  }
}
