package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One line of output for something that moves a lot of data ({@code m65.cp -v},
 * {@code m65.boot}, {@code m65.defrag --fix}): the text ({@code 'src' -> 'dst'}) right away,
 * then, if it takes longer than {@code delay}, a progress field {@code " [ 66%]"} that is
 * updated in place, from {@link #ETA_AFTER} on with the estimated time left
 * ({@code " [ 66%] (8 min)"}), and on {@link #close()} the field is cleared again and the line
 * ends (optionally after a suffix, e.g. a result). A finished line therefore looks the same with
 * or without progress, also in logs.
 */
final class CopyLine implements AutoCloseable {

  static final Duration DELAY = Duration.ofSeconds(1);
  static final Duration INTERVAL = Duration.ofMillis(250);
  /** The estimate appears after this long (the start is slow: lookups, first packets). */
  static final Duration ETA_AFTER = Duration.ofSeconds(5);
  /** The rate behind the estimate is measured over this window (so it follows changes). */
  static final Duration RATE_WINDOW = Duration.ofSeconds(10);
  private static final String FIELD = " [%3d%%]";

  private final PrintStream out;
  private final Thread ticker;
  private volatile String interruptedSuffix = " interrupted";
  private final Runnable onInterrupt = () -> end(interruptedSuffix, false);
  private int shown; // characters of the field on screen; guarded by out
  private boolean ended; // guarded by out

  /**
   * @param live show progress at all (only on a terminal: the field is redrawn with backspaces)
   * @param done bytes moved so far
   * @param total bytes to move; 0 or less while not known yet (e.g. data from a pipe: known
   *     once it has been read completely), and no field is shown until it is
   * @param clock nanoseconds (System::nanoTime; tests pass their own)
   */
  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, LongSupplier total, Duration delay,
      LongSupplier clock) {
    this.out = out;
    out.print(text);
    out.flush();
    Interruption.running(onInterrupt);
    if (!live) {
      ticker = null;
      return;
    }
    long start = clock.getAsLong();
    ticker = Thread.ofPlatform().daemon().name("m65-progress").start(() -> {
      try {
        Thread.sleep(delay);
        String last = null;
        ArrayDeque<long[]> samples = new ArrayDeque<>(); // {time, done}
        while (!Thread.currentThread().isInterrupted()) {
          long now = clock.getAsLong();
          long d = Math.max(0, done.getAsLong());
          long t = total.getAsLong();
          samples.addLast(new long[] {now, d});
          while (samples.size() > 2 && now - samples.peekFirst()[0] > RATE_WINDOW.toNanos()) {
            samples.removeFirst();
          }
          if (t > 0) {
            // never 100 % before the copy is done (read-ahead can move a little more than the file)
            int pct = (int) Math.min(99, d * 100 / t);
            // from ETA_AFTER on, also at 0 % (a large file stays there for a long time)
            String eta = now - start >= ETA_AFTER.toNanos() ? eta(samples, t - d) : null;
            String field = String.format(FIELD, pct) + (eta == null ? "" : " (" + eta + ")");
            if (!field.equals(last)) {
              synchronized (out) {
                if (ended) {
                  return;
                }
                draw(field);
              }
              last = field;
            }
          }
          Thread.sleep(INTERVAL);
        }
      } catch (InterruptedException e) {
        // done
      }
    });
  }

  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, LongSupplier total, Duration delay) {
    this(out, text, live, done, total, delay, System::nanoTime);
  }

  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, long total, Duration delay) {
    this(out, text, live, done, () -> total, delay);
  }

  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, LongSupplier total) {
    this(out, text, live, done, total, DELAY);
  }

  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, long total) {
    this(out, text, live, done, () -> total, DELAY);
  }

  /** The time left at the rate over the samples, or null if there is no rate yet. */
  private static String eta(ArrayDeque<long[]> samples, long left) {
    long[] first = samples.peekFirst();
    long[] last = samples.peekLast();
    long dt = last[0] - first[0];
    long moved = last[1] - first[1];
    if (dt < Duration.ofSeconds(1).toNanos() || moved <= 0) {
      return null;
    }
    return duration((long) Math.ceil(left * (double) dt / moved / 1e9));
  }

  /** "<1 min", "8 min", "1 h 05 min" (minutes, rounded up: seconds would only flicker). */
  static String duration(long seconds) {
    if (seconds < 60) {
      return "<1 min";
    }
    long minutes = (seconds + 59) / 60;
    return minutes < 60 ? minutes + " min" : String.format("%d h %02d min", minutes / 60, minutes % 60);
  }

  /** Replaces the field on screen with {@code field} (wider or narrower). Call with out locked. */
  private void draw(String field) {
    StringBuilder sb = new StringBuilder();
    sb.append("\b".repeat(shown)).append(field);
    int pad = shown - field.length();
    if (pad > 0) {
      sb.append(" ".repeat(pad)).append("\b".repeat(pad)); // the end of a longer old field
    }
    out.print(sb);
    out.flush();
    shown = field.length();
  }

  /**
   * Whether to show progress: only on a terminal, where the field can be redrawn with
   * backspaces ({@code M65_PROGRESS=1} forces it, {@code =0} turns it off).
   */
  static boolean live(Map<String, String> env, PrintStream out) {
    String p = env.getOrDefault("M65_PROGRESS", "");
    if (p.equals("1") || p.equals("0")) {
      return p.equals("1");
    }
    if (out != System.out) {
      return false;
    }
    var console = System.console();
    return console != null && console.isTerminal() || stdoutIsTerminal();
  }

  /**
   * Whether standard output is a terminal, also when standard input is not (Console.isTerminal()
   * needs both, and {@code zcat x.gz | m65.cp - ...} has a pipe as input): on Linux and macOS
   * {@code /dev/fd/1} is then a character device other than {@code /dev/null}.
   */
  private static boolean stdoutIsTerminal() {
    try {
      var a = Files.readAttributes(Path.of("/dev/fd/1"), "unix:mode,rdev");
      boolean charDevice = ((int) a.get("mode") & 0170000) == 0020000;
      return charDevice && !a.get("rdev").equals(Files.getAttribute(Path.of("/dev/null"), "unix:rdev"));
    } catch (IOException | RuntimeException e) {
      return false; // a pipe or a file (no device), or no /dev/fd (Windows)
    }
  }

  @Override
  public void close() {
    close("");
  }

  /** Clears the progress field, appends {@code suffix} and ends the line. */
  public void close(String suffix) {
    Interruption.done(onInterrupt);
    if (ticker != null) {
      ticker.interrupt();
      try {
        ticker.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    end(suffix, true);
  }

  /**
   * Ends the line once: with {@code clear} the progress field goes first (a finished line looks
   * as without progress); without (interrupted) the field stays as it is and shows how far it
   * got. It is not redrawn then: on Ctrl-C the terminal has echoed {@code ^C} at the cursor, so
   * backspaces would land in the wrong column.
   */
  private void end(String suffix, boolean clear) {
    if (ticker != null) {
      ticker.interrupt();
    }
    synchronized (out) {
      if (ended) {
        return;
      }
      ended = true;
      if (shown > 0 && clear) {
        out.print("\b".repeat(shown) + " ".repeat(shown) + "\b".repeat(shown));
      }
      out.println(suffix);
      out.flush();
    }
  }

  /** What ends the line on Ctrl-C (default {@code " interrupted"}). */
  CopyLine interruptedSuffix(String suffix) {
    interruptedSuffix = suffix;
    return this;
  }

  /** For tests: what a Ctrl-C would print. */
  void simulateInterrupt() {
    Interruption.done(onInterrupt);
    onInterrupt.run();
  }
}
