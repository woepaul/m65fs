package io.github.paw.m65fs.launch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.launch.CbmTools.DiskFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class D81ImageTest {

  @TempDir Path tmp;

  @Test
  void readsTheDirectoryAndLoadAddresses() throws IOException {
    byte[] c64 = CbmTools.basic(tmp, "2", "10 print \"hello\"\n");
    byte[] m65 = CbmTools.basic(tmp, "65", "10 print \"mega\"\n");
    byte[] ml = {0x00, (byte) 0xc0, (byte) 0xee, 0x20, (byte) 0xd0, 0x60}; // $C000: inc $d020, rts
    var files = new DiskFile[12]; // more than 8 entries: the directory spans two sectors
    files[0] = new DiskFile("readme", 's', "not a program".getBytes());
    files[1] = new DiskFile("hello", 'p', c64);
    files[2] = new DiskFile("mega prog", 'p', m65);
    files[3] = new DiskFile("ml", 'p', ml);
    for (int i = 4; i < files.length; i++) {
      files[i] = new DiskFile("file" + i, 'p', c64);
    }
    Path img = CbmTools.d81(tmp, "m65fs test", files);

    D81Image d = D81Image.read(img);
    assertEquals("M65FS TEST", d.diskName());
    assertEquals(12, d.entries().size());
    assertEquals("README", d.entries().get(0).displayName());
    assertEquals(D81Image.Entry.SEQ, d.entries().get(0).fileType());
    assertEquals("FILE11", d.entries().get(11).displayName());

    var first = d.firstProgram().orElseThrow();
    assertEquals("HELLO", first.displayName(), "the SEQ file before it is skipped");
    assertEquals(0x0801, d.loadAddress(first));
    var mega = d.program(Petscii.fromAscii("mega prog")).orElseThrow();
    assertEquals(0x2001, d.loadAddress(mega));
    assertEquals(0xc000, d.loadAddress(d.program(Petscii.fromAscii("ML")).orElseThrow()));
    assertTrue(d.program(Petscii.fromAscii("README")).isEmpty(), "not a program");
  }

  @Test
  void plansBootsByLoadAddress() throws IOException {
    byte[] c64 = CbmTools.basic(tmp, "2", "10 print \"hello\"\n");
    byte[] m65 = CbmTools.basic(tmp, "65", "10 print \"mega\"\n");
    D81Image c64Disk = D81Image.read(CbmTools.d81(tmp, "c64 disk", new DiskFile("game", 'p', c64),
        new DiskFile("other", 'p', m65)));
    D81Image m65Disk = D81Image.read(CbmTools.d81(tmp, "m65 disk", new DiskFile("demo", 'p', m65)));

    var boot = Mega65Launcher.plan("GAME.D81", c64Disk, null, null);
    assertEquals(MachineMode.C64, boot.mode());
    assertEquals("GAME", boot.programName());
    assertEquals(0x0801, boot.loadAddress());

    assertEquals(MachineMode.MEGA65, Mega65Launcher.plan("DEMO.D81", m65Disk, null, null).mode());
    assertEquals(MachineMode.MEGA65, Mega65Launcher.plan("X.D81", c64Disk, null, Petscii.fromAscii("other")).mode());
    assertEquals(MachineMode.MEGA65, Mega65Launcher.plan("X.D81", c64Disk, MachineMode.MEGA65, null).mode(),
        "explicit mode wins");
    assertArrayEquals(Petscii.fromAscii("GAME"), boot.program());

    var e = assertThrows(IOException.class, () -> Mega65Launcher.plan("X.D81", c64Disk, null, Petscii.fromAscii("x")));
    assertTrue(e.getMessage().contains("no program \"X\""), e.getMessage());
    D81Image empty = D81Image.read(CbmTools.d81(tmp, "empty", new DiskFile("text", 's', new byte[10])));
    assertThrows(IOException.class, () -> Mega65Launcher.plan("E.D81", empty, null, null));
  }

  @Test
  void rejectsNonD81Files() throws IOException {
    Path f = tmp.resolve("x.d81");
    Files.write(f, new byte[1000]);
    assertThrows(IOException.class, () -> D81Image.read(f));
    Files.write(f, new byte[(int) D81Image.SIZE]);
    var e = assertThrows(IOException.class, () -> D81Image.read(f));
    assertTrue(e.getMessage().contains("'D'"), e.getMessage());
  }
}
