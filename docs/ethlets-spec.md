# m65fs ethlets

The small 45GS02 programs ("ethlets") that m65fs sends to a MEGA65 over Ethernet to load data
into memory and to start programs. Sources: `src/main/asm/ethlets/` (ACME 0.97, `--cpu m65`),
built by `tools/build-ethlets.sh` into `src/main/resources/io/github/paw/m65fs/blobs/`. The
Java side is `io.github.paw.m65fs.etherload.Ethlet` (field offsets) and `Etherload`.

They replace mega65-tools' etherload ethlets for m65fs: three instead of five, only what m65fs
uses, and using the 45GS02 and the DMA controller where that makes them shorter or faster.

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
