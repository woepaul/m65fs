---
name: m65fs
description: Work with a MEGA65 computer over Ethernet using the m65 tools (m65.ls, m65.cp, m65.boot, ...) and the m65fs library. Use them to list, copy (single files or whole directory trees, in either direction), move, delete and inspect files on the MEGA65's SD card; to change the working directory it shares with BASIC (m65.cd, m65.pwd); to push a D81 disk image and boot its first program in MEGA65 or C64 (GO64) mode; to load and run a PRG file; to reset the machine or mount a disk; to read memory; and to find and fix fragmented files. Also works on SD card image files. Use it whenever a request involves a MEGA65, its SD card, D81/PRG files for it, mega65_ftp or etherload.
---

# m65fs: MEGA65 over Ethernet

m65fs talks to the MEGA65 over Ethernet from pure Java, with its own small 45GS02 programs on
the MEGA65 side (nothing to install there). All FAT32 handling runs on this computer, and
**files are always written to the card contiguously** (some MEGA65 cores and the hypervisor
cannot use fragmented files).

## The tools

The m65 tools follow the Unix core utilities: `m65.ls`, `m65.tree`, `m65.cd`, `m65.pwd`,
`m65.cp`, `m65.mv`, `m65.rm`, `m65.mkdir`, `m65.cat`, `m65.stat`, `m65.df`, plus `m65.boot`,
`m65.run`, `m65.reset`, `m65.mount`, `m65.peek`, `m65.discover` and `m65.defrag`. Each takes `--help`; `m65 help`
lists all of them.

**Finding them:** use `m65.ls` etc. if they are on the PATH (`command -v m65.ls`). Otherwise
use the copies in the project's `bin/` directory, `PROJECT/bin/m65.ls` etc., where `PROJECT`
is the directory three levels above this file. They run the native binary when it is built
(`tools/build-native.sh`, GraalVM) and otherwise the jar on Java 25+, building it on first
use. `tools/install.sh` installs native tools into `~/.local/bin`.

**Card paths:** `m65.cp` copies between this computer and the card, so card paths are written
**`m65:/PATH`**, like scp's `host:path`. Unprefixed paths are local. All other tools only work
on the card and take plain `/PATH`, or `PATH` relative to the working directory (the prefix is
accepted too). Paths compare
case-insensitively like FAT (`/GAMES/x.d81` = `/games/X.D81`); long names are supported.

**Working directory:** relative card paths (`m65:GAMES`, `GAMES`, `m65:` alone) start from the
working directory, which `m65.cd DIR` changes and `m65.pwd` prints. The MEGA65 itself keeps it,
so it lasts from one tool to the next and is the same one as `CHDIR "DIR",U12` in BASIC: what
the user chose in BASIC is where relative paths start. This needs a hypervisor with
`trap_dos_getcwd`; with an older one, and for `M65_IMAGE`, the working directory is always `/`
and `m65.cd` fails. **Use absolute paths in scripts** unless the user works with a working
directory on purpose; run `m65.pwd` first when a relative path matters.

**Environment:**

| Variable | Meaning |
|---|---|
| `M65_TARGET=fe80::…%eth0` | Address of the MEGA65 (default: remembered in `~/.cache/m65fs/target` from the last discovery, which prefers wired interfaces) |
| `M65_IMAGE=card.img` | Work on an SD card image file instead of a MEGA65 (file tools only) |
| `M65_RESET=1` | Reset the MEGA65 after each file tool. By default it keeps showing the transfer screen until `m65.reset`, `m65.boot` or `m65.run` |

Exit status: 0 = ok, 1 = error, with the message on stderr in coreutils style
(`m65.cp: cannot stat 'm65:/x': No such file or directory`). For `m65.defrag`: 0 = clean,
1 = fragmented files remain, 2 = error.

## Before the first command

The MEGA65 must be in **remote mode**: DIP switch 2 on, or SHIFT+£ after power-up (the power
LED then blinks green and yellow). Check with `m65.discover`. If nothing is found, ask the user
to enable remote mode. Don't retry in a loop: SHIFT+£ is lost on every power cycle or reset
button press. The card must contain `ETHLOAD.M65` in its root (standard MEGA65 SD cards do),
and the computer needs IPv6 on the same network segment.

## Files

| Tool | Does | Changes the card |
|---|---|---|
| `m65.ls [-lRd] [PATH…]` | Names (default: the working directory); `-l` adds type, size, time and `[fragmented]`; `-R` recursive with `DIR:` headers | no |
| `m65.tree [-d] [-L LEVEL] [DIR]` | Tree view like tree(1): sizes and dates of files, total size and file count of each directory, `[fragmented]` marks, and a summary line (with free space for `/`). `-d` directories only, `-L` depth. A whole card takes about 5 s | no |
| `m65.cd [DIR]` | Change the working directory (`/` without DIR); the same as `CHDIR "DIR",U12` in BASIC | no |
| `m65.pwd` | Print the working directory | no |
| `m65.cat FILE…` | Print files | no |
| `m65.stat PATH…` | Size, times, 8.3 name, attributes, contiguous, start sector | no |
| `m65.df [--contiguous]` | Size, used and free; `--contiguous` adds the largest free run (reads the whole FAT, ~30 s) | no |
| `m65.cp [-rRnfpv] SOURCE… DEST` | Copy with **GNU cp semantics**, computer→card, card→computer or card→card. `-r` is needed for directories (whole trees); several sources need a directory DEST; **existing files are overwritten** unless `-n`; `-p` keeps modification times | yes, if DEST is `m65:` |
| `m65.mv [-nfv] SOURCE… DEST` | Move or rename **on the card**; overwrites unless `-n` | yes |
| `m65.rm [-rRfv] PATH…` | Delete; `-r` directories with contents, `-f` ignore missing; refuses `/` | yes |
| `m65.mkdir [-pv] DIR…` | Create directories | yes |
| `m65.defrag [--fix] [DIR]` | List fragmented files; `--fix` defragments them | only with `--fix` |

Files written to the card are always contiguous. If an overwrite doesn't fit contiguously,
`m65.cp` fails and the old file stays as it was.

## Machine

| Tool | Does |
|---|---|
| `m65.discover` | Print the MEGA65's address |
| `m65.boot [-n] [--mode c64\|mega65] IMAGE.d81` | A **local** `.d81` is first copied to the card root (overwriting a same-named file; `-n` keeps an existing one); `m65:/IMAGE.D81` boots an image already on the card. Mounts it, resets and runs its first program. The mode follows the program's load address ($2001 = MEGA65, otherwise C64 mode as with `GO64`) unless `--mode` is given |
| `m65.run [--mount IMAGE.D81] [--pal\|--ntsc] [--no-run] [--jump HEX] FILE.prg` | Load a local PRG straight into memory (nothing is written to the card) and start it: $0801 programs in C64 mode, $2001 programs in MEGA65 mode. Machine code at other addresses needs `--jump`. `--mount` attaches a D81 from the card root at the same time |
| `m65.reset [--mode mega65\|c64] [--pal\|--ntsc]` | Reset. A MEGA65-mode reset (the default) also **detaches drive 0** and leaves the transfer screen |
| `m65.mount IMAGE.D81` | Attach a D81 from the card root as drive 0 |
| `m65.peek ADDRESS_HEX [COUNT]` | Hex dump of memory (28-bit address, e.g. `m65.peek ffd3629 1` = model ID) |

## How the tools affect the MEGA65

- **Every tool takes over the machine.** It starts the transfer helper, which replaces whatever
  was running. Afterwards the MEGA65 shows the transfer screen (or resets, with `M65_RESET=1`).
  `m65.boot`, `m65.run` and `m65.reset` leave it running the new program or BASIC. If the user
  might be in the middle of something on the MEGA65, ask first. When done with file work, run
  `m65.reset` (or boot or run something) so the user gets the machine back.
- Because of this, `m65.peek` shows memory *after* the helper started (screen and I/O have
  changed). It can't inspect a program that was running before.
- All tools keep the MEGA65's working directory, including `m65.boot`, `m65.run` and
  `m65.reset` (BASIC starts in it).
- Disk images used with `m65.boot` or `m65.mount` must be in the **root directory**,
  contiguous, with a printable ASCII name of at most 63 characters. `m65.boot` with a local
  file takes care of that.
- A mount survives the end of a tool. Don't delete an image that may be mounted: `m65.reset`
  first (detaches drive 0), then `m65.rm`.
- Each call costs about 0.1 s for the session setup (the first one, or the first after the
  network changed, up to about 1 s more to find the MEGA65). Copy directories with one
  `m65.cp -r` instead of many single-file calls anyway. Writing runs at about 450 KiB/s,
  reading at about 950 KiB/s.

## Safety rules for agents

1. **`m65.cp` and `m65.mv` overwrite like cp/mv.** When the target might exist and the user
   didn't ask to replace it, check first (`m65.ls`/`m65.stat`) or use `-n`. Ask before
   overwriting, `m65.rm`, or `m65.defrag --fix`. `m65.ls`, `m65.cat`, `m65.stat`, `m65.df`,
   `m65.defrag` (report) and copying *from* the card are read-only for the card.
2. **Stay within what was asked.** Write only the files and directories the request names. For
   experiments or tests, use a dedicated top-level test directory agreed with the user.
3. **Report fragmentation in two steps:** `m65.defrag` first, show the result, and run
   `m65.defrag --fix` only after the user agrees.
4. If a tool fails with a timeout in the middle of a write, the card is consistent (data is
   written before the FAT and the directory entry), but there may be lost clusters. Tell the
   user and suggest checking the card (`fsck.fat` on a card reader). Don't loop retries.

## Common requests

| Request | Commands |
|---|---|
| "What's on my MEGA65?" | `m65.ls -l /`, or `m65.tree -L 2` for an overview |
| "Give me a tree view of the card (in a file)" | `m65.tree > sdcard-tree.txt` |
| "Copy my game folder to the MEGA65" | `m65.mkdir -p /GAMES && m65.cp -r ./mygame m65:/GAMES/` |
| "Back up the card / a folder" | `m65.cp -rp m65:/ ./backup` or `m65.cp -rp m65:/GAMES ./backup` |
| "Push this disk and run it" | `m65.boot game.d81` (`--mode c64` to force GO64) |
| "Run this program" | `m65.run demo.prg` |
| "Run this program, it needs its data disk" | `m65.cp data.d81 m65:/` then `m65.run --mount m65:/DATA.D81 game.prg` |
| "Go into my games folder" / "where am I?" | `m65.cd /GAMES` (BASIC's `DIR U12` then lists it too) / `m65.pwd` |
| "Is anything fragmented?" | `m65.defrag`; after confirmation `m65.defrag --fix` |
| "Free space?" | `m65.df` |
| "Eject the disk / give me my machine back" | `m65.reset` |
| Work on an image file from a card reader | `M65_IMAGE=sdcard.img m65.ls /` etc. (the image must not be mounted by the OS at the same time) |

## Troubleshooting

| Message | Meaning / what to do |
|---|---|
| `no MEGA65 found` | Remote mode off, or machine off or on another network. Ask the user; then retry `m65.discover` |
| `no response from MEGA65 … ETHLOAD.M65` | The hypervisor didn't answer (retried for ~15 s). Ask the user to check remote mode and that `ETHLOAD.M65` is in the card root, or to press reset |
| `… File exists` | `m65.mkdir` target exists (`-p` ignores that) |
| `no contiguous free space` | No free run large enough. Free up space, or check with `m65.defrag`. The old file is unchanged |
| `only images in the root directory can be mounted` | Copy the image to `m65:/` first; with a working directory set, give the image as `m65:/NAME.D81` |
| `the MEGA65's hypervisor does not keep a working directory` | `m65.cd` needs the patched hypervisor (see the project README); use absolute paths instead |
| `an image file has no working directory` | With `M65_IMAGE`, use absolute paths |
| `program loads at $XXXX: only $0801 … and $2001 …` | A machine-code PRG: ask for the start address and use `m65.run --jump HEX` |
| `session … failed earlier` / timeouts | Network trouble. The tool can be run again; for writes, see safety rule 4 |
| `MEGA65 did not acknowledge quit` (warning) | The machine may still show the transfer helper; `m65.reset` or the reset button |

## Using the Java API directly

The tools cover most requests. For anything else, write a small Java program against the library
and run it as a source file (Java 25):

```sh
java -cp PROJECT/target/m65fs-*.jar MyTask.java     # bin/m65 help builds the jar if missing
```

```java
import io.github.paw.m65fs.nio.*;
import io.github.paw.m65fs.launch.*;
import java.net.URI;
import java.nio.file.*;
import java.util.Map;

public class MyTask {
  public static void main(String[] args) throws Exception {
    // "mega65://auto/" discovers the machine; "mega65://[fe80::…%eth0]/" for a fixed address
    try (var fs = (Mega65FileSystem) FileSystems.newFileSystem(URI.create("mega65://auto/"), Map.of())) {
      try (var s = Files.list(fs.getPath("/"))) { s.forEach(System.out::println); }
      Files.copy(Path.of("game.d81"), fs.getPath("/GAME.D81"), StandardCopyOption.REPLACE_EXISTING);
      fs.closeAndBoot(fs.getPath("/GAME.D81"));   // ends the session and boots the disk
    }
  }
}
```

Useful entry points:
- `java.nio.file.Files` works on paths from the file system.
- `Mega65FileSystem`: `mountD81`, `peek`, `commit()` (every operation is already on the card
  when it returns; `commit` also flushes still-open write channels), `workingDirectory()` /
  `setWorkingDirectory(path)` (shared with BASIC when the hypervisor supports it), and
  `closeAndBoot`, `closeAndRun`, `closeAndReset`.
- `Mega65Launcher`: `runPrg`, `boot`, `reset` without a file system.
- `D81Image` reads a D81 directory.
- `Defragmenter`: `scan`, `defragment`.
- Attribute view `"mega65"` (`Mega65FileAttributes`): `shortName`, `startSector`, `contiguous`.
- `FileSystems.newFileSystem(Path.of("card.img"), Map.of())` opens an SD card image.

The project README has the full documentation.
