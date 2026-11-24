package io.github.paw.m65fs.fat32;

import io.github.paw.m65fs.block.BlockDevice;
import io.github.paw.m65fs.block.FailFastBlockDevice;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.ReadOnlyFileSystemException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A FAT32 volume on a {@link BlockDevice}: either the first FAT32 partition of an MBR-partitioned
 * device (the MEGA65 SD card layout) or a bare volume starting at sector 0.
 *
 * <p><b>Files are always stored contiguously.</b> {@link #writeContent} lays out the complete
 * new content in one run of clusters (in place when possible, otherwise relocated) and throws
 * {@link NoContiguousSpaceException} rather than fragmenting a file. Directories may be
 * fragmented.
 *
 * <p>Every mutating operation is complete (FATs, FSInfo and directory written, device flushed)
 * when it returns. {@link DirEntry} values are snapshots; operations return the updated entry.
 * Thread-safe.
 *
 * <p>After an I/O error of the device (e.g. the MEGA65 stopped answering) the volume refuses all
 * further operations, as its cached state may no longer match the medium; close and reopen it.
 * The on-disk write order (data, then FAT, then directory entry, then freeing old clusters)
 * means an interrupted operation leaves at worst lost clusters or a file listed twice (move),
 * never a directory entry pointing to unwritten data.
 */
public final class Fat32Volume implements AutoCloseable {

  private static final Logger LOG = System.getLogger(Fat32Volume.class.getName());
  /** Upper bound for a single device transfer (sectors). */
  private static final int MAX_TRANSFER_SECTORS = 128;
  private static final int MAX_DIR_SLOTS = 65536;
  /** Sectors per read/write step when copying clusters (fewer read/write switches on the network). */
  private static final int COPY_SECTORS = 2048;
  private static final int SECTOR = BlockDevice.SECTOR_SIZE;

  private final FailFastBlockDevice dev;
  private final long volumeLba;
  private final BootSector boot;
  private final FatTable fat;
  private final FsInfo fsInfo;
  private final DirEntry root;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();

  private Fat32Volume(FailFastBlockDevice dev, long volumeLba, BootSector boot, Clock clock) throws IOException {
    this.dev = dev;
    this.volumeLba = volumeLba;
    this.boot = boot;
    this.clock = clock;
    this.fat = new FatTable(dev, volumeLba + boot.fatStart(), boot.sectorsPerFat(), boot.numberOfFats(),
        boot.maxCluster());
    this.fsInfo = FsInfo.read(dev, boot.fsInfoSector() == 0 || boot.fsInfoSector() == 0xFFFF
        ? -1 : volumeLba + boot.fsInfoSector());
    this.root = new DirEntry("", "", DirEntry.ATTR_DIRECTORY, boot.rootCluster(), 0, null, null, null,
        new DirEntry.Location(0, -1, 0));
  }

  /** Opens the volume using the system clock/time zone for timestamps. */
  public static Fat32Volume open(BlockDevice dev) throws IOException {
    return open(dev, Clock.systemDefaultZone());
  }

  /**
   * Opens the FAT32 file system on {@code dev}. Uses the first FAT32 partition (type 0x0B/0x0C)
   * of the MBR; if there is none, sector 0 is tried as a bare FAT32 volume. FAT timestamps are
   * local times in {@code clock}'s zone.
   */
  public static Fat32Volume open(BlockDevice device, Clock clock) throws IOException {
    Objects.requireNonNull(clock);
    FailFastBlockDevice dev = new FailFastBlockDevice(Objects.requireNonNull(device));
    ByteBuffer s0 = readSector(dev, 0);
    for (Partition p : Partition.parse(s0)) {
      if (p.isFat32()) {
        LOG.log(Level.DEBUG, "FAT32 partition in slot {0} at sector {1}", p.index(), p.startLba());
        return new Fat32Volume(dev, p.startLba(), BootSector.parse(readSector(dev, p.startLba())), clock);
      }
    }
    if (new String(bytes(s0, 0x52, 5), StandardCharsets.US_ASCII).equals("FAT32")) {
      return new Fat32Volume(dev, 0, BootSector.parse(s0), clock);
    }
    throw new FileSystemException(null, null, "no FAT32 partition found");
  }

  /**
   * Whether a device's sector 0 is what {@link #open} accepts: an MBR with a FAT32 partition,
   * or the boot sector of a bare FAT32 volume. A cheap check before claiming a file.
   */
  public static boolean looksLikeFat32(ByteBuffer sector0) {
    if (sector0.remaining() < BlockDevice.SECTOR_SIZE) {
      return false;
    }
    ByteBuffer s0 = sector0.slice();
    return Partition.parse(s0).stream().anyMatch(Partition::isFat32)
        || new String(bytes(s0, 0x52, 5), StandardCharsets.US_ASCII).equals("FAT32");
  }

  public BootSector bootSector() {
    return boot;
  }

  /** Absolute LBA of the volume's boot sector. */
  public long volumeLba() {
    return volumeLba;
  }

  public boolean isReadOnly() {
    return dev.isReadOnly();
  }

  /** The root directory (synthetic entry; it has no directory entry of its own). */
  public DirEntry root() {
    return root;
  }

  /** Absolute LBA of the first sector of {@code cluster}. */
  public long clusterLba(int cluster) {
    return volumeLba + boot.dataStart() + (long) (cluster - 2) * boot.sectorsPerCluster();
  }

  // ------------------------------------------------------------------ reading

  /** The cluster runs a file or directory occupies. */
  public List<Extent> extents(DirEntry entry) throws IOException {
    return locked(() -> fat.extents(entry.firstCluster()));
  }

  /** Whether the entry's data occupies a single run of clusters (empty files count as contiguous). */
  public boolean isContiguous(DirEntry entry) throws IOException {
    return extents(entry).size() <= 1;
  }

  /** Live entries of a directory, without {@code .}, {@code ..} and the volume label. */
  public List<DirEntry> list(DirEntry dir) throws IOException {
    requireDirectory(dir);
    return locked(() -> new Dir(dir.firstCluster()).visible());
  }

  /** Case-insensitive lookup of one name in {@code dir}. */
  public Optional<DirEntry> find(DirEntry dir, String name) throws IOException {
    requireDirectory(dir);
    return locked(() -> new Dir(dir.firstCluster()).find(name));
  }

  /**
   * Resolves a path given as name components from the root.
   *
   * @throws NoSuchFileException if a component does not exist
   * @throws NotDirectoryException if an intermediate component is a file
   */
  public DirEntry resolve(List<String> names) throws IOException {
    return locked(() -> {
      DirEntry cur = root;
      StringBuilder path = new StringBuilder();
      for (String n : names) {
        if (!cur.isDirectory()) {
          throw new NotDirectoryException(path.toString());
        }
        path.append('/').append(n);
        cur = new Dir(cur.firstCluster()).find(n).orElseThrow(() -> new NoSuchFileException(path.toString()));
      }
      return cur;
    });
  }

  /**
   * The entries from the root down to the directory that starts at {@code cluster}, found by
   * following the {@code ..} entries upwards (empty for the root directory, which cluster 0 also
   * stands for). This is how a working directory known only by its cluster, as the MEGA65
   * hypervisor keeps it, becomes a path.
   *
   * @throws NoSuchFileException if no directory reachable from the root starts at {@code cluster}
   */
  public List<DirEntry> directoryPath(int cluster) throws IOException {
    return locked(() -> {
      List<DirEntry> path = new ArrayList<>();
      int cur = cluster == 0 ? boot.rootCluster() : cluster;
      for (int depth = 0; cur != boot.rootCluster(); depth++) {
        if (depth == 4096 || cur < 2 || cur > boot.maxCluster()) {
          throw new NoSuchFileException("directory at cluster " + cluster);
        }
        int child = cur;
        int parent = new Dir(child).all().stream().filter(e -> e.name().equals("..")).findFirst()
            .orElseThrow(() -> new NoSuchFileException("directory at cluster " + cluster)).firstCluster();
        cur = parent == 0 ? boot.rootCluster() : parent;
        path.addFirst(new Dir(cur).visible().stream()
            .filter(e -> e.isDirectory() && e.firstCluster() == child).findFirst()
            .orElseThrow(() -> new NoSuchFileException("directory at cluster " + cluster)));
      }
      return path;
    });
  }

  /**
   * Reads file data at byte offset {@code position} into {@code dst}.
   *
   * @return number of bytes read, or {@code -1} at end of file
   */
  public int read(DirEntry file, long position, ByteBuffer dst) throws IOException {
    if (file.isDirectory()) {
      throw new FileSystemException(file.name(), null, "is a directory");
    }
    if (position >= file.size()) {
      return -1;
    }
    int want = (int) Math.min(dst.remaining(), file.size() - position);
    return locked(() -> readClusters(fat.extents(file.firstCluster()), position, dst, want));
  }

  /** Number of free clusters (from FSInfo if plausible, otherwise by scanning the FAT). */
  public long freeClusters() throws IOException {
    return locked(() -> {
      long fc = fsInfo.freeCount();
      if (fc < 0 || fc > boot.clusterCount()) {
        fc = fat.countFree();
        fsInfo.setFreeCount(fc);
      }
      return fc;
    });
  }

  /** Length (in clusters) of the largest run of free clusters — the biggest file that fits. Scans the FAT. */
  public long largestFreeRun() throws IOException {
    return locked(() -> {
      var r = fat.findFreeRun(Integer.MAX_VALUE, 2);
      return (long) r.length();
    });
  }

  // ------------------------------------------------------------------ writing

  /** Creates an empty file. */
  public DirEntry createFile(DirEntry dir, String name) throws IOException {
    requireDirectory(dir);
    return mutate(() -> new Dir(dir.firstCluster()).add(name, DirEntry.ATTR_ARCHIVE, 0, 0));
  }

  /** Creates a directory (one cluster with {@code .} and {@code ..}). */
  public DirEntry createDirectory(DirEntry parent, String name) throws IOException {
    requireDirectory(parent);
    return mutate(() -> {
      Dir p = new Dir(parent.firstCluster());
      ShortNames.validateLongName(name);
      p.requireAbsent(name, null);
      int c = allocateNear(0);
      byte[] cluster = new byte[boot.bytesPerCluster()];
      LocalDateTime now = now();
      int parentRef = parent.firstCluster() == boot.rootCluster() ? 0 : parent.firstCluster();
      System.arraycopy(DirEntryCodec.encode(null, dotName("."), DirEntry.ATTR_DIRECTORY, c, 0, now), 0, cluster, 0, 32);
      System.arraycopy(DirEntryCodec.encode(null, dotName(".."), DirEntry.ATTR_DIRECTORY, parentRef, 0, now), 0, cluster, 32, 32);
      writeClusterData(c, cluster);
      fat.set(c, FatTable.END_OF_CHAIN);
      fat.flush();
      return p.add(name, DirEntry.ATTR_DIRECTORY, c, 0);
    });
  }

  /**
   * Deletes a file or an empty directory.
   *
   * @throws DirectoryNotEmptyException if a directory still has entries
   */
  public void delete(DirEntry entry) throws IOException {
    requireNotRoot(entry);
    mutate(() -> {
      if (entry.isDirectory() && !new Dir(entry.firstCluster()).visible().isEmpty()) {
        throw new DirectoryNotEmptyException(entry.name());
      }
      List<Extent> chain = fat.extents(entry.firstCluster());
      // directory entry first: an interruption then only leaks clusters
      new Dir(entry.location().directoryCluster()).markDeleted(entry.location());
      fat.free(chain);
      fat.flush();
      return null;
    });
  }

  /**
   * Moves/renames {@code entry} to {@code newName} in {@code targetDir}. Times, attributes, size
   * and data stay as they are.
   *
   * @throws FileAlreadyExistsException if the target name exists (and is not {@code entry} itself)
   */
  public DirEntry move(DirEntry entry, DirEntry targetDir, String newName) throws IOException {
    requireNotRoot(entry);
    requireDirectory(targetDir);
    return mutate(() -> {
      ShortNames.validateLongName(newName);
      int sourceCluster = entry.location().directoryCluster();
      Dir source = new Dir(sourceCluster);
      Dir target = sourceCluster == targetDir.firstCluster() ? source : new Dir(targetDir.firstCluster());
      target.requireAbsent(newName, entry);
      if (entry.isDirectory() && target != source && isSelfOrDescendant(targetDir.firstCluster(), entry.firstCluster())) {
        throw new FileSystemException(entry.name(), newName, "cannot move a directory into itself");
      }
      byte[] oldShort = source.slot(entry.location().shortSlot());
      // new entry first, then remove the old one: an interruption leaves the file listed twice,
      // never lost
      DirEntry moved = target.addCopy(newName, oldShort, target == source ? entry.location() : null);
      source.markDeleted(entry.location());
      if (entry.isDirectory() && target != source) {
        Dir self = new Dir(entry.firstCluster());
        self.setDotDot(targetDir.firstCluster() == boot.rootCluster() ? 0 : targetDir.firstCluster());
      }
      return moved;
    });
  }

  /**
   * Replaces the content of a file with {@code size} bytes read from {@code src}, keeping the
   * file contiguous:
   * <ol>
   *   <li>contiguous and big enough: rewritten in place (surplus clusters freed);
   *   <li>contiguous and the following clusters are free: extended in place;
   *   <li>otherwise: written to a new contiguous run, then the entry is switched over and the
   *       old clusters are freed.
   * </ol>
   *
   * @return the updated entry
   * @throws NoContiguousSpaceException if no free run is large enough; the file is unchanged
   */
  public DirEntry writeContent(DirEntry file, ReadableByteChannel src, long size) throws IOException {
    requireNotRoot(file);
    if (!file.isRegularFile()) {
      throw new FileSystemException(file.name(), null, "not a regular file");
    }
    if (size < 0 || size > 0xFFFF_FFFFL) {
      throw new FileSystemException(file.name(), null, "file size must be 0..4 GiB-1: " + size);
    }
    return mutate(() -> {
      int bpc = boot.bytesPerCluster();
      int need = (int) ((size + bpc - 1) / bpc);
      List<Extent> old = fat.extents(file.firstCluster());
      int oldLen = old.stream().mapToInt(Extent::length).sum();

      enum Plan { EMPTY, IN_PLACE, SHRINK, EXTEND, RELOCATE }
      Plan plan;
      int start = 0;
      if (need == 0) {
        plan = Plan.EMPTY;
      } else if (old.size() == 1 && oldLen >= need) {
        plan = oldLen == need ? Plan.IN_PLACE : Plan.SHRINK;
        start = old.getFirst().first();
      } else if (old.size() == 1 && rangeFree(old.getFirst().end(), old.getFirst().first() + need)) {
        plan = Plan.EXTEND;
        start = old.getFirst().first();
      } else {
        plan = Plan.RELOCATE;
        FatTable.FreeRun run = fat.findFreeRun(need, hint());
        if (run.length() < need) {
          throw new NoContiguousSpaceException(file.name(), need, run.length(), bpc);
        }
        start = run.start();
      }
      LOG.log(Level.DEBUG, "write {0}: {1} bytes, {2} clusters, plan {3} at {4}", file.name(), size, need, plan, start);

      // 1. data (for EXTEND/RELOCATE into clusters nobody references yet)
      if (need > 0) {
        writeData(clusterLba(start), src, size);
      }
      // 2. make the new chain reachable before the entry points to it
      if (plan == Plan.EXTEND || plan == Plan.RELOCATE) {
        fat.linkRun(start, need);
        fat.flush();
      }
      // 3. directory entry
      Dir parent = new Dir(file.location().directoryCluster());
      LocalDateTime now = now();
      int newStart = start;
      DirEntry updated = parent.patch(file.location(), (b, off) -> {
        DirEntryCodec.setCluster(b, off, newStart);
        b.putInt(off + 28, (int) size);
        b.put(off + 11, (byte) (b.get(off + 11) | DirEntry.ATTR_ARCHIVE));
        DirEntryCodec.setTimes(b, off, null, now, now);
      });
      // 4. release what is no longer referenced
      switch (plan) {
        case SHRINK -> {
          fat.linkRun(start, need);
          fat.free(List.of(new Extent(start + need, oldLen - need)));
        }
        case EMPTY, RELOCATE -> fat.free(old);
        default -> { }
      }
      fat.flush();
      if (need > 0) {
        fsInfo.setNextFree(start + need <= boot.maxCluster() ? start + need : 2);
      }
      return updated;
    });
  }

  /**
   * Makes a fragmented file contiguous: copies its clusters (raw) into one free run, points the
   * directory entry at the copy and then frees the old clusters. Size, timestamps and attributes
   * are unchanged; only the start cluster moves. A contiguous file is returned as it is.
   *
   * <p>Same crash safety as {@link #writeContent}: until the directory entry is switched the
   * file is untouched; an interruption leaves at worst lost clusters.
   *
   * @param file a current entry (re-resolve it if the volume changed since it was read)
   * @return the updated entry
   * @throws NoContiguousSpaceException if no free run can hold the file; nothing is changed
   */
  public DirEntry defragment(DirEntry file) throws IOException {
    requireNotRoot(file);
    if (!file.isRegularFile()) {
      throw new FileSystemException(file.name(), null, "not a regular file");
    }
    return mutate(() -> {
      List<Extent> old = fat.extents(file.firstCluster());
      if (old.size() <= 1) {
        return file;
      }
      // keep the chain's exact length (normally ceil(size / cluster size))
      int need = old.stream().mapToInt(Extent::length).sum();
      int bpc = boot.bytesPerCluster();
      FatTable.FreeRun run = fat.findFreeRun(need, hint());
      if (run.length() < need) {
        throw new NoContiguousSpaceException(file.name(), need, run.length(), bpc);
      }
      int start = run.start();
      LOG.log(Level.DEBUG, "defragment {0}: {1} fragments, {2} clusters -> {3}", file.name(), old.size(), need, start);

      // 1. copy the data into clusters nobody references yet
      int spc = boot.sectorsPerCluster();
      ByteBuffer buf = ByteBuffer.allocate(COPY_SECTORS * SECTOR);
      long dst = clusterLba(start);
      for (Extent e : old) {
        long src = clusterLba(e.first());
        long left = (long) e.length() * spc;
        while (left > 0) {
          int n = (int) Math.min(COPY_SECTORS, left);
          buf.clear().limit(n * SECTOR);
          dev.read(src, buf);
          dev.write(dst, buf.flip());
          src += n;
          dst += n;
          left -= n;
        }
      }
      // 2. the new chain, 3. switch the entry, 4. free the old clusters
      fat.linkRun(start, need);
      fat.flush();
      DirEntry updated = new Dir(file.location().directoryCluster()).patch(file.location(),
          (b, off) -> DirEntryCodec.setCluster(b, off, start));
      fat.free(old);
      fat.flush();
      fsInfo.setNextFree(start + need <= boot.maxCluster() ? start + need : 2);
      return updated;
    });
  }

  /** Writes all bytes of {@code data} as the new content. */
  public DirEntry writeContent(DirEntry file, byte[] data) throws IOException {
    return writeContent(file, java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(data)), data.length);
  }

  /** Sets the FAT attribute bits (read-only, hidden, system, archive); type bits are preserved. */
  public DirEntry setAttributes(DirEntry entry, int attributes) throws IOException {
    requireNotRoot(entry);
    int settable = DirEntry.ATTR_READ_ONLY | DirEntry.ATTR_HIDDEN | DirEntry.ATTR_SYSTEM | DirEntry.ATTR_ARCHIVE;
    return mutate(() -> new Dir(entry.location().directoryCluster()).patch(entry.location(), (b, off) ->
        b.put(off + 11, (byte) ((b.get(off + 11) & ~settable) | (attributes & settable)))));
  }

  /** Sets times; {@code null} leaves a time unchanged. */
  public DirEntry setTimes(DirEntry entry, LocalDateTime created, LocalDateTime modified, LocalDateTime accessed)
      throws IOException {
    requireNotRoot(entry);
    return mutate(() -> new Dir(entry.location().directoryCluster()).patch(entry.location(), (b, off) ->
        DirEntryCodec.setTimes(b, off, created, modified, accessed)));
  }

  /** Writes pending FAT/FSInfo changes and flushes the device. */
  public void flush() throws IOException {
    locked(() -> {
      flushMetadata();
      return null;
    });
  }

  @Override
  public void close() throws IOException {
    try {
      if (!dev.isReadOnly() && dev.failure() == null) {
        flush();
      }
    } finally {
      dev.close();
    }
  }

  // ------------------------------------------------------------------ internals

  private boolean rangeFree(int from, int toExclusive) throws IOException {
    if (toExclusive - 1 > boot.maxCluster()) {
      return false;
    }
    for (int c = from; c < toExclusive; c++) {
      if (!fat.isFree(c)) {
        return false;
      }
    }
    return true;
  }

  private int hint() {
    long nf = fsInfo.nextFree();
    return nf >= 2 && nf <= boot.maxCluster() ? (int) nf : 2;
  }

  /** Allocates one cluster, preferably {@code after + 1} (keeps directories contiguous when possible). */
  private int allocateNear(int after) throws IOException {
    if (after >= 2 && after + 1 <= boot.maxCluster() && fat.isFree(after + 1)) {
      return after + 1;
    }
    FatTable.FreeRun r = fat.findFreeRun(1, hint());
    if (r.length() < 1) {
      throw new FileSystemException(null, null, "disk full");
    }
    fsInfo.setNextFree(r.start() + 1);
    return r.start();
  }

  private void writeClusterData(int cluster, byte[] data) throws IOException {
    dev.write(clusterLba(cluster), ByteBuffer.wrap(data));
  }

  /** Streams {@code size} bytes from {@code src} to consecutive sectors starting at {@code lba}. */
  private void writeData(long lba, ReadableByteChannel src, long size) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(MAX_TRANSFER_SECTORS * SECTOR);
    long remaining = size;
    while (remaining > 0) {
      buf.clear();
      int want = (int) Math.min(buf.capacity(), remaining);
      buf.limit(want);
      while (buf.hasRemaining()) {
        if (src.read(buf) < 0) {
          throw new EOFException("source ended " + (remaining - buf.position()) + " bytes early");
        }
      }
      int sectors = (want + SECTOR - 1) / SECTOR;
      buf.limit(sectors * SECTOR);
      while (buf.position() < buf.limit()) {
        buf.put((byte) 0); // pad the last sector
      }
      buf.flip();
      dev.write(lba, buf);
      lba += sectors;
      remaining -= want;
    }
  }

  private boolean isSelfOrDescendant(int dirCluster, int ancestorCluster) throws IOException {
    int cur = dirCluster;
    for (int depth = 0; depth < 4096 && cur != boot.rootCluster(); depth++) {
      if (cur == ancestorCluster) {
        return true;
      }
      int parent = new Dir(cur).dotDot();
      cur = parent == 0 ? boot.rootCluster() : parent;
    }
    return false;
  }

  /** Reads {@code want} bytes starting at byte {@code position} of the given cluster runs. */
  private int readClusters(List<Extent> extents, long position, ByteBuffer dst, int want) throws IOException {
    int clusterBytes = boot.bytesPerCluster();
    int done = 0;
    long extentStart = 0; // byte offset of the current extent within the data
    for (Extent ext : extents) {
      long extentBytes = (long) ext.length() * clusterBytes;
      if (position >= extentStart + extentBytes) {
        extentStart += extentBytes;
        continue;
      }
      while (done < want && position < extentStart + extentBytes) {
        long inExtent = position - extentStart;
        long lba = clusterLba(ext.first()) + inExtent / SECTOR;
        int inSector = (int) (inExtent % SECTOR);
        int left = want - done;
        long sectorsLeftInExtent = (extentBytes - inExtent) / SECTOR;
        if (inSector == 0 && left >= SECTOR) {
          int n = (int) Math.min(Math.min(left / SECTOR, sectorsLeftInExtent), MAX_TRANSFER_SECTORS);
          int bytes = n * SECTOR;
          dev.read(lba, dst.slice(dst.position(), bytes));
          dst.position(dst.position() + bytes);
          done += bytes;
          position += bytes;
        } else {
          ByteBuffer sector = readSector(dev, lba);
          int n = Math.min(left, SECTOR - inSector);
          dst.put(sector.slice(inSector, n));
          done += n;
          position += n;
        }
      }
      if (done == want) {
        break;
      }
      extentStart += extentBytes;
    }
    if (done < want) {
      throw new FileSystemException(null, null, "cluster chain shorter than file size");
    }
    return done;
  }

  /** For tests in this package (FAT surgery to create fragmented files). */
  FatTable fatTable() {
    return fat;
  }

  /** For tests in this package. */
  BlockDevice device() {
    return dev;
  }

    private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private void flushMetadata() throws IOException {
    fat.flush();
    long delta = fat.takeFreeDelta();
    if (delta != 0 && fsInfo.freeCount() >= 0) {
      fsInfo.setFreeCount(fsInfo.freeCount() + delta);
    }
    fsInfo.write(dev);
    dev.flush();
  }

  @FunctionalInterface
  private interface IoAction<T> {
    T run() throws IOException;
  }

  private <T> T locked(IoAction<T> action) throws IOException {
    lock.lock();
    try {
      dev.check();
      return action.run();
    } finally {
      lock.unlock();
    }
  }

  private <T> T mutate(IoAction<T> action) throws IOException {
    if (dev.isReadOnly()) {
      throw new ReadOnlyFileSystemException();
    }
    return locked(() -> {
      T result;
      try {
        result = action.run();
      } catch (IOException | RuntimeException e) {
        if (dev.failure() == null) {
          try {
            flushMetadata(); // e.g. a validation error after some metadata changes
          } catch (IOException | RuntimeException f) {
            e.addSuppressed(f);
          }
        }
        throw e;
      }
      flushMetadata();
      return result;
    });
  }

  private void requireNotRoot(DirEntry e) throws FileSystemException {
    if (e.location().firstSlot() < 0) {
      throw new FileSystemException("/", null, "operation not possible on the root directory");
    }
  }

  private static void requireDirectory(DirEntry dir) throws NotDirectoryException {
    if (!dir.isDirectory()) {
      throw new NotDirectoryException(dir.name());
    }
  }

  private static byte[] dotName(String dots) {
    byte[] n = "           ".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(dots.getBytes(StandardCharsets.US_ASCII), 0, n, 0, dots.length());
    return n;
  }

  private static ByteBuffer readSector(BlockDevice dev, long lba) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(SECTOR);
    dev.read(lba, b);
    return b.flip();
  }

  private static byte[] bytes(ByteBuffer b, int off, int len) {
    byte[] r = new byte[len];
    b.get(off, r);
    return r;
  }

  @FunctionalInterface
  private interface SlotPatch {
    void apply(ByteBuffer slots, int offset);
  }

  /** A loaded directory: its raw slots plus the cluster runs they live in. Used under the lock. */
  private final class Dir {
    final int firstCluster;
    final List<Extent> extents;
    byte[] raw;

    Dir(int firstCluster) throws IOException {
      this.firstCluster = firstCluster;
      this.extents = new ArrayList<>(fat.extents(firstCluster));
      long clusters = extents.stream().mapToLong(Extent::length).sum();
      long bytes = clusters * boot.bytesPerCluster();
      if (bytes > (long) MAX_DIR_SLOTS * DirEntryCodec.SLOT) {
        throw new FileSystemException(null, null, "directory larger than the FAT limit of 65536 entries");
      }
      ByteBuffer b = ByteBuffer.allocate((int) bytes);
      readClusters(extents, 0, b, (int) bytes);
      this.raw = b.array();
    }

    List<DirEntry> all() {
      return DirectoryParser.parse(ByteBuffer.wrap(raw), firstCluster);
    }

    List<DirEntry> visible() {
      return all().stream()
          .filter(e -> !e.isDotEntry() && (e.attributes() & DirEntry.ATTR_VOLUME_ID) == 0)
          .toList();
    }

    Optional<DirEntry> find(String name) {
      return visible().stream().filter(e -> e.matches(name)).findFirst();
    }

    void requireAbsent(String name, DirEntry except) throws FileAlreadyExistsException {
      Optional<DirEntry> existing = find(name);
      if (existing.isPresent() && (except == null || !existing.get().location().equals(except.location()))) {
        throw new FileAlreadyExistsException(name);
      }
    }

    /** Raw 11-byte short names of all live entries (except the one at {@code exclude}). */
    Set<String> shortNames(DirEntry.Location exclude) {
      Set<String> names = new HashSet<>();
      for (DirEntry e : all()) {
        if (e.location().equals(exclude)) {
          continue;
        }
        names.add(new String(raw, e.location().shortSlot() * DirEntryCodec.SLOT, 11, StandardCharsets.ISO_8859_1));
      }
      return names;
    }

    byte[] slot(int index) {
      byte[] s = new byte[DirEntryCodec.SLOT];
      System.arraycopy(raw, index * DirEntryCodec.SLOT, s, 0, DirEntryCodec.SLOT);
      return s;
    }

    /** Creates a new entry (long name + 8.3 alias as needed). */
    DirEntry add(String name, int attributes, int firstCluster, long size) throws IOException {
      ShortNames.validateLongName(name);
      requireAbsent(name, null);
      byte[] exact = ShortNames.exact(name);
      byte[] short11 = exact != null ? exact : ShortNames.alias(name, ShortNames.in(shortNames(null)));
      byte[] slots = DirEntryCodec.encode(exact != null ? null : name, short11, attributes, firstCluster, size, now());
      return place(slots);
    }

    /**
     * Creates an entry named {@code name} carrying the fields of an existing 8.3 slot; the entry
     * at {@code replacing} (about to be deleted) does not count as a short-name collision.
     */
    DirEntry addCopy(String name, byte[] oldShortSlot, DirEntry.Location replacing) throws IOException {
      ShortNames.validateLongName(name);
      byte[] exact = ShortNames.exact(name);
      byte[] short11 = exact != null ? exact : ShortNames.alias(name, ShortNames.in(shortNames(replacing)));
      byte[] slots = DirEntryCodec.encode(exact != null ? null : name, short11, 0, 0, 0, now());
      int off = slots.length - DirEntryCodec.SLOT;
      System.arraycopy(oldShortSlot, 11, slots, off + 11, DirEntryCodec.SLOT - 11);
      slots[off + 12] = 0; // NT case flags belonged to the old short name
      return place(slots);
    }

    private DirEntry place(byte[] slots) throws IOException {
      int k = slots.length / DirEntryCodec.SLOT;
      int start = allocateSlots(k);
      writeSlots(start, slots);
      DirEntry parsed = DirectoryParser.parse(ByteBuffer.wrap(slots), firstCluster).getFirst();
      return new DirEntry(parsed.name(), parsed.shortName(), parsed.attributes(), parsed.firstCluster(),
          parsed.size(), parsed.created(), parsed.modified(), parsed.accessed(),
          new DirEntry.Location(firstCluster, start, k));
    }

    void markDeleted(DirEntry.Location loc) throws IOException {
      byte[] slots = new byte[loc.slotCount() * DirEntryCodec.SLOT];
      System.arraycopy(raw, loc.firstSlot() * DirEntryCodec.SLOT, slots, 0, slots.length);
      for (int i = 0; i < loc.slotCount(); i++) {
        slots[i * DirEntryCodec.SLOT] = (byte) DirectoryParser.DELETED;
      }
      writeSlots(loc.firstSlot(), slots);
    }

    /** Applies {@code patch} to the 8.3 slot of {@code loc} and returns the re-parsed entry. */
    DirEntry patch(DirEntry.Location loc, SlotPatch patch) throws IOException {
      byte[] slots = new byte[loc.slotCount() * DirEntryCodec.SLOT];
      System.arraycopy(raw, loc.firstSlot() * DirEntryCodec.SLOT, slots, 0, slots.length);
      ByteBuffer b = ByteBuffer.wrap(slots).order(ByteOrder.LITTLE_ENDIAN);
      patch.apply(b, slots.length - DirEntryCodec.SLOT);
      writeSlots(loc.shortSlot(), java.util.Arrays.copyOfRange(slots, slots.length - DirEntryCodec.SLOT, slots.length));
      DirEntry parsed = DirectoryParser.parse(ByteBuffer.wrap(slots), firstCluster).getFirst();
      return new DirEntry(parsed.name(), parsed.shortName(), parsed.attributes(), parsed.firstCluster(),
          parsed.size(), parsed.created(), parsed.modified(), parsed.accessed(), loc);
    }

    int dotDot() {
      for (DirEntry e : all()) {
        if (e.name().equals("..")) {
          return e.firstCluster();
        }
      }
      return 0;
    }

    void setDotDot(int cluster) throws IOException {
      for (DirEntry e : all()) {
        if (e.name().equals("..")) {
          patch(e.location(), (b, off) -> DirEntryCodec.setCluster(b, off, cluster));
          return;
        }
      }
    }

    /**
     * Finds {@code k} consecutive free slots (deleted or past the end marker), extending the
     * directory by zeroed clusters if needed. Returns the first slot index.
     */
    int allocateSlots(int k) throws IOException {
      int slots = raw.length / DirEntryCodec.SLOT;
      int runStart = -1;
      int runLen = 0;
      boolean pastEnd = false;
      for (int s = 0; s < slots; s++) {
        int first = raw[s * DirEntryCodec.SLOT] & 0xff;
        pastEnd |= first == 0x00;
        if (pastEnd || first == DirectoryParser.DELETED) {
          if (runLen++ == 0) {
            runStart = s;
          }
          if (runLen == k) {
            return runStart;
          }
        } else {
          runLen = 0;
        }
      }
      // the trailing free run (if any) continues into newly allocated clusters
      int start = runLen > 0 ? runStart : slots;
      int needBytes = (start + k) * DirEntryCodec.SLOT;
      while (raw.length < needBytes) {
        extend();
      }
      return start;
    }

    private void extend() throws IOException {
      if (raw.length + boot.bytesPerCluster() > MAX_DIR_SLOTS * DirEntryCodec.SLOT) {
        throw new FileSystemException(null, null, "directory full (65536 entries)");
      }
      int last = extents.getLast().end() - 1;
      int c = allocateNear(last);
      writeClusterData(c, new byte[boot.bytesPerCluster()]);
      fat.set(c, FatTable.END_OF_CHAIN);
      fat.set(last, c);
      fat.flush();
      if (c == last + 1) {
        Extent e = extents.removeLast();
        extents.add(new Extent(e.first(), e.length() + 1));
      } else {
        extents.add(new Extent(c, 1));
      }
      raw = java.util.Arrays.copyOf(raw, raw.length + boot.bytesPerCluster());
    }

    /** Copies {@code data} into the slots starting at {@code first} and writes the affected sectors. */
    void writeSlots(int first, byte[] data) throws IOException {
      int from = first * DirEntryCodec.SLOT;
      System.arraycopy(data, 0, raw, from, data.length);
      int firstSector = from / SECTOR;
      int lastSector = (from + data.length - 1) / SECTOR;
      for (int s = firstSector; s <= lastSector; s++) {
        dev.write(sectorLba(s), ByteBuffer.wrap(raw, s * SECTOR, SECTOR));
      }
    }

    /** Absolute LBA of the {@code index}-th sector of this directory's data. */
    private long sectorLba(int index) {
      int spc = boot.sectorsPerCluster();
      int clusterIndex = index / spc;
      for (Extent e : extents) {
        if (clusterIndex < e.length()) {
          return clusterLba(e.first() + clusterIndex) + index % spc;
        }
        clusterIndex -= e.length();
      }
      throw new IllegalStateException("sector " + index + " beyond directory end");
    }
  }
}
