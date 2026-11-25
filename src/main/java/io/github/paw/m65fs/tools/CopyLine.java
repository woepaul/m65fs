package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One line of output for something that moves a lot of data ({@code m65.cp -v},
 * {@code m65.boot}, {@code m65.defrag --fix}): the text ({@code 'src' -> 'dst'}) right away,
 * then, if it takes longer than {@code delay}, a progress field {@code " [ 66%]"} that is
 * updated in place, and on {@link #close()} the field is cleared again and the line ends
 * (optionally after a suffix, e.g. a result). A finished line therefore looks the same with or
 * without progress, also in logs.
 */
final class CopyLine implements AutoCloseable {

  static final Duration DELAY = Duration.ofSeconds(1);
  static final Duration INTERVAL = Duration.ofMillis(250);
  private static final String FIELD = " [%3d%%]";
  private static final int WIDTH = 7;

  private final PrintStream out;
  private final Thread ticker;
  private volatile String interruptedSuffix = " interrupted";
  private final Runnable onInterrupt = () -> end(interruptedSuffix, false);
  private volatile boolean shown;
  private boolean ended; // guarded by out

  /**
   * @param live show progress at all (only on a terminal: the field is redrawn with backspaces)
   * @param done bytes moved so far
   * @param total bytes to move; 0 or less while not known yet (e.g. data from a pipe: known
   *     once it has been read completely), and no field is shown until it is
   */
  CopyLine(PrintStream out, String text, boolean live, LongSupplier done, LongSupplier total, Duration delay) {
    this.out = out;
    out.print(text);
    out.flush();
    Interruption.running(onInterrupt);
    if (!live) {
      ticker = null;
      return;
    }
    ticker = Thread.ofPlatform().daemon().name("m65-progress").start(() -> {
      try {
        Thread.sleep(delay);
        int last = -1;
        while (!Thread.currentThread().isInterrupted()) {
          // never 100 % before the copy is done (read-ahead can move a little more than the file)
          long t = total.getAsLong();
          int pct = t <= 0 ? -1 : (int) Math.min(99, Math.max(0, done.getAsLong()) * 100 / t);
          if (pct >= 0 && pct != last) {
            synchronized (out) {
              if (ended) {
                return;
              }
              out.print((shown ? "\b".repeat(WIDTH) : "") + String.format(FIELD, pct));
              out.flush();
              shown = true;
            }
            last = pct;
          }
          Thread.sleep(INTERVAL);
        }
      } catch (InterruptedException e) {
        // done
      }
    });
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
   * as without progress); without (interrupted) it stays and shows how far it got.
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
      if (shown && clear) {
        out.print("\b".repeat(WIDTH) + " ".repeat(WIDTH) + "\b".repeat(WIDTH));
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
