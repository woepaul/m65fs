# m65fs

A Java NIO file system for the SD card of a [MEGA65](https://mega65.org), accessed over
Ethernet with the same protocol as `mega65_ftp -e`. With it, any Java code that uses
`java.nio.file.Files` can work on the card: copy, move, list, delete and stream files. It
can also mount D81 images.

```java
try (FileSystem fs = FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())) {
  Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"), StandardCopyOption.REPLACE_EXISTING);
  try (Stream<Path> s = Files.list(fs.getPath("/"))) {
    s.forEach(System.out::println);
  }
  ((Mega65FileSystem) fs).mountD81(fs.getPath("/GAME.D81"));
}   // closing ends the session; the MEGA65 resets
```

Pure Java (25+), no native code, no dependencies.

## How it works

The library talks to the MEGA65 the same way `mega65_ftp -e` does:

1. **Bootstrap.** A trigger packet puts the MEGA65 hypervisor into Ethernet remote control.
   Small 6502 programs ("ethlets") then upload the helper `remotesd_eth.prg` and `RUN` it.
2. **Session.** The helper serves raw SD card sectors over UDP (IPv6 link-local, port 4510).
   The library sends requests through a sliding window with retransmission.
3. **FAT32 on the PC.** All file system logic runs in Java: MBR, FAT32, VFAT long names and
   cluster allocation. The MEGA65 is only a remote block device.

`PLAN.md` has the full protocol analysis.

## Requirements on the MEGA65

* Ethernet cable to the same network segment as the PC. IPv6 link-local must work; that is
  the default on Linux, macOS and Windows.
* Remote mode on, which makes the MEGA65 send its "mega65" discovery beacon: DIP switch 2 on,
  or press SHIFT+£.
* Nothing to install on the card. The helper is bundled (see [Blobs](#blobs)).

## Opening a file system

| URI / call | Meaning |
|---|---|
| `mega65://auto/` (or `mega65:///`) | Discover the MEGA65 on all interfaces (2.5 s). |
| `mega65://[fe80::4065:1ff:fe00:1%eth0]/` | Fixed address with its interface. `%25eth0` (RFC 6874) works too. |
| `FileSystems.newFileSystem(Path.of("card.img"), env)` | A local SD card image (MBR + FAT32, or a bare FAT32 volume). |
| `mega65://name/` with env `imageFile` | A local image registered under a URI, so `Path.of(URI)` works. |

There is one session per MEGA65, because the helper locks itself to the first client.
`FileSystems.getFileSystem(uri)` returns the open instance.

### Options (`env` map)

| Key | Default | Meaning |
|---|---|---|
| `target` | — | `fe80::…%if`; overrides the URI host |
| `discoveryTimeout` | 2500 ms | Time to wait for the beacon with `auto` |
| `timeout` | 3000 ms | Per-request timeout (each request is retransmitted within it) |
| `writeWindow` | 3 or 28 | Unacknowledged write packets; the default depends on the board model, as in `mega65_ftp` |
| `readAhead` | 16 | Sectors read ahead on cache misses |
| `quitOnClose` | `true` | Send quit on close. The MEGA65 then resets and leaves the helper. |
| `readOnly` | `false` | Reject all changes |
| `zone` | system | Time zone for FAT timestamps, which are stored as local times |
| `imageFile` | — | Use a local image instead of the network |
| `shadowMemoryLimit` | 8 MiB | Write buffer per open channel kept in memory before spilling to a temp file |

Durations accept a `Duration`, a number of milliseconds, or an ISO-8601 string (`PT5S`).

## Files are always contiguous

Some MEGA65 cores can't read fragmented files, and the hypervisor refuses to mount a
fragmented D81. So **this library never fragments a file**. Writes go to a buffer on the PC.
When the channel or stream is closed, the complete content is written to one contiguous run
of clusters: in place if it fits, otherwise relocated, and only then is the old copy freed.
If no free run is large enough, `close()` throws
`io.github.paw.m65fs.fat32.NoContiguousSpaceException` and the file keeps its previous
content. `Mega65FileStore.getLargestContiguousSpace()` returns the largest file that still
fits.

Directories may fragment. The hypervisor follows their cluster chains, and so does every
other FAT implementation.

Files that are already fragmented (written by other tools) can be read. Rewriting one
defragments it.

## When changes are on the card

Every operation is complete on the SD card when it returns. That covers `Files.write`,
`copy`, `move`, `delete`, `createDirectory`, attribute changes and closing a channel or
stream. Data, both FATs, FSInfo and the directory entry are written, and the MEGA65 has
acknowledged them. The helper acknowledges a batch only after writing it and reading it back
to verify. Nothing waits on the PC to be written later.

The only exception is a write channel that is still open: its content stays on the PC until
it is closed. `Mega65FileSystem.commit()` writes it anyway and leaves the channel open. With
no channels open, `commit()` returns at once. Either way the session stays open, so there is
no need to close (and reset) just to be sure things are saved.

```java
var m65 = (Mega65FileSystem) fs;
try (var log = Files.newByteChannel(fs.getPath("/LOG.BIN"), CREATE, WRITE, APPEND)) {
  log.write(chunk);
  m65.commit();      // LOG.BIN is on the card now; keep writing
}
```

Closing the file system closes any write channels still open, which writes their content.

## What is supported

* **Paths.** Absolute and relative, `/` separator. They compare case-insensitively like FAT
  (`/Games/README.TXT` equals `/games/readme.txt`). `toRealPath()` returns the names as stored
  on the card.
* **Names.** VFAT long names (up to 255 UTF-16 chars) with generated 8.3 aliases (`LONGNA~1.D81`).
* **Operations.** `Files.newByteChannel` / streams (READ, WRITE, APPEND, CREATE, CREATE_NEW,
  TRUNCATE_EXISTING, DELETE_ON_CLOSE), `newDirectoryStream` with glob filters, `walk`, `find`,
  `createDirectory`, `delete` (directories must be empty), `copy` and `move` within the card
  and between the card and the default file system (REPLACE_EXISTING, COPY_ATTRIBUTES).
  `ATOMIC_MOVE` is not supported.
* **Attributes.**
  * `basic`: times and size, with FAT resolution (2 s).
  * `dos`: read-only, hidden, system and archive.
  * `mega65` (`Mega65FileAttributes`): `shortName`, `firstCluster`, `startSector`,
    `contiguous`.
* **File store.** Label, total, free and usable space, and the largest contiguous free space.
* **Glob and regex** path matchers.
* **MEGA65 extras on `Mega65FileSystem`.**
  * `mountD81(path)` mounts an image as drive 0. The image must be in the root directory
    (a hypervisor limitation) and contiguous. Its name must be printable ASCII and at most
    63 characters.
  * `peek(address, count)` reads memory (28-bit address).
  * `target()` returns the MEGA65's address.

Not supported: symbolic links, watch services, file locks, user principals, and FAT12/16 or
exFAT.

### Mount notes

Hyppo keeps a mount across the soft reset that `quit` performs. Only a hardware reset (the
reset button or power-on) detaches the drive. At that point Hyppo mounts `MEGA65.D81` if the
root directory has one. **Don't delete a mounted image** before pressing reset: drive 0 would
point at freed clusters. The current mount can be read with
`peek(0xFFD368B, 5)`: the flags (`$D68B`) and the image's start sector (`$D68C`–`$D68F`).

## Errors

* `Mega65TimeoutException` (an `IOException`): the MEGA65 didn't answer within `timeout`,
  despite retransmissions. Discovery failure is an `IOException` with a hint about remote
  mode.
* Interrupting a thread that waits for the MEGA65 throws `InterruptedIOException` at once,
  and the interrupt flag stays set.
* **After a communication failure the file system can't be used any more.** The helper's
  state and the cached FAT may no longer match the card, so every later operation throws an
  `IOException` whose cause is the original error. Close it (a quit is still attempted, with a
  short deadline) and open a new one. The on-card write order is data, then FAT, then
  directory entry, then freeing old clusters. So an interrupted operation leaves at worst lost
  clusters, or a moved file listed twice, and never a directory entry pointing to unwritten
  data. `fsck.fat` can clean up lost clusters.
* Standard NIO exceptions are used where they apply: `NoSuchFileException`,
  `FileAlreadyExistsException`, `DirectoryNotEmptyException`,
  `ReadOnlyFileSystemException`, `ClosedFileSystemException`, and so on.

## Logging

The library logs through `System.Logger` (JUL by default) and prints nothing at the default
level. Use `DEBUG` for sessions, bootstrap and write layouts, and `TRACE` for individual
retransmissions. With JUL:

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level = FINE
io.github.paw.m65fs.level = FINE
```

`RemoteSdSession.retransmissions()` reports the number of retransmissions.

## Lower layers

The packages below `nio` are public, so they can be used on their own:

| Package | Contents |
|---|---|
| `transport` | `UdpLink`, `SlidingWindow`, `Mega65Locator` (discovery), `Target` |
| `etherload` | Hyperrupt trigger, ethlets, helper bootstrap (`Etherload`) |
| `remotesd` | `RemoteSdSession`: sectors, peek, mount, quit; `RemoteSdBlockDevice` |
| `block` | `BlockDevice`, image files, caching, fail-fast wrapper |
| `fat32` | `Fat32Volume`: the complete FAT32 implementation on any `BlockDevice` |

## Building

```sh
./mvnw verify            # build + offline tests (simulated MEGA65, image files)
```

Test images are made with `mkfs.fat` (dosfstools). Tests compare results with `mtools` and
check every image with `fsck.fat -n`. If those tools are missing, the tests that need them
are skipped.

### Hardware tests

These tests are tagged `hardware` and only run with the `hardware` profile. Each test that
changes something needs its own opt-in flag:

```sh
./mvnw test -Phardware                                     # discover, read-only checks
./mvnw test -Phardware -Dm65.bootstrap=true                # + upload helper, hello/quit
./mvnw test -Phardware -Dm65.write=true -Dm65.testdir=M65FSTEST   # + writes inside /M65FSTEST (create it first)
./mvnw test -Phardware -Dm65.mount=true                    # + writes /M65FS.D81, mounts it, deletes it
```

`-Dm65.target=fe80::…%if` skips discovery. The write tests stay inside the given top-level
directory, which must already exist. They check that the FAT outside it is byte-identical
afterwards.

### Blobs

`src/main/resources/io/github/paw/m65fs/blobs/` holds the three ethlets and
`remotesd_eth.prg`. They come from
[mega65-tools](https://github.com/MEGA65/mega65-tools) (GPL-3); `PROVENANCE.txt` lists the
commit and checksums. Rebuild them with `tools/build-blobs.sh`, which needs a mega65-tools
checkout, Ophis and git. It builds its own cc65, because some distribution builds crash on
the helper.

## License

GPL-3.0, see `LICENSE`. That matches mega65-tools, whose binaries are bundled.
