package io.github.paw.m65fs.launch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.etherload.Etherload.Video;
import io.github.paw.m65fs.etherload.Ethlet;
import io.github.paw.m65fs.etherload.FakeHypervisor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The launcher against the simulated hypervisor: what lands in memory and which ethlet ends it. */
class Mega65LauncherTest {

  static byte[] prg(int load, int size) {
    byte[] p = new byte[size + 2];
    p[0] = (byte) load;
    p[1] = (byte) (load >> 8);
    for (int i = 2; i < p.length; i++) {
      p[i] = (byte) (i * 7);
    }
    return p;
  }

  static byte[] memory(FakeHypervisor hv, int addr, int len) {
    return Arrays.copyOfRange(hv.memory, addr, addr + len);
  }

  @Test
  void c64ProgramIsLoadedAndRunAfterAC64Reset() throws Exception {
    try (var hv = new FakeHypervisor(0.02, 0.02, 1)) {
      byte[] p = prg(0x0801, 5000);
      var mode = Mega65Launcher.runPrg(hv.target(), p,
          Mega65Launcher.Options.defaults().withMount("Data Disk.d81").withVideo(Video.PAL));
      assertEquals(MachineMode.C64, mode);
      assertTrue(hv.triggers.get() >= 1);
      assertArrayEquals(Arrays.copyOfRange(p, 2, p.length), memory(hv, 0x0801, 5000));
      var reset = hv.starts(Ethlet.START_MODE_C64).getLast();
      assertEquals(0x0801 + 5000, reset.address());
      assertTrue(reset.run());
      assertEquals(1, reset.video(), "PAL");
      assertEquals("Data Disk.d81", reset.imageName());
      assertEquals((byte) 'd', reset.packet()[Ethlet.START_IMAGE], "PETSCII: letters with swapped case");
    }
  }

  @Test
  void mega65ProgramIsLoadedAndRunAfterAMega65Reset() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1)) {
      byte[] p = prg(0x2001, 40_000);
      assertEquals(MachineMode.MEGA65, Mega65Launcher.runPrg(hv.target(), p,
          Mega65Launcher.Options.defaults().withRun(false)));
      assertArrayEquals(Arrays.copyOfRange(p, 2, p.length), memory(hv, 0x2001, 40_000));
      var reset = hv.starts(Ethlet.START_MODE_MEGA65).getLast();
      assertEquals(0x2001 + 40_000, reset.address());
      assertFalse(reset.run());
      assertEquals("", reset.imageName());
      assertTrue(hv.starts(Ethlet.START_MODE_C64).isEmpty());
    }
  }

  @Test
  void otherLoadAddressesNeedAJumpAndFailBeforeTouchingTheMachine() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1)) {
      byte[] p = prg(0xc000, 100);
      var e = assertThrows(IllegalArgumentException.class,
          () -> Mega65Launcher.runPrg(hv.target(), p, Mega65Launcher.Options.defaults()));
      assertTrue(e.getMessage().contains("$C000"), e.getMessage());
      assertEquals(0, hv.triggers.get());

      assertNull(Mega65Launcher.runPrg(hv.target(), p,
          Mega65Launcher.Options.defaults().withJump(0xc000).withMount("TOOLS.D81")));
      assertArrayEquals(Arrays.copyOfRange(p, 2, p.length), memory(hv, 0xc000, 100));
      var jump = hv.starts(Ethlet.START_MODE_JUMP).getLast();
      assertEquals(0xc000, jump.address());
      assertEquals("TOOLS.D81", jump.imageName());
      assertThrows(IllegalArgumentException.class,
          () -> Mega65Launcher.Options.defaults().withJump(0xc000).withMount("x".repeat(64) + ".d81"));
      assertThrows(IllegalArgumentException.class, () -> Mega65Launcher.runPrg(hv.target(), prg(0xff00, 0x200),
          Mega65Launcher.Options.defaults()), "beyond $FFFF");
    }
  }

  @Test
  void diskBootUploadsTheRightLoaderAndMounts() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1)) {
      var c64 = new Mega65Launcher.Boot("Game Disk.D81", MachineMode.C64, Petscii.fromAscii("GAME"), 0x0801);
      Mega65Launcher.boot(hv.target(), c64, Video.UNCHANGED);
      byte[] loader = BootLoaders.c64(Petscii.fromAscii("GAME"));
      assertArrayEquals(Arrays.copyOfRange(loader, 2, loader.length), memory(hv, 0x0801, loader.length - 2));
      var reset = hv.starts(Ethlet.START_MODE_C64).getLast();
      assertEquals("Game Disk.D81", reset.imageName());
      assertEquals(0x0801 + loader.length - 2, reset.address());
      assertTrue(reset.run());

      var m65 = new Mega65Launcher.Boot("DEMO.D81", MachineMode.MEGA65, Petscii.fromAscii("DEMO"), 0x2001);
      Mega65Launcher.boot(hv.target(), m65, Video.NTSC);
      byte[] basic = BootLoaders.mega65(Petscii.fromAscii("DEMO"), "DEMO.D81");
      assertArrayEquals(Arrays.copyOfRange(basic, 2, basic.length), memory(hv, 0x2001, basic.length - 2));
      var reset65 = hv.starts(Ethlet.START_MODE_MEGA65).getLast();
      assertEquals("", reset65.imageName(), "MEGA65 mode: the BASIC loader mounts, not the reset");
      assertEquals(0x2001 + basic.length - 2, reset65.address());
      assertTrue(reset65.run());
      assertEquals(2, reset65.video(), "NTSC");
    }
  }

  @Test
  void plainResetKeepsNoProgram() throws Exception {
    try (var hv = new FakeHypervisor(0, 0, 1)) {
      Mega65Launcher.reset(hv.target(), MachineMode.MEGA65, null, Video.UNCHANGED);
      var reset = hv.starts(Ethlet.START_MODE_MEGA65).getLast();
      assertEquals(0, reset.address());
      assertFalse(reset.run());
      assertEquals("", reset.imageName());
    }
  }
}
