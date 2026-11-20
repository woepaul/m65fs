package io.github.paw.m65fs.nio;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * Typed access to the {@code env} map of {@code newFileSystem}. Values may be given as their
 * natural type or as strings (handy with {@code Map<String, String>}).
 *
 * <table>
 *   <caption>Supported keys</caption>
 *   <tr><td>{@code target}</td><td>{@code fe80::…%if}; alternative to the URI host</td></tr>
 *   <tr><td>{@code discoveryTimeout}</td><td>Duration or millis (default 2500)</td></tr>
 *   <tr><td>{@code timeout}</td><td>per-operation timeout, Duration or millis (default 3000)</td></tr>
 *   <tr><td>{@code writeWindow}</td><td>override the write window size</td></tr>
 *   <tr><td>{@code readAhead}</td><td>sectors read ahead for metadata (default 16)</td></tr>
 *   <tr><td>{@code quitOnClose}</td><td>send quit (resets the MEGA65) on close (default true)</td></tr>
 *   <tr><td>{@code readOnly}</td><td>open read-only (default false)</td></tr>
 *   <tr><td>{@code zone}</td><td>time zone of FAT timestamps (default system)</td></tr>
 *   <tr><td>{@code imageFile}</td><td>use a local SD card / FAT32 image instead of the network</td></tr>
 *   <tr><td>{@code shadowMemoryLimit}</td><td>bytes buffered in memory per open writer (default 8 MiB)</td></tr>
 * </table>
 */
record Env(Map<String, ?> map) {

  Env {
    map = map == null ? Map.of() : map;
  }

  Optional<String> string(String key) {
    Object v = map.get(key);
    return v == null ? Optional.empty() : Optional.of(v.toString());
  }

  boolean bool(String key, boolean def) {
    Object v = map.get(key);
    return v == null ? def : v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
  }

  Optional<Integer> integer(String key) {
    Object v = map.get(key);
    return v == null ? Optional.empty() : Optional.of(v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()));
  }

  Optional<Duration> duration(String key) {
    Object v = map.get(key);
    if (v == null) {
      return Optional.empty();
    }
    if (v instanceof Duration d) {
      return Optional.of(d);
    }
    if (v instanceof Number n) {
      return Optional.of(Duration.ofMillis(n.longValue()));
    }
    String s = v.toString();
    return Optional.of(s.startsWith("P") || s.startsWith("p") ? Duration.parse(s) : Duration.ofMillis(Long.parseLong(s)));
  }

  Optional<Path> path(String key) {
    Object v = map.get(key);
    return v == null ? Optional.empty() : Optional.of(v instanceof Path p ? p : Path.of(v.toString()));
  }

  ZoneId zone() {
    Object v = map.get("zone");
    return v == null ? ZoneId.systemDefault() : v instanceof ZoneId z ? z : ZoneId.of(v.toString());
  }
}
