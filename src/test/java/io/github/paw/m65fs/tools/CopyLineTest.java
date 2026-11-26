package io.github.paw.m65fs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CopyLineTest {

  final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  final PrintStream out = new PrintStream(bytes, true);

  /** What a terminal shows: "\b" moves back one column, later characters overwrite. */
  static String rendered(String s) {
    StringBuilder line = new StringBuilder();
    int col = 0;
    for (char c : s.toCharArray()) {
      if (c == '\b') {
        col = Math.max(0, col - 1);
      } else if (col < line.length()) {
        line.setCharAt(col++, c);
      } else {
        line.append(c);
        col++;
      }
    }
    return line.toString().stripTrailing();
  }

  @Test
  void withoutATerminalTheLineIsPlain() {
    try (var l = new CopyLine(out, "'a' -> 'b'", false, () -> 50, 100, Duration.ZERO)) {
      assertEquals("'a' -> 'b'", bytes.toString(), "the line comes first, before the copy");
    }
    assertEquals("'a' -> 'b'" + System.lineSeparator(), bytes.toString());
  }

  @Test
  void aQuickCopyShowsNoProgress() {
    new CopyLine(out, "'a' -> 'b'", true, () -> 100, 100, Duration.ofSeconds(5)).close();
    assertEquals("'a' -> 'b'" + System.lineSeparator(), bytes.toString());
  }

  @Test
  void aTotalThatIsNotKnownYetShowsNothingUntilItIs() throws InterruptedException {
    // data from a pipe: the size is known once it has been read completely
    AtomicLong total = new AtomicLong();
    AtomicLong done = new AtomicLong(250);
    try (var l = new CopyLine(out, "'-' -> 'x'", true, done::get, total::get, Duration.ZERO)) {
      Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
      assertEquals("'-' -> 'x'", rendered(bytes.toString()));
      total.set(1000);
      Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
      assertEquals("'-' -> 'x' [ 25%]", rendered(bytes.toString()));
    }
  }

  @Test
  void anInterruptedCopyKeepsItsProgressAndSaysSo() throws InterruptedException {
    var l = new CopyLine(out, "'a' -> 'b'", true, () -> 420, 1000, Duration.ZERO);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    l.simulateInterrupt();
    l.close(); // the main thread may still get here: the line stays as it is
    assertEquals("'a' -> 'b' [ 42%] interrupted" + System.lineSeparator(), rendered(bytes.toString()) + System.lineSeparator());
    assertEquals(1, bytes.toString().split(System.lineSeparator(), -1).length - 1, "one newline");
  }

  @Test
  void aFailedCopySaysSo() {
    new CopyLine(out, "'a' -> 'b'", false, () -> 0, 100, Duration.ZERO).close(" failed");
    assertEquals("'a' -> 'b' failed" + System.lineSeparator(), bytes.toString());
  }

  @Test
  void aSlowCopyShowsProgressAndEndsAsAPlainLine() throws InterruptedException {
    AtomicLong done = new AtomicLong();
    try (var l = new CopyLine(out, "'a' -> 'b'", true, done::get, 1000, Duration.ZERO)) {
      for (int pct : new int[] {10, 66, 100}) {
        done.set(pct * 10L);
        Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
        String shown = rendered(bytes.toString());
        assertEquals("'a' -> 'b' [" + String.format("%3d", Math.min(pct, 99)) + "%]", shown);
      }
    }
    String all = bytes.toString();
    assertTrue(all.endsWith(System.lineSeparator()));
    assertEquals("'a' -> 'b'", rendered(all.substring(0, all.length() - System.lineSeparator().length())),
        "the field is cleared before the newline");
  }

  @Test
  void durationsAreRoundedToMinutes() {
    assertEquals("<1 min", CopyLine.duration(0));
    assertEquals("<1 min", CopyLine.duration(59));
    assertEquals("1 min", CopyLine.duration(60));
    assertEquals("2 min", CopyLine.duration(61));
    assertEquals("59 min", CopyLine.duration(59 * 60));
    assertEquals("1 h 00 min", CopyLine.duration(60 * 60));
    assertEquals("1 h 05 min", CopyLine.duration(64 * 60 + 1));
  }

  @Test
  void aLongCopyShowsTheTimeLeftAfterAWhile() throws InterruptedException {
    AtomicLong clock = new AtomicLong();
    AtomicLong done = new AtomicLong();
    long s = 1_000_000_000L;
    var l = new CopyLine(out, "'a' -> 'b'", true, done::get, () -> 10_000, Duration.ZERO, clock::get);
    done.set(150); // bytes first, then the time: the ticker may read in between
    clock.set(2 * s);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    assertEquals("'a' -> 'b' [  1%]", rendered(bytes.toString()), "no estimate in the first seconds");
    done.set(600);
    clock.set(6 * s);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    // 600 bytes in 6 s: 9,400 left at 100/s = 94 s
    assertEquals("'a' -> 'b' [  6%] (2 min)", rendered(bytes.toString()));
    done.set(6_000);
    clock.set(12 * s);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    // the window follows the rate: 5,400 bytes in the last 6 s, 4,000 left
    assertEquals("'a' -> 'b' [ 60%] (<1 min)", rendered(bytes.toString()));
    l.close();
    String all = bytes.toString();
    assertEquals("'a' -> 'b'", rendered(all.substring(0, all.length() - System.lineSeparator().length())),
        "the field (with the estimate) is cleared before the newline");
  }

  @Test
  void anInterruptedCopyKeepsOnlyItsPercentage() throws InterruptedException {
    AtomicLong clock = new AtomicLong();
    AtomicLong done = new AtomicLong();
    var l = new CopyLine(out, "'a' -> 'b'", true, done::get, () -> 1_000, Duration.ZERO, clock::get);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    done.set(200);
    clock.set(10_000_000_000L);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    done.set(420);
    clock.set(20_000_000_000L);
    Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
    assertTrue(rendered(bytes.toString()).endsWith(" min)"), rendered(bytes.toString()));
    l.simulateInterrupt();
    l.close();
    assertEquals("'a' -> 'b' [ 42%] interrupted", rendered(bytes.toString()));
  }

  @Test
  void aStalledCopyShowsNoEstimate() throws InterruptedException {
    AtomicLong clock = new AtomicLong();
    AtomicLong done = new AtomicLong(300);
    try (var l = new CopyLine(out, "'a' -> 'b'", true, done::get, () -> 1_000, Duration.ZERO, clock::get)) {
      Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
      clock.set(30_000_000_000L); // 30 s without a byte
      Thread.sleep(CopyLine.INTERVAL.toMillis() * 2);
      assertEquals("'a' -> 'b' [ 30%]", rendered(bytes.toString()));
    }
  }
}
