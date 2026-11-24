# m65fs

[![CI](https://github.com/woepaul/m65fs/actions/workflows/ci.yml/badge.svg)](https://github.com/woepaul/m65fs/actions/workflows/ci.yml)

Tools and a Java library for working with a [MEGA65](https://mega65.org) over Ethernet:
manage the files on its SD card and start software on it from your computer. You don't need
a card reader, and nothing has to be installed on the MEGA65. m65fs uses the hypervisor's
Ethernet remote control (`ETHLOAD.M65`) with its own small 45GS02 programs; the protocols are
those of mega65-tools' `mega65_ftp -e` and `etherload`, reduced to what m65fs needs.

- **Command line tools in the style of the Unix core utilities:** `m65.ls`, `m65.tree`,
  `m65.cd`, `m65.pwd`, `m65.cp`, `m65.mv`, `m65.rm`, `m65.mkdir`, `m65.cat`, `m65.stat`,
  `m65.df`. They copy single files or whole directory trees in either direction, and they're
  built as one native binary (GraalVM). A command such as `m65.ls /` takes about 0.1 s,
  writing runs at about 450 KiB/s and reading at about 950 KiB/s (see `CHANGELOG.md`).
- **A working directory shared with BASIC:** `m65.cd /GAMES` is the same as
  `CHDIR "/GAMES",U12`, and relative card paths start where BASIC left off (needs a patched
  hypervisor, see [What you need](#what-you-need)).
- **Push and run software with one command:** `m65.boot` boots a D81 disk image and
  `m65.run` starts a PRG, in MEGA65 mode or in C64 mode (as entered with `GO64`).
  `m65.reset` gives you the machine back.
- **No fragmented files:** some cores and the hypervisor can't use fragmented files.
  m65fs always writes files contiguously, and `m65.defrag` finds and fixes fragmented files
  left by other tools.
- **A Java NIO file system** (`mega65://`): any Java code that uses `java.nio.file.Files`
  works on the card. The lower layers (FAT32, block devices, the Ethernet protocols) are
  usable on their own.
- **An agent skill** (`.claude/skills/m65fs`) that lets AI agents serve MEGA65 requests with
  the tools.

```sh
m65.tree -L 1                        # what's on the card
m65.cp -r ./mygame m65:/GAMES/       # copy a directory tree to the card
m65.boot game.d81                    # copy the disk to the card, mount it, run its first program
m65.run demo.prg                     # load a program into memory and start it
m65.defrag                           # report fragmented files (--fix to defragment)
```

The same from Java:

```java
try (var fs = (Mega65FileSystem) FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())) {
  try (Stream<Path> s = Files.list(fs.getPath("/"))) {
    s.forEach(System.out::println);
  }
  Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"), StandardCopyOption.REPLACE_EXISTING);
  fs.closeAndBoot(fs.getPath("/GAME.D81"));   // ends the session, mounts the disk, runs it
}
```

The library is pure Java 25 with no dependencies; the native binary is optional. It also
works on SD card image files. Everything has been tested on real hardware.

## Installation

**From a [release](https://github.com/woepaul/m65fs/releases):** native binaries, no Java
needed:

| Platform | Archive | Contents |
|---|---|---|
| Linux amd64 / aarch64 | `m65-VERSION-linux-amd64.tar.gz`, `…-linux-aarch64.tar.gz` | `bin/m65` and the `bin/m65.*` links |
| macOS (Apple silicon) | `m65-VERSION-macos-aarch64.tar.gz` | `bin/m65` and the `bin/m65.*` links |
| Windows amd64 | `m65-VERSION-windows-amd64.zip` | `bin\m65.exe` and `bin\m65.ls.cmd`, `bin\m65.cp.cmd`, … |

Unpack it and put its `bin` directory on the `PATH`. On macOS the binary isn't signed; if it is
blocked, `xattr -d com.apple.quarantine m65` allows it. `m65 --version` shows the version.

**The jar** (`m65fs-VERSION.jar`, on every platform with Java 25): `java -jar m65fs-VERSION.jar
ls /` runs `m65.ls /`, and so on. It is also the library.

**From source:** `tools/install.sh` builds the native binary and installs it with its links into
`~/.local/bin` (`--jvm`: without GraalVM, running the jar).

## What you need

| | Required | Optional |
|---|---|---|
| **Running the tools** | Java 25, or nothing with the native binary | |
| **On the MEGA65** | a standard SD card (`ETHLOAD.M65` in the root), Ethernet, remote mode (DIP switch 2 or SHIFT+£) | the `hyppo-preserve-cwd` hypervisor patch for the shared working directory |
| **Building** | the Maven wrapper (`./mvnw`, fetches Maven and the Java build plugins) | GraalVM for the native binary (downloaded by `tools/build-native.sh`); ACME 0.97+ to rebuild the MEGA65 code (the built blobs are in the repository); `dosfstools` and `mtools` for the image tests |

Nothing has to be installed on the MEGA65: everything m65fs runs there is its own small 45GS02
code, bundled in the jar and in the binary.

## How it works

For files, m65fs talks to the MEGA65 the way `mega65_ftp -e` does:

1. **Bootstrap.** A trigger packet puts the MEGA65 hypervisor into Ethernet remote control.
   Small 45GS02 programs ("ethlets") then upload the transfer helper `helper.prg` (2.7 KB)
   and start it.
2. **Session.** The helper serves raw SD card sectors over UDP (IPv6 link-local, port 4510).
   The library sends requests through a sliding window with retransmission.
3. **FAT32 on the PC.** All file system logic runs in Java: MBR, FAT32, VFAT long names and
   cluster allocation. The MEGA65 is only a remote block device.

Starting software works the same way: after the trigger, ethlets write the program (or a
small boot loader) into memory, mount a disk image and reset into C64 or MEGA65 mode.

`docs/mega65-code.md` describes the code that runs on the MEGA65 and its protocols. `PLAN.md`
is the original design plan, written against mega65-tools' programs.

## Requirements on the MEGA65

* Ethernet cable to the same network segment as the PC. IPv6 link-local must work; that is
  the default on Linux, macOS and Windows.
* Remote mode on, which makes the MEGA65 send its "mega65" discovery beacon: DIP switch 2 on,
  or press SHIFT+£.
* Nothing to install on the card. The MEGA65 code is bundled (see [Blobs](#blobs)).
* Optional, for a working directory shared with BASIC (`m65.cd`): a hypervisor with
  `trap_dos_getcwd` ($0A) and `trap_dos_restorecwd` ($4C). The stock hypervisor changes to
  the root directory on every Ethernet trigger and forgets the directory; the patch on the
  `hyppo-preserve-cwd` branch of mega65-core saves it, and the helper returns to it when it
  starts. To try it without flashing a core, put the patched `HICKUP.M65` into the card root
  and power-cycle (delete it to go back).

## Opening a file system

| URI / call | Meaning |
|---|---|
| `mega65://auto/` (or `mega65:///`) | Discover the MEGA65 on all interfaces (waits for its beacon, sent once a second; wired interfaces are preferred). |
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
  * `workingDirectory()` / `setWorkingDirectory(path)`: the directory relative paths start
    from. With a hypervisor that has `trap_dos_getcwd`, this is the MEGA65's own working
    directory (`isWorkingDirectoryShared()`): a new file system starts where BASIC's `CHDIR` or
    the last session left it, and changing it is like `CHDIR "DIR",U12`. Otherwise it starts
    at `/` and only lives as long as the file system.
  * `peek(address, count)` reads memory (28-bit address).
  * `target()` returns the MEGA65's address.

Not supported: symbolic links, watch services, file locks, user principals, and FAT12/16 or
exFAT.

### Mount notes

Hyppo keeps a mount across the soft reset that `quit` performs. A hardware reset (the reset
button or power-on) detaches the drive, and so does `closeAndReset(MachineMode.MEGA65)`. At that point Hyppo mounts `MEGA65.D81` if the
root directory has one. **Don't delete a mounted image** before pressing reset: drive 0 would
point at freed clusters (or detach it first with `closeAndReset`). The current mount can be read with
`peek(0xFFD368B, 5)`: the flags (`$D68B`) and the image's start sector (`$D68C`–`$D68F`).

## Command line tools

The `m65.*` tools work like the Unix core utilities. They're one native binary, built with
GraalVM `native-image`, so they start in milliseconds:

```sh
tools/install.sh                     # build the native binary, install m65 + m65.* into ~/.local/bin

m65.ls -l /GAMES                     # list (-l: size, time, [fragmented]; -R recursive)
m65.tree > card.txt                  # tree view with sizes, dates and directory totals
m65.cp -r ./mygame m65:/GAMES/       # copy like cp: card paths are m65:/PATH
m65.cd /GAMES                        # same as CHDIR "/GAMES",U12; kept by the MEGA65
m65.cp demo.prg m65:                 # m65:NAME and plain NAME are relative to it
m65.cp -rp m65:/GAMES ./backup       # whole trees out, keeping times
m65.mv /old.d81 /new.d81             # on the card
m65.rm -r /GAMES/mygame
m65.boot game.d81                    # copy to the card root, mount, reset, run the first program
m65.run demo.prg                     # load into memory and start ($0801 C64 / $2001 MEGA65)
m65.reset                            # MEGA65 mode, drive 0 detached
m65.defrag [--fix]                   # report / fix fragmented files
```

The full set is `m65.ls`, `m65.tree`, `m65.cd`, `m65.pwd`, `m65.cp`, `m65.mv`, `m65.rm`, `m65.mkdir`, `m65.cat`, `m65.stat`,
`m65.df`, `m65.defrag`, `m65.boot`, `m65.run`, `m65.reset`, `m65.mount`, `m65.peek` and
`m65.discover`. Each takes `--help`, and `m65 help` lists them all.

- **`m65.cp`** has GNU cp semantics: `-r` for directories, several sources into a directory,
  overwriting by default (`-n` keeps existing files) and `-p` to keep times. A failed
  overwrite on the card leaves the old file intact (no contiguous space, for example).
- **`m65.mv`** works on the card only.
- **`m65.cd`** / **`m65.pwd`**: the MEGA65 keeps the working directory, so it holds from one
  tool to the next and BASIC sees it too (`DIR U12`), and a `CHDIR` in BASIC is where
  relative card paths start. This needs a hypervisor with `trap_dos_getcwd` and
  `trap_dos_restorecwd` (see below); otherwise relative paths start at `/` and `m65.cd`
  reports an error. `m65.boot`, `m65.run` and `m65.reset` keep it too: BASIC starts in it.
- **Finding the MEGA65:** the tools remember its address in `~/.cache/m65fs/target` and check
  it with a quick trigger (a few ms); only if that fails do they wait for its beacon (sent once
  a second) and prefer a wired interface over WLAN. `M65_TARGET` skips all of that.
- **Environment variables:** `M65_TARGET=fe80::…%if` (default: remembered or discovered),
  `M65_IMAGE=card.img` (an SD card image instead of a MEGA65) and `M65_RESET=1` (reset after
  each file tool; by default the MEGA65 keeps showing the transfer screen until `m65.reset`,
  `m65.boot` or `m65.run`).
- **Exit status:** 0 ok, 1 error (`m65.defrag`: 1 = fragmented files remain, 2 = error).

**Without installing:** `bin/m65.ls` and so on run the native binary if it's built and up to
date, and otherwise the jar on the JVM (Java 25+). `java -jar target/m65fs-*.jar ls -l /`
works too.

### Building the native binary

`tools/build-native.sh` builds `target/native/m65` and the `m65.*` links. It uses
`$GRAALVM_HOME`, or downloads GraalVM Community for JDK 25 once into `~/.cache/m65fs`
(checksum-verified). The build takes about 15 s. The resources the binary needs (the MEGA65
blobs) are listed in `META-INF/native-image/io.github.paw/m65fs/reachability-metadata.json`,
and the build fails if one isn't embedded. `tools/install.sh --jvm` installs the tools
without GraalVM.

## Agent skill

`.claude/skills/m65fs/` is an [Agent Skill](https://agentskills.io): a `SKILL.md` that tells
an AI agent how to serve MEGA65 requests with the `m65.*` tools. It covers when to use them,
every tool, common requests ("push this disk and run it", "back up the card"), safety rules
(cp-style overwrites, what changes the card or takes over the machine, when to ask first) and
troubleshooting. Claude Code picks it up automatically in this project. Other agents can load
the folder as a skill, with the tools installed on the PATH.

## Finding and fixing fragmented files

Files written by other tools (or copied with a card reader) can be fragmented. The `Defrag`
tool first lists them, and changes the card only when asked:

```sh
m65.defrag                           # report only (read-only)
m65.defrag --fix                     # defragment the listed files
M65_IMAGE=card.img m65.defrag --fix  # an SD card image
```

```
scanning mega65://auto/ ...
276 files in 23 directories
fragmented directory (fine, not changed): /amiga/adf (2 fragments)
1 fragmented file(s):
    3 fragments  293.0 KiB  /M65FSTEST/Frag Test.bin
run again with --fix to defragment them
```

Defragmenting a file copies its clusters into one free contiguous run, points the directory
entry at the copy and then frees the old clusters. Content, size, timestamps and attributes
stay the same, and the same crash-safe order applies as for writes. Each file needs a free run
of its own size; files without one are skipped and reported. Fragmented directories are listed
but left alone, since the hypervisor follows directory chains. A scan of a full card takes
about 5 s. Exit status: 0 = no fragmented files (left), 1 = some remain, 2 = error.

The same two steps are available as an API:

```java
Defragmenter.Report report = Defragmenter.scan(fs);                  // read-only
List<Defragmenter.Result> results = Defragmenter.defragment(fs, report.paths(), System.out::println);
```

`Fat32Volume.defragment(entry)` does one file at the lower level.

## Booting disks and running programs

The `launch` package uses the hypervisor's Ethernet remote control and m65fs' ethlets to start
software. Every launch takes over the machine.

```java
try (var fs = (Mega65FileSystem) FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())) {
  Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"), StandardCopyOption.REPLACE_EXISTING);
  fs.closeAndBoot(fs.getPath("/GAME.D81"));       // mount, reset, load and run the first program
}
```

* **`closeAndBoot(image)`** reads the D81's directory from the card and picks its first
  program. The mode follows the program's load address: `$2001` boots in MEGA65 mode, anything
  else in C64 mode (as with `GO64`). `closeAndBoot(image, MachineMode.C64)` forces a mode. The
  method returns what it started, e.g. `boot GAME.D81: "GAME" ($0801) in C64 mode`.
  * **C64 mode:** the reset mounts the image. A 113-byte loader
    (`src/main/asm/c64boot.a`) then does `LOAD"name",8,1` and types `RUN`, exactly as a
    user would. Programs that start themselves during the load (autostart tricks) work too.
  * **MEGA65 mode:** the reset happens without a disk, and a one-line BASIC 65 program does
    `MOUNT"image":RUN"name"`.
* **`closeAndRun(prg, options)`** or **`Mega65Launcher.runPrg(target, prg, options)`** loads a
  program file straight into memory; nothing is written to the card. A `$0801` program runs in
  C64 mode, a `$2001` program in MEGA65 mode. Options cover mounting a D81 at the same time,
  PAL/NTSC, not running the program, or `withJump(address)` for machine code at other
  addresses.
* **`closeAndReset(mode)`** or **`Mega65Launcher.reset(...)`** resets the machine. A MEGA65-mode
  reset also **detaches drive 0**, which the soft reset at the end of a normal session doesn't.
* **`D81Image`** reads a D81 directory (names, types, load addresses), from a local file or
  from an image on the card.

The `closeAnd…` methods end the session without its quit (and its soft reset). Right after a
reset the hypervisor can ignore the trigger for several seconds, so the trigger is retried
for up to about 15 s.

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
| `etherload` | Hyperrupt trigger, ethlets, helper bootstrap, resets and jumps (`Etherload`) |
| `launch` | `Mega65Launcher` (boot disks, run PRGs, reset), `D81Image`, `MachineMode` |
| `tools` | `M65fs`: the `m65.*` command line tools (the jar's main class) |
| `remotesd` | `RemoteSdSession`: sectors, peek, mount, working directory, quit; `RemoteSdBlockDevice` |
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
./mvnw test -Phardware -Dm65.write=true -Dm65.testdir=M65FSTEST   # + writes and defrag inside /M65FSTEST (create it first)
./mvnw test -Phardware -Dm65.mount=true                    # + writes /M65FS.D81, mounts it, deletes it
./mvnw test -Phardware -Dm65.boot=true                     # + boots C64 and MEGA65 disks, runs PRGs
```

`-Dm65.target=fe80::…%if` skips discovery. The write tests stay inside the given top-level
directory, which must already exist. They check that the FAT outside it is byte-identical
afterwards.

### Releasing

GitHub Actions build and test every push (`.github/workflows/ci.yml`): the offline tests, the
bundled MEGA65 code against its sources, and the native binary on all four platforms. A
release is a tag:

1. In `CHANGELOG.md`, rename `## Unreleased` to `## X.Y.Z (date)` and commit.
2. `git tag vX.Y.Z && git push origin vX.Y.Z`.

`.github/workflows/release.yml` then builds the jar and the four native binaries with that
version and publishes them as a GitHub release, with the changelog section as release notes.
It refuses to run if the changelog has no section for the version.

### Blobs

`src/main/resources/io/github/paw/m65fs/blobs/` holds:

* the three ethlets `ethlet_echo.bin`, `ethlet_load.bin` and `ethlet_start.bin`
  (`src/main/asm/ethlets/`);
* the transfer helper `helper.prg` (`src/main/asm/helper/`);
* the C64 disk boot loader `c64boot.prg` (`src/main/asm/c64boot.a`).

All of it is m65fs' own 45GS02 assembly, described in `docs/mega65-code.md`. Rebuild it with
`tools/build-blobs.sh`, which needs only ACME 0.97 or newer.

## License

GPL-3.0, see `LICENSE`.
