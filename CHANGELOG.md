# Changelog

Changes to m65fs, newest first. Dates are when the work was done.

## 2026-11-24

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

Session starts vary by up to a second in both tools (the hypervisor sometimes answers the
trigger late). An 8 x 4 MiB write/read endurance run (2.5 minutes) passed without errors.

### Tooling

- `m65 --list-commands` prints the commands; `tools/install.sh` and `tools/build-native.sh`
  take the list from the binary instead of hardcoding it, and a test checks the `bin/m65.*`
  links.
