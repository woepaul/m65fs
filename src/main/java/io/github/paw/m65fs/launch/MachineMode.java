package io.github.paw.m65fs.launch;

/** The mode the MEGA65 core runs a program in. */
public enum MachineMode {
  /** C64 mode as entered with {@code GO64}: C64 BASIC v2 and KERNAL; programs load at $0801. */
  C64,
  /** MEGA65 mode: BASIC 65; programs load at $2001. */
  MEGA65;

  /** The mode a program belongs to by its load address: $2001 is MEGA65, anything else C64. */
  public static MachineMode forLoadAddress(int address) {
    return address == 0x2001 ? MEGA65 : C64;
  }
}
