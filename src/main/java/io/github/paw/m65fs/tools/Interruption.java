package io.github.paw.m65fs.tools;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * What to report if the program is stopped (Ctrl-C) while something long runs, e.g. a copy. The
 * work itself is not waited for: the card stays consistent (writes go to free clusters first),
 * but the output must show that it did not complete.
 */
final class Interruption {

  /** Innermost first: e.g. the line of the file being copied, then the summary of the run. */
  private static final Deque<Runnable> REPORTS = new ConcurrentLinkedDeque<>();

  static {
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("m65-interrupted").unstarted(() -> {
      for (Runnable r; (r = REPORTS.pollFirst()) != null; ) {
        r.run();
      }
    }));
  }

  private Interruption() {}

  /** {@code report} runs if the program stops before {@link #done(Runnable)}. */
  static void running(Runnable report) {
    REPORTS.addFirst(report);
  }

  /** The work behind {@code report} has ended (completed or failed with its own message). */
  static void done(Runnable report) {
    REPORTS.removeFirstOccurrence(report);
  }
}
