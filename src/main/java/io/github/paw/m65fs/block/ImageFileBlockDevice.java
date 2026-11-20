package io.github.paw.m65fs.block;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A disk image file (whole SD card dump or a bare FAT32 volume) as {@link BlockDevice}. */
public final class ImageFileBlockDevice implements BlockDevice {

  private final FileChannel channel;
  private final boolean readOnly;

  private ImageFileBlockDevice(FileChannel channel, boolean readOnly) {
    this.channel = channel;
    this.readOnly = readOnly;
  }

  public static ImageFileBlockDevice open(Path image, boolean readOnly) throws IOException {
    FileChannel ch = readOnly
        ? FileChannel.open(image, StandardOpenOption.READ)
        : FileChannel.open(image, StandardOpenOption.READ, StandardOpenOption.WRITE);
    return new ImageFileBlockDevice(ch, readOnly);
  }

  @Override
  public long sectorCount() throws IOException {
    return channel.size() / SECTOR_SIZE;
  }

  @Override
  public void read(long lba, ByteBuffer buf) throws IOException {
    BlockDevice.sectors(lba, buf);
    long pos = lba * SECTOR_SIZE;
    while (buf.hasRemaining()) {
      int n = channel.read(buf, pos);
      if (n < 0) {
        throw new EOFException("read beyond end of image at byte " + pos);
      }
      pos += n;
    }
  }

  @Override
  public void write(long lba, ByteBuffer buf) throws IOException {
    if (readOnly) {
      throw new IOException("image opened read-only");
    }
    BlockDevice.sectors(lba, buf);
    long pos = lba * SECTOR_SIZE;
    while (buf.hasRemaining()) {
      pos += channel.write(buf, pos);
    }
  }

  @Override
  public void flush() throws IOException {
    if (!readOnly) {
      channel.force(false);
    }
  }

  @Override
  public boolean isReadOnly() {
    return readOnly;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
