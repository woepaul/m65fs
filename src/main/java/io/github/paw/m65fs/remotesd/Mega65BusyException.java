package io.github.paw.m65fs.remotesd;

import java.io.IOException;

/**
 * The transfer helper on the MEGA65 is busy: it has a session with another command (of this
 * or another computer), or another computer owns the helper (it started it, and the helper runs until that computer resets the
 * MEGA65 or someone presses RUN/STOP on it). A trigger would end the other computer's session,
 * so m65fs does not trigger unless asked to ({@code force}).
 */
public final class Mega65BusyException extends IOException {

  /**
   * {@code 1}: another session of this computer runs; {@code 2}: a session of another computer
   * runs; {@code 3}: another computer owns the idle helper.
   */
  private final int status;
  private final String owner;

  Mega65BusyException(int status, String owner) {
    super(status == 1
        ? "the MEGA65 is busy with another m65 command on this computer; try again when it is done"
        : status == 2
        ? "the MEGA65 is busy with a command from another computer (" + owner + ")"
        : "the MEGA65 is reserved for another computer (" + owner + "), which used it last;"
            + " it is free again after m65.reset there or RUN/STOP on the MEGA65"
            + " (M65_FORCE=1 takes it over anyway)");
    this.status = status;
    this.owner = owner;
  }

  public int status() {
    return status;
  }

  /** The other computer's IPv6 address. */
  public String owner() {
    return owner;
  }
}
