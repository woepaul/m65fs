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
}
