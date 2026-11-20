package io.github.paw.m65fs.launch;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** VICE's petcat and c1541 for building test programs and disk images (tests skip without them). */
public final class CbmTools {

  private CbmTools() {}

  public static void assumeInstalled() {
    for (String tool : List.of("petcat", "c1541")) {
      boolean found = false;
      for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
        if (Files.isExecutable(Path.of(dir, tool))) {
          found = true;
        }
      }
      assumeTrue(found, tool + " (VICE) not installed");
    }
  }

  /**
   * Tokenizes a BASIC listing. {@code version} is petcat's: "2" (C64) or "65" (MEGA65). Use
   * lower case for keywords.
   */
  public static byte[] basic(Path dir, String version, String listing) throws IOException {
    assumeInstalled();
    Path src = Files.createTempFile(dir, "prog", ".bas");
    Path out = Files.createTempFile(dir, "prog", ".prg");
    Files.writeString(src, listing);
    run(List.of("petcat", "-w" + version, "-o", out.toString(), "--", src.toString()));
    return Files.readAllBytes(out);
  }

  /** One file to put on a disk: {@code name} in lower-case ASCII (c1541 turns it into PETSCII). */
  public record DiskFile(String name, char type, byte[] content) {}

  /** Creates a D81 with the files in order. */
  public static Path d81(Path dir, String diskName, DiskFile... files) throws IOException {
    assumeInstalled();
    Path img = dir.resolve(diskName.replace(' ', '_') + ".d81");
    List<String> cmd = new ArrayList<>(List.of("c1541", "-format", diskName + ",65", "d81", img.toString()));
    int i = 0;
    for (DiskFile f : files) {
      Path p = dir.resolve("file" + i++);
      Files.write(p, f.content());
      cmd.addAll(List.of("-write", p.toString(), f.name() + (f.type() == 'p' ? "" : "," + f.type())));
    }
    run(cmd);
    return img;
  }

  private static void run(List<String> cmd) throws IOException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    try {
      if (p.waitFor() != 0) {
        throw new IOException(String.join(" ", cmd) + " failed:\n" + out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}
