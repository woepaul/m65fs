package io.github.paw.m65fs.transport;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the MEGA65 is: an IPv6 address (normally link-local), the UDP port (always 4510 on
 * real hardware) and the network interface it is reachable through. The interface is needed
 * for link-local unicast and for the multicast hyperrupt trigger; it may be {@code null} for
 * loopback test targets.
 */
public record Target(Inet6Address address, int port, NetworkInterface networkInterface) {

  /** UDP port used by the MEGA65 Ethernet remote control and by the transfer helper. */
  public static final int MEGA65_PORT = 4510;

  public Target {
    Objects.requireNonNull(address, "address");
    if (port <= 0 || port > 0xffff) {
      throw new IllegalArgumentException("port out of range: " + port);
    }
  }

  /**
   * Parses {@code "fe80::1234%enp3s0"} (the format of {@code mega65_ftp -i}).
   *
   * @throws IOException if the address or interface cannot be resolved
   */
  public static Target parse(String addressAndInterface) throws IOException {
    int pct = addressAndInterface.indexOf('%');
    if (pct < 0) {
      throw new IllegalArgumentException("expected <ipv6>%<interface>, got: " + addressAndInterface);
    }
    String ip = addressAndInterface.substring(0, pct);
    String ifName = addressAndInterface.substring(pct + 1);
    NetworkInterface nif = NetworkInterface.getByName(ifName);
    if (nif == null) {
      throw new SocketException("unknown network interface: " + ifName);
    }
    if (!(InetAddress.getByName(ip) instanceof Inet6Address v6)) {
      throw new IllegalArgumentException("not an IPv6 address: " + ip);
    }
    return of(v6, nif);
  }

  /** A MEGA65 target on port 4510 reachable via {@code nif}. */
  public static Target of(Inet6Address address, NetworkInterface nif) throws IOException {
    return new Target(Inet6Address.getByAddress(null, address.getAddress(), nif), MEGA65_PORT, nif);
  }

  public Optional<NetworkInterface> optionalInterface() {
    return Optional.ofNullable(networkInterface);
  }

  public InetSocketAddress socketAddress() {
    return new InetSocketAddress(address, port);
  }

  @Override
  public String toString() {
    String host = address.getHostAddress();
    int pct = host.indexOf('%');
    if (pct >= 0) {
      host = host.substring(0, pct);
    }
    return "[" + host + (networkInterface != null ? "%" + networkInterface.getName() : "") + "]:" + port;
  }
}
