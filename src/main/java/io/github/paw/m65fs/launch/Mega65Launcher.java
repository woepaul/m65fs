package io.github.paw.m65fs.launch;

import io.github.paw.m65fs.etherload.Etherload;
import io.github.paw.m65fs.etherload.Etherload.Video;
import io.github.paw.m65fs.transport.Target;
import io.github.paw.m65fs.transport.UdpLink;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Starts programs on a MEGA65 through the hypervisor's Ethernet remote control (the protocol of
 * mega65-tools' {@code etherload}). Needs remote mode (DIP switch 2 or SHIFT+£) and
 * {@code ETHLOAD.M65} in the SD card root.
 *
 * <p>Every call takes over the machine: whatever is running is replaced, and the MEGA65 is
 * reset into C64 mode (as with {@code GO64}) or MEGA65 mode. A running m65fs file system session
 * must be closed first ({@code Mega65FileSystem.closeAndBoot} / {@code closeAndRun} do that).
 */
public final class Mega65Launcher {

  private static final Logger LOG = System.getLogger(Mega65Launcher.class.getName());

  private Mega65Launcher() {}

  /**
   * Options for {@link #runPrg}.
   *
   * @param mount D81 image in the SD card root to attach as drive 0, or {@code null}. In MEGA65
   *     mode the ROM then does not RUN the program (it only stays in memory)
   * @param video video standard to switch to on reset
   * @param run RUN the program after the reset (otherwise it is only loaded)
   * @param cartridgeDetection honour a CBM80 cartridge signature at $8004 on reset (C64 mode)
   * @param jumpAddress instead of resetting, jump to this address after loading (machine code
   *     that initialises the machine itself)
   */
  public record Options(String mount, Video video, boolean run, boolean cartridgeDetection, OptionalInt jumpAddress) {
    public Options {
      Objects.requireNonNull(video);
      Objects.requireNonNull(jumpAddress);
      if (mount != null) {
        Etherload.checkImageName(mount, jumpAddress.isPresent() ? 15 : Etherload.MAX_IMAGE_NAME);
      }
    }

    public static Options defaults() {
      return new Options(null, Video.UNCHANGED, true, false, OptionalInt.empty());
    }

    public Options withMount(String image) {
      return new Options(image, video, run, cartridgeDetection, jumpAddress);
    }

    public Options withVideo(Video v) {
      return new Options(mount, v, run, cartridgeDetection, jumpAddress);
    }

    public Options withRun(boolean r) {
      return new Options(mount, video, r, cartridgeDetection, jumpAddress);
    }

    public Options withCartridgeDetection(boolean c) {
      return new Options(mount, video, run, c, jumpAddress);
    }

    public Options withJump(int address) {
      return new Options(mount, video, run, cartridgeDetection, OptionalInt.of(address));
    }

    Etherload.Reset reset() {
      return new Etherload.Reset(mount, video, cartridgeDetection);
    }
  }

  /**
   * What a disk boot does: mount {@code image}, reset into {@code mode}, load {@code program}
   * from drive 8 and run it.
   *
   * @param program the file name as in the disk directory (PETSCII)
   * @param loadAddress the program's load address
   */
  public record Boot(String image, MachineMode mode, byte[] program, int loadAddress) {
    public Boot {
      Objects.requireNonNull(image);
      Objects.requireNonNull(mode);
      Etherload.checkImageName(image, Etherload.MAX_IMAGE_NAME);
      program = program.clone();
    }

    @Override
    public byte[] program() {
      return program.clone();
    }

    public String programName() {
      return Petscii.toAscii(program);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Boot b && image.equals(b.image) && mode == b.mode && Arrays.equals(program, b.program)
          && loadAddress == b.loadAddress;
    }

    @Override
    public int hashCode() {
      return Objects.hash(image, mode, Arrays.hashCode(program), loadAddress);
    }

    @Override
    public String toString() {
      return String.format("boot %s: \"%s\" ($%04X) in %s mode", image, programName(), loadAddress, mode);
    }
  }

  /**
   * Decides how to boot a disk.
   *
   * @param image the image's name in the SD card root
   * @param disk the image's directory
   * @param mode the mode, or {@code null} to choose by the program's load address ($2001 =
   *     MEGA65, anything else C64)
   * @param program the program to start (PETSCII), or {@code null} for the first program on the
   *     disk
   */
  public static Boot plan(String image, D81Image disk, MachineMode mode, byte[] program) throws IOException {
    D81Image.Entry entry = (program == null ? disk.firstProgram() : disk.program(program))
        .orElseThrow(() -> new IOException(program == null
            ? "no program on disk image " + image
            : "no program \"" + Petscii.toAscii(program) + "\" on disk image " + image));
    int load = disk.loadAddress(entry);
    return new Boot(image, mode != null ? mode : MachineMode.forLoadAddress(load), entry.name(), load);
  }

  /**
   * Boots a disk: mounts the image (which must already be in the SD card root and contiguous),
   * resets into the boot's mode and loads and runs its program.
   *
   * <p>C64 mode: the reset ethlet mounts the image, then a small loader does
   * {@code LOAD"name",8,1} and {@code RUN}. MEGA65 mode: the reset happens without a disk and
   * a one-line BASIC 65 program does {@code MOUNT"image":RUN"name"} (the ROM does not start a
   * program in memory when a disk is attached during the reset).
   */
  public static void boot(Target target, Boot boot, Video video) throws IOException {
    boolean c64 = boot.mode() == MachineMode.C64;
    byte[] loader = c64 ? BootLoaders.c64(boot.program) : BootLoaders.mega65(boot.program, boot.image());
    var reset = new Etherload.Reset(c64 ? boot.image() : null, video, false);
    LOG.log(Level.DEBUG, "{0} on {1}", boot, target);
    try (UdpLink link = UdpLink.open(target)) {
      Etherload e = Etherload.connect(link);
      int end = e.writeProgram(loader);
      if (c64) {
        e.resetToC64(end, true, reset);
      } else {
        e.resetToMega65(end, true, reset);
      }
    }
  }

  /**
   * Loads a program file straight into memory (nothing is written to the SD card) and starts
   * it: a program at $0801 in C64 mode, one at $2001 in MEGA65 mode. Programs at other addresses
   * need {@link Options#withJump}.
   *
   * @return the mode the program was started in, or {@code null} for a jump
   * @throws IllegalArgumentException for other load addresses without a jump address (before
   *     anything is sent to the MEGA65)
   */
  public static MachineMode runPrg(Target target, byte[] prg, Options options) throws IOException {
    int load = Etherload.loadAddress(prg);
    MachineMode mode = null;
    if (options.jumpAddress().isEmpty()) {
      mode = switch (load) {
        case Etherload.BASIC_START -> MachineMode.C64;
        case Etherload.BASIC65_START -> MachineMode.MEGA65;
        default -> throw new IllegalArgumentException(String.format(
            "program loads at $%04X: only $0801 (C64) and $2001 (MEGA65) programs can be RUN; "
                + "give a jump address for machine code", load));
      };
    }
    LOG.log(Level.DEBUG, "run {0} bytes at ${1} on {2} ({3})", prg.length - 2, Integer.toHexString(load), target,
        mode == null ? "jump" : mode);
    try (UdpLink link = UdpLink.open(target)) {
      Etherload e = Etherload.connect(link);
      int end = e.writeProgram(prg);
      if (mode == null) {
        e.jump(options.jumpAddress().getAsInt(), options.mount());
      } else if (mode == MachineMode.C64) {
        e.resetToC64(end, options.run(), options.reset());
      } else {
        e.resetToMega65(end, options.run(), options.reset());
      }
    }
    return mode;
  }

  /**
   * Resets the MEGA65 into {@code mode}, optionally mounting an image. A MEGA65-mode reset
   * without an image also detaches drive 0 (which the soft reset after a file system session
   * does not).
   */
  public static void reset(Target target, MachineMode mode, String mount, Video video) throws IOException {
    var reset = new Etherload.Reset(mount, video, false);
    if (mount != null) {
      Etherload.checkImageName(mount, Etherload.MAX_IMAGE_NAME);
    }
    try (UdpLink link = UdpLink.open(target)) {
      Etherload e = Etherload.connect(link);
      if (mode == MachineMode.C64) {
        e.resetToC64(0, false, reset);
      } else {
        e.resetToMega65(0, false, reset);
      }
    }
  }
}
