# Changelog

Changes to m65fs, newest first, since 2026-11-24. A release turns "Unreleased" into
"VERSION (date)"; the release on GitHub takes its notes from that section
(`tools/release-notes.sh`).

## Unreleased

- Progress shows the estimated time left: `m65.cp -v`, `m65.boot`, `m65.defrag --fix` and
  copies from a pipe show `[ 12%] (8 min)` after the first 5 seconds of a transfer (also at
  0 %, which a large file shows for a while), from the rate over the last 10 seconds (minutes,
  rounded up; `(<1 min)` at the end; `(1 h 05 min)` for long ones). A stalled transfer shows
  no estimate, an interrupted line stays as it was with `interrupted` added, and a finished
  line looks as before.

## 0.2.2 (2026-11-26)

- Fix: after leaving the transfer helper (RUN/STOP on the MEGA65, or `m65.reset` after a
  tool), BASIC 65 could start with `?FILE NOT FOUND ERROR` or `74,DRIVE NOT READY`. The
  helper was loaded at $0800, over BASIC's workspace, and BASIC reads some of it unchecked
  after a reset: the default drive at $1106 then came from whatever helper code byte was
  there (0.2.0 and 0.2.1 were lucky with theirs; any change of the helper could break it).
  The helper now lives at $4000 and leaves $0000-$2FFF alone (`HELPER_VERSION` 6).
- The helper's title row says what RUN/STOP does right now: `run/stop: quit to basic (idle)`,
  `run/stop: no action (busy)`, or after a fatal error `run/stop: reset (error)`. It always
  said `quit to basic (idle)`, also while busy.
- Windows: messages and file names with characters outside ASCII came out garbled in the
  console (`SHIFT+£` as `SHIFT+┬ú`): m65 writes UTF-8, the console shows its code page
  (usually 850 or 437). The `m65.*.cmd` wrappers now switch the console to UTF-8 for the
  command and back to its code page afterwards (calling `m65.exe` directly is not covered).
- The hypervisor change for the shared working directory (`m65.cd`, `m65.pwd`; mega65-core
  PR #985) is merged: it is in MEGA65 core development build 179 or later
  (https://builder.mega65.org/job/mega65-core/job/development/179/), so no patched
  `HICKUP.M65` is needed any more. Docs and `m65.cd`'s error message point there.

## 0.2.1 (2026-11-25)

- Fix: after Ctrl-C during a write to the card, the next tool hung for 3 seconds at its first
  read and failed with a timeout (the one after it worked again, with a new helper). The
  interrupted write left the SD card in an unfinished multi-sector write, which 0.2.0's reuse
  of the running helper carried over to the next session. The helper now resets the SD card
  first when a session takes over in the middle of a write batch (`HELPER_VERSION` 4). The
  sectors written so far stay; as before, nothing of the interrupted file is listed (an
  overwritten file keeps its old content, a new file is left empty).
- `m65.dsplit` says `No such file or directory` for a directory that does not exist (was:
  `Not a directory`).

## 0.2.0 (2026-11-25)

**The transfer helper's screen**

- 80 x 50 instead of a few lines in 40 columns. It shows who the helper is reserved for and
  whether it is busy or idle, statistics (requests, KiB read and written, each per second, and
  the time running), and a log of what the tools do: each command line without `m65.` (white,
  e.g. `cp -rv game m65:/GAMES`) and below it, indented, a line for every copied file (green,
  also without `-v`; red with `failed`), e.g. `  .../game/disk1.d81 -> /GAMES/game/disk1.d81`,
  and the files `m65.defrag --fix` moves. Too long lines keep the command name or indent and
  lose the start of their paths. The log fills from the top and scrolls when full, in the
  vertical blank so that the screen does not jolt. A copied file costs one round trip more
  for its line.
- The border shows the state: red while requests come in, green when idle, blinking red after
  a fatal error.
- RUN/STOP (or ESC) on the MEGA65 quits the helper into BASIC 65 when it is idle (green: no
  write batch open, no request for 5 seconds); pressed while busy, the status row says
  `(run/stop: not now)`. After a fatal helper error the same keys reset.

**Faster, and no more taking over a busy MEGA65**

- Tools reuse the running transfer helper: a tool ends its session with a release (new helper
  request `$15`), and the next tool says hello to the helper directly instead of triggering
  the MEGA65 and loading the helper again. `m65.ls` takes about 0.1 s instead of 0.5 s, the
  screen no longer jolts at every command (the trigger makes Hyppo switch to a 40-column C64
  screen for a moment), and the log and statistics go on. Only a helper of the same version
  is reused (its hello answer says it); otherwise, or if nothing answers within 150 ms, the
  tool triggers as before.
- A busy MEGA65 is no longer taken over. So far a tool always triggered the MEGA65 if the
  helper did not answer it, which ended another command's session (from a second terminal or
  another computer) in the middle of its work. Now the helper answers "busy", and the tool
  stops with exit status 1 and a message:
  - another m65 command of this computer is running: try again when it is done (after Ctrl-C
    the old session counts as ended once it has been silent for 5 seconds);
  - a command of another computer is running;
  - the helper is reserved for another computer: the computer that started it owns it (by its
    IPv6 address) until `m65.reset` there, `m65.boot`/`m65.run`, or RUN/STOP on the MEGA65.

  `m65.run` and `m65.reset` ask the helper before they trigger. `M65_FORCE=1` takes the
  MEGA65 over anyway.
- `m65.tree -L LEVEL` reads only the levels it shows. Before, it read the whole card (for the
  directory totals) and only cut the output, so `-L 1` took as long as a full tree (test card:
  4 instead of 196 requests). Directories cut off by `-L` show no totals, and the summary
  counts what was read, e.g. `6 directories, 23 files, 6.3 MiB (to level 1)`. Without `-L`
  the output is unchanged.
- Faster tree walks on the card: path lookups read the directories along the path in
  cache-sized pieces. Before, a directory larger than the sector cache's read-ahead was read
  from the card again for every lookup, so walking a large tree took minutes instead of
  seconds (11,000 files: 116 s, now 8 s).

**New: `m65.dsplit`**

- `m65.dsplit [-rv] [-m MAX] [--dry-run] DIR...` moves the files of a directory with more than
  MAX (250) files into subdirectories by name, e.g. `0-C`, `D-G`, `R`, and by the next letter
  where one letter has too many (`Sa-Sd`); as few groups as MAX allows, as evenly filled as
  the names allow. For cores whose file browser loads only so many entries (the C64 core warns
  above about 300 to 600). `-r` handles every directory below DIR, `--dry-run` only shows the
  plan. Works on the card (one session, only directory entries change) and on local
  directories.

**Also**

- The agent skill is part of the release: `m65fs-skill-0.2.0.zip`, to unpack into
  `~/.claude/skills/`.
- Library: `Mega65FileSystem.showOnMega65(line, kind, replace)` shows a line in the helper's
  log; `Mega65BusyException`, env `force`; `RemoteSdSession.attachRunning`, `ensureFree`,
  `helperAnswers`; helper requests `$14` text and `$15` release, hello answers with the
  helper version (`HELPER_VERSION`) or "busy".

## 0.1.3 (2026-11-25)

- Progress for large transfers. `m65.cp -v` prints `'src' -> 'dst'` before copying (not after),
  and on a terminal a progress field ` [ 66%]` follows after a second, updated in place and
  cleared before the newline, so finished lines look as before and logs stay clean.
  `m65.boot` shows its copy of the disk image the same way, and `m65.defrag --fix` prints each
  file first and appends the result (`  /GAMES/big.d81: defragmented (3 fragments -> 1)`;
  before: `  defragmented (3 fragments -> 1)  /GAMES/big.d81`).
  Progress counts what really moves to and from the card (a file written to the card goes
  over the network when it is closed). `M65_PROGRESS=1` forces it, `=0` turns it off.
- `m65.cp - DEST` copies standard input, e.g. a compressed image unpacked on the fly:
  `zcat hdd.img.gz | m65.cp -v - m65:/HDD.IMG` (also on Windows, where there is no
  `/dev/stdin`). The data is collected on the PC first (memory, then a temporary file), because
  the card gets the file in one contiguous run, which needs the size; progress shows the
  transfer to the card. DEST must name the file; `-` can be given once.
- Progress also shows when only standard output is a terminal (input from a pipe).
- Ctrl-C during an overwrite no longer leaves a broken file. A new version that fit into the
  old file's clusters was written over them, so an interrupted `m65.cp` (or `m65.boot` of a
  D81 of the same size) left a mix of old and new data under the old entry (measured: 688 of
  2048 4K blocks new). Content now always goes to free clusters first; the entry is switched
  and the old clusters freed only after it is complete, so an interruption keeps the old
  content. Overwriting therefore needs free space of the file's size, and the file's start
  cluster changes.
- An interrupted copy says so: the `-v` line keeps its progress and ends with `interrupted`
  (` [ 42%] interrupted`), without `-v` stderr gets `m65.cp: interrupted: 'a' -> 'b' not
  completed`; a copy that fails ends its line with `failed`. `m65.defrag --fix` marks the file
  it was moving and reports `interrupted after 7 of 12 file(s); the others are unchanged`.
- Tested on Linux only so far: Ctrl-C handling on Windows and macOS. On Windows, input from a
  pipe (`... | m65.cp -v - ...`) shows no progress field (the copy itself works).
- Library: `Mega65FileSystem.bytesRead()` / `bytesWritten()` (traffic to and from the card,
  below the cache); `Defragmenter.defragmentWithEvents` with a `Listener` (start, deferred,
  finished).

## 0.1.2 (2026-11-24)

- Fixed: the working directory was lost on every command in 0.1.0 and 0.1.1 (`m65.cd c64`,
  then `m65.ls` listed `/`). The quick check of the remembered address triggers the
  hypervisor, and the session then triggered it a second time; that second trigger saved the
  root as working directory. A session now uses ETHLOAD.M65 when it already runs, so the
  hypervisor is triggered once per command.
- Opening an SD card image: `Mega65FileSystem.openImage(path, env)`. The generic
  `FileSystems.newFileSystem(path, env)`, which asks every installed provider, now only picks
  m65fs with the `env` entry `"mega65": true` and only for files that look like an SD card
  image (MBR with a FAT32 partition, or a FAT32 volume). Before, m65fs claimed every file and
  failed on the ones it couldn't read, so other providers never got them. The CLI and
  `Defrag` use `openImage`.

## 0.1.1 (2026-11-24)

- Windows release: the `m65.COMMAND.cmd` wrappers in 0.1.0's zip had a carriage return in
  their names, and the zip used `\` as path separator. The zip is now made with 7-Zip, the
  command list is written with `\n` on every platform, and every release archive is checked
  (entry names, binary, wrappers or links) in CI and before a release.
- The release archives keep the binary and its links or wrappers in `bin/` (README, LICENSE
  and CHANGELOG at the top).
- CI and release retry fetching Maven (a failed download from Maven Central failed a run).

## 0.1.0 (2026-11-24)

### Working directory shared with the MEGA65

- New tools `m65.cd [DIR]` and `m65.pwd`. Relative card paths (`m65:NAME`, plain `NAME`,
  `m65:` alone) now start from the working directory instead of the root; `m65.ls` and
  `m65.tree` without arguments list it.
- The MEGA65 keeps the working directory, so it lasts from one tool to the next, `m65.cd DIR`
  is the same as `CHDIR "DIR",U12` in BASIC, and a `CHDIR` in BASIC is where relative paths
  start. `m65.boot`, `m65.run` and `m65.reset` keep it too: BASIC starts in it.
- Needs a hypervisor with `trap_dos_getcwd` ($0A) and `trap_dos_restorecwd` ($4C): branch
  `hyppo-preserve-cwd` of mega65-core (try it as `HICKUP.M65` in the card root). The stock
  hypervisor changes to the root directory on every Ethernet trigger and forgets the
  directory. Without the patch relative paths start at `/` and `m65.cd` reports an error; for
  `M65_IMAGE` the working directory is always `/`.
- Library: `Mega65FileSystem.workingDirectory()`, `setWorkingDirectory()`,
  `isWorkingDirectoryShared()`; `Fat32Volume.directoryPath(cluster)`;
  `RemoteSdSession.workingDirectory()` / `changeWorkingDirectory()`. A working directory that
  no longer exists (deleted since) falls back to `/`.

### m65fs' own MEGA65 code, no more mega65-tools

Everything m65fs runs on the MEGA65 is now its own 45GS02 assembly (`src/main/asm/`, ACME,
`tools/build-blobs.sh`, described in `docs/mega65-code.md`). Building it needs only ACME; no
mega65-tools checkout, cc65 or Ophis.

- **Ethlets:** three instead of mega65-tools' five. ECHO (ping), LOAD (one DMA job list copies
  the data and builds the reply) and START (mount, return to the working directory, reset into
  MEGA65 or C64 mode keeping and RUNning a loaded program, or jump). No ROM reload; C64 mode
  gets control back through a CBM80 signature in RAM instead of patching the ROM. RUN now also
  works with a disk mounted in MEGA65 mode.
- **Transfer helper:** `helper.prg`, 2.7 KB instead of `remotesd_eth.prg`'s 13 KB, same
  protocol on the wire. Started without a reset (START's jump mode), uses no ROM, copies by
  DMA, checksums with 32-bit additions.
  Like `remotesd_eth`, it answers the last sector of a write batch only after the whole batch
  is written and read back, so a finished write is on the card (the first version answered
  first and wrote afterwards).
- **`c64boot.prg`:** assembled with ACME (byte-identical).
- The never-used cartridge detection option is gone.

Measured on a MEGA65 over 100 Mbit Ethernet against `mega65_ftp -e` (version
20260719.12-develo-c2c8635, with mega65-tools' helper), same 4 MiB file, median of 3 runs
(directory: of 8); every copy compared byte for byte:

| | `mega65_ftp -e` | m65fs | |
|---|---|---|---|
| write 4 MiB | 10.62 s (386 KiB/s) | 9.05 s (453 KiB/s) | 15 % less time |
| read 4 MiB | 4.99 s (821 KiB/s) | 4.28 s (957 KiB/s) | 14 % less time |
| list `/` (incl. session start) | 0.83 s (2 of 8 runs failed) | 0.46 s | 45 % less time |

Session starts varied by up to a second in both tools: each waited for the MEGA65's beacon,
see "Faster command start" below. An 8 x 4 MiB write/read endurance run (2.5 minutes) passed
without errors.

### Faster command start

- Every command used to wait for the MEGA65's beacon (sent once a second): 0.2–1.7 s before
  anything happened. The tools now remember the address (`~/.cache/m65fs/target`) and check
  it with a quick trigger and ping instead; discovery only runs when that fails (another
  MEGA65, another interface) and refreshes the remembered address. `m65.pwd` now takes
  0.05 s, `m65.ls /` 0.08 s (against 0.85 s for `mega65_ftp -e -c dir`, median of 5).
- Discovery prefers a wired interface: a MEGA65 heard over WLAN is only taken if no wired
  interface hears it within one beacon period (reading 4 MiB took 4.15 s wired, 4.64 s over
  WLAN). A remembered WLAN address is not reused without checking for a wired one.

### Releases

- GitHub Actions: every push builds and tests (including the image tests with dosfstools and
  mtools, and a check that the bundled MEGA65 code matches its sources), and builds the
  native binary for Linux amd64 and aarch64, macOS aarch64 and Windows amd64. A tag `vX.Y.Z`
  publishes a release with the jar and those four binaries.
- `m65 --version`.
- The build scripts run on macOS (bash 3.2) and the native build on Windows (Git Bash), where
  the commands are `m65.ls.cmd`, ... wrappers around `m65.exe`. On Windows the address cache
  lives in `%LOCALAPPDATA%\m65fs\target`.

### Tooling

- `m65 --list-commands` prints the commands; `tools/install.sh` and `tools/build-native.sh`
  take the list from the binary instead of hardcoding it, and a test checks the `bin/m65.*`
  links.
