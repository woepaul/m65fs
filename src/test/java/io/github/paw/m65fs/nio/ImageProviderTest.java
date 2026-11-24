package io.github.paw.m65fs.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.paw.m65fs.fat32.FatImages;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code FileSystems.newFileSystem(Path)} asks every installed provider: m65fs must only claim
 * a file when asked for ({@code "mega65": true}) and when it is an SD card image, and leave
 * everything else to the other providers.
 */
class ImageProviderTest {

  @TempDir Path tmp;

  @Test
  void otherFilesAreLeftToOtherProviders() throws IOException {
    Path data = tmp.resolve("data.bin");
    byte[] b = new byte[4096];
    new Random(1).nextBytes(b);
    Files.write(data, b);
    assertFalse(Mega65FileSystemProvider.isImage(data));
    // no provider claims it: m65fs declined instead of failing (or opening it)
    assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(data, Map.of()));
    assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(data, Map.of("mega65", true)));
    Files.write(tmp.resolve("tiny.bin"), new byte[100]);
    assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(tmp.resolve("tiny.bin")));
  }

  @Test
  void zipFilesStillGoToTheZipProvider() throws IOException {
    Path zip = tmp.resolve("x.zip");
    try (var fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
      Files.writeString(fs.getPath("/hello.txt"), "hi");
    }
    try (var fs = FileSystems.newFileSystem(zip)) {
      assertFalse(fs instanceof Mega65FileSystem);
      assertEquals("hi", Files.readString(fs.getPath("/hello.txt")));
    }
  }

  @Test
  void imagesOpenBothWays() throws IOException {
    var img = FatImages.partitioned(tmp, 64);
    assertTrue(Mega65FileSystemProvider.isImage(img.file()));
    // without the opt-in, not even an image is claimed
    assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(img.file(), Map.of()));
    assertThrows(ProviderNotFoundException.class, () -> FileSystems.newFileSystem(img.file()));
    try (var fs = FileSystems.newFileSystem(img.file(), Map.of("mega65", true, "readOnly", true))) {
      assertInstanceOf(Mega65FileSystem.class, fs);
    }
    try (var fs = Mega65FileSystem.openImage(img.file(), Map.of("readOnly", true))) {
      assertTrue(Files.isDirectory(fs.getPath("/")));
    }
    var bare = FatImages.bareVolume(Files.createDirectories(tmp.resolve("bare")), 64);
    assertTrue(Mega65FileSystemProvider.isImage(bare.file()));
  }

  @Test
  void openImageExplainsWhatIsWrong() throws IOException {
    Path data = tmp.resolve("notes.txt");
    Files.writeString(data, "not an image");
    var e = assertThrows(FileSystemException.class, () -> Mega65FileSystem.openImage(data, Map.of()));
    assertTrue(e.getMessage().contains("FAT32"), e.getMessage());
  }
}
