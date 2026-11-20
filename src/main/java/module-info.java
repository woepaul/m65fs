/**
 * Java NIO file system for the MEGA65 SD card, accessed over Ethernet using the
 * {@code mega65_ftp -e} protocol.
 *
 * <p>Entry point: {@code FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())}.
 */
module io.github.paw.m65fs {
  exports io.github.paw.m65fs.nio;
  exports io.github.paw.m65fs.fat32;
  exports io.github.paw.m65fs.block;
  exports io.github.paw.m65fs.remotesd;
  exports io.github.paw.m65fs.etherload;
  exports io.github.paw.m65fs.transport;

  provides java.nio.file.spi.FileSystemProvider with io.github.paw.m65fs.nio.Mega65FileSystemProvider;
}
