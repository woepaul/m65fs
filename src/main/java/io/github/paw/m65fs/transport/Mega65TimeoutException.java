package io.github.paw.m65fs.transport;

import java.io.IOException;
import java.io.Serial;

/** The MEGA65 did not answer in time. */
public class Mega65TimeoutException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  public Mega65TimeoutException(String message) {
    super(message);
  }
}
