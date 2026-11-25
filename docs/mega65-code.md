# m65fs' MEGA65 code

Everything m65fs runs on the MEGA65 is its own 45GS02 assembly in `src/main/asm/`, built with
ACME 0.97 (`--cpu m65`) by `tools/build-blobs.sh` into
`src/main/resources/io/github/paw/m65fs/blobs/`:

* three **ethlets** (ECHO, LOAD, START), run by the hypervisor's `ETHLOAD.M65`;
* the **transfer helper** `helper.prg`, which serves the SD card during file commands;
* the C64 disk boot loader `c64boot.prg`.

They replace mega65-tools' five etherload ethlets and `remotesd_eth.prg`, keep their
protocols on the wire, and do only what m65fs uses. Java side: `etherload.Ethlet` (field
offsets), `etherload.Etherload`, `remotesd.RemoteSdSession` / `Request` / `Response`.

## Environment

1. The host sends the trigger frame. Hyppo's `ethernet_remote_trap`
   (`mega65-core/src/hyppo/task.asm`) closes all files, sets a C64 memory map (registers 0,
   SP = $01FF, interrupts off, CPU port $3F, C64 I/O, plain 40-column screen), saves the
   current disk and working directory (patched hypervisor, see `trap_dos_restorecwd`), changes
   to the root directory and runs `ETHLOAD.M65` from colour RAM.
2. `ETHLOAD.M65` (`mega65-core/src/utilities/etherload.a65`) switches to the MEGA65 I/O
   personality and the fast CPU, MAPs the Ethernet buffers at $6000–$7FFF (and itself at
   $8000–$9FFF, with the MAP megabytes set to $FF), and calls every UDP payload to port 4510
   that starts with $A9 (`LDA #`) with `JSR $6840`, B = $00.
3. In $6800–$6FFF reads return the received frame (2 length bytes, then the frame), writes go to
   the frame to be sent. An ethlet cannot change its own bytes. The DMA controller sees the same
   buffer at MB $FF, bank $D with I/O ($8D), $E800.

## Protocol

* One UDP datagram per ethlet, fixed size, from any host port to port 4510.
* Every ethlet answers with one datagram that carries the **request payload unchanged**,
  addresses and ports swapped (built with DMA; swapping does not change the UDP checksum).
* The host fills a 16-bit sequence number; replies are matched byte for byte.
* ECHO and LOAD return to ETHLOAD (`RTS`); START never returns.

## ECHO (256 bytes)

| Offset | Field |
|---|---|
| $FE | sequence number |

Stops audio DMA (so a sample playing when the trigger hit does not keep sounding), answers.
The host's ping after the trigger.

## LOAD (1280 bytes)

| Offset | Field |
|---|---|
| $0E | destination address bits 20–27 (MB) |
| $11 | byte count, LE16, 1..1024 |
| $16 | destination address bits 0–15, LE16 |
| $18 | destination address bits 16–19 (bank) |
| $FE | sequence number |
| $100 | data |

One in-line DMA job list: the data copy (its fields are the ones above) followed by the
reply. The host never lets a copy cross a 1 MiB boundary. About 4 KiB in 2 ms on hardware.

## START (1088 bytes)

| Offset | Field |
|---|---|
| $3C0 | disk image name, NUL-terminated, max. 63 characters, PETSCII as setname takes it (first byte 0 = none) |
| $400 | mode: 0 = reset into MEGA65 mode, 1 = reset into C64 mode, 2 = jump |
| $401 | flags: bit 0 = RUN |
| $402 | video: 0 = unchanged, 1 = PAL, 2 = NTSC |
| $404 | end address of the BASIC program LOADed at $2001 / $0801 (LE16, 0 = none), or the jump address |
| $43E | sequence number |

1. Answers, then waits ~5 ms so that the reply has left before the machine is taken over.
2. Mode 0: detaches all disk images (`$42`).
3. With a name: copies it to $0100 (setname reads RAM, not the Ethernet buffer), setname
   (`$2E`), attach as drive 0 (`$40`), looked up in the root directory.
4. Returns to the saved working directory (`$4C`; older hypervisors just fail).
5. Resets VIC-IV registers the ROMs leave alone ($D070, $D076, $D07A), sets PAL/NTSC.
6. Hands over through a trampoline in page 3 ($0334, RAM, so clearing the MAP is safe): MAP
   megabytes and MAP cleared, CPU port $3F, C64 CPU speed, C64 I/O, SP $01FF with 8-bit stack,
   interrupts off; then `JMP ($FFFC)` (MEGA65 mode), `JMP ($FFF6)` (C64 mode) or the jump
   address.

**C64 mode, getting control back:** a CBM80 cartridge signature in RAM at $8000 makes the C64
KERNAL's reset jump to our code in the cassette buffer. It gives $8000–$8008 back, does the
KERNAL and BASIC cold start itself without the RAM test (which would clear page 3 and write
into RAM at $A000), restores the first line link that NEW clears, sets the end pointer
($2D/$2E), LINKPRG, CLR, device 8, types RUN and enters READY. The ROM is not touched.

**MEGA65 mode, getting control back:** BASIC 65's interrupt handler starts with `JMP` at
$32006 (ROM, mapped at $2006). START points it at a hook in a free ROM page ($2C700, mapped at
$C700 with $D030 bit 5), saves the program's first bytes and the cartridge byte at $8004 (and
clears it), with the ROM write-enabled through the hypervisor (`$D641`, A = $02/$00). The first
interrupt restores all of it, sets the end pointer ($82/$83), types RUN and continues with
BASIC's handler. If the ROM still points at the hook (an attempt whose hook never ran), the
handler address saved then (at $2C7F0) is kept instead of saving the hook's own address.
Works with a disk attached, too.

## Transfer helper (`helper.prg`, 3.7 KB)

Loaded to $0800 with LOAD packets and started with START's jump mode (no ROM initialisation,
so a file command needs no reset). It switches to the MEGA65 I/O personality and 40 MHz, runs
with interrupts off and uses nothing from the ROM except its lower case character set
($2D800). On start it returns to the working directory the trigger saved (`$4C`).

**Screen.** 80 x 50 text at $3000: the VIC-IV derives the geometry from the legacy registers
with its hot registers on (`$D031` = H640, FAST, V400), then the helper sets its own screen
and character set pointers with the hot registers off. Rows: title (reversed), status, the
statistics (requests, KiB read and written, each with its rate over the last second, and the
seconds running; updated once a second from the frame counter `$D7FA`), a line, and from row 10
a log of the lines the host sends (`$14`). The log fills from the top; when it is full, it
scrolls one row per line (DMA, started below the log, i.e. in the bottom border or vertical
blank, so that no frame shows it half scrolled). Each tool starts the helper again: if its
title is still in screen memory, the log rows (screen and colour RAM) and the next free row
(at $3FA0, right after the screen) are kept, and if the VIC still shows that screen it is not
set up again. (The Ethernet trigger itself switches the VIC to a 40-column C64 screen,
`task_set_c64_memorymap` in Hyppo, so the screen still jolts once when a tool starts.)

**Border.** Red as soon as a request arrives, green when the helper is idle (no write batch
open, no request for 5 seconds), blinking red after a fatal error.

**Keyboard.** RUN/STOP or ESC (from the hardware queue `$D610`) quits into MEGA65 mode when
the helper is idle: no write batch open and no request for 5 seconds; otherwise the status row
says so. After a fatal error the same keys reset the machine. Before the reset the hot
registers are switched on again and `$D031` is cleared, so the ROM starts from the power-on
state of the VIC.

The protocol is remotesd_eth's (`"mreq"` / `"mrsp"`, sequence number, opcode over UDP port
4510), restricted to what `RemoteSdSession` uses:

| Opcode | Request | Answer |
|---|---|---|
| $FD hello | locks the session to the sender's address and port (a repeated hello from it is answered again) | header |
| $04 read | up to 128 sectors | one answer per sector, consecutive sequence numbers; the next sector is read from the card while the current one is sent |
| $02 write | one sector (written and read back right away), or one sector of a batch of 2..128 | acknowledged on arrival; batch sectors go into a cache at $40000 and are written in order as they arrive (multi-sector write), then read back and compared |
| $11 memory | up to 256 bytes at a 28-bit address | the bytes |
| $12 mount | D81 name (changes to the root directory) | status |
| $13 cwd | get, or set from a path | status, drive, cluster |
| $14 text | colour, mode (0 = new line, 1 = replace the last one), up to 80 screen codes | header |
| $FF quit | | header, then a reset into MEGA65 mode |

It also answers IPv6 neighbor solicitations for its own address (without that, the computer
stops sending to the MEGA65 once its neighbor entry expires).

Frames are copied from the Ethernet buffer into RAM by DMA, checked (addresses, port, UDP
checksum with 32-bit `ADCQ` additions) and answered from a frame template set up by hello.
Sector data moves between the SD card buffer, the cache and the frames by DMA only. A protocol
violation (read during a write batch, a duplicate with different data, a failed read-back)
stops the helper with a message on its screen; the host then notices the silence.
