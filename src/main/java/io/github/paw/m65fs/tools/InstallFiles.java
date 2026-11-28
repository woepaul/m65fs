package io.github.paw.m65fs.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The files that {@code m65 install} and {@code m65 completion} write (the bash script, the
 * Windows launcher, ...). In the native image they are one zip resource, {@link #ZIP}, which
 * tools/build-native.sh puts together for its platform (only what that platform needs: the
 * launcher only on Windows); native-image stores resources uncompressed, so zipped they cost
 * less. On the JVM (tests, the jar) they are read from {@link #DIR} instead, where their sources
 * are.
 */
final class InstallFiles {

  static final String ZIP = "/io/github/paw/m65fs/install.zip";
  static final String DIR = "/io/github/paw/m65fs/install/";

  private InstallFiles() {}

  /** The content of {@code name}, or null if this build has no such file. */
  static byte[] read(String name) throws IOException {
    try (InputStream zip = InstallFiles.class.getResourceAsStream(ZIP)) {
      if (zip != null) {
        try (ZipInputStream z = new ZipInputStream(zip)) {
          for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
            if (e.getName().equals(name)) {
              return z.readAllBytes();
            }
          }
        }
        return null;
      }
    }
    try (InputStream plain = InstallFiles.class.getResourceAsStream(DIR + name)) {
      return plain == null ? null : plain.readAllBytes();
    }
  }
}
