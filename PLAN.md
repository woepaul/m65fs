# m65fs — Java NIO FileSystemProvider for mega65_ftp's Ethernet mode

Status: **draft for review** (2026-11-20)

Sources analysed (mega65-tools):
`src/tools/mega65_ftp.c`, `src/tools/etherload/etherload_common.c`, `include/etherload_common.h`,
`src/tools/etherload/ethlet_{echo,dma_load,all_done_basic2}.a65`, `src/utilities/remotesd_eth.c`.

---

## Part 1 — Protocol analysis

### 1.1 Big picture

`mega65_ftp -e` does **not** talk to a file server. It runs in three phases:

1. **Bootstrap (Etherload / hypervisor level).** The MEGA65 hypervisor's Ethernet remote
   mode accepts UDP packets on port 4510 whose payload *is 6502 machine code* (an "ethlet").
   The hypervisor executes the code in place from the RX buffer. The host uses ethlets to
   upload a helper program to `$0801` and `RUN` it.
2. **Session (helper program `remotesd_eth.prg`).** The helper takes over the Ethernet
   controller and serves a small **raw sector protocol** (`mreq`/`mrsp`): read sectors,
   write sectors, read memory, mount D81, quit.
3. **FAT32 on the host.** All file system logic — MBR, FAT32, directories, VFAT long names,
   cluster allocation — lives **on the PC** (`mega65_ftp.c`). The MEGA65 is just a remote
   block device.

Consequence: the Java library is mainly (a) a reliable UDP transport, (b) a block device,
and (c) a full FAT32 implementation. (c) is the largest piece of work.

Everything is IPv6 link‑local UDP, port **4510** on both ends of the MEGA65 side.
No native code is needed: `DatagramChannel` + `NetworkInterface` cover scope IDs,
multicast join, `IP_MULTICAST_IF` and `SO_REUSEPORT`. **No FFM up/downcalls required.**

### 1.2 Phase 0 — Discovery and trigger

| Step | Detail |
|---|---|
| Address given (`-i fe80::…%if`) | Parse `addr%ifname`, scope id = interface index. |
| Auto‑discovery (`-e` alone) | For every UP interface with a link‑local IPv6 address: UDP socket, `IPV6_V6ONLY`, `SO_REUSEADDR`, `SO_REUSEPORT`, join `ff02::1` on that interface, bind `[::]:4510`. Wait up to 2.5 s for a datagram whose payload is exactly the 6 bytes `"mega65"`. Its source address + scope = target. (The MEGA65 beacons this while in remote mode: DIP 2 on, or SHIFT+£.) |
| Client socket | One unbound (ephemeral port) UDP socket, `IPV6_MULTICAST_IF` = target scope, non‑blocking. All later traffic goes to `[target]:4510`; replies from any other address/port are dropped. |
| Hyperrupt trigger | Send 128 bytes to `[ff02::1%scope]:4510`: zeros, with `"65GSKEYCODE" 0x00 0x80` (12 bytes) at offset `0x24`. Sleep 10 ms. This traps the hypervisor into Ethernet remote control. |

### 1.3 Phase 1 — Etherload (ethlets)

Ethlets are assembled 6502 blobs (Ophis). The host patches fields in the blob, then sends it.
Each ethlet echoes (part of) its own packet back as an ACK, with the MACs/IPs/ports swapped.

| Ethlet | Size | Purpose | Seq field | ACK = |
|---|---|---|---|---|
| `ethlet_echo` | 1024 | ping | LE16 @ 1022 | any 1024‑byte reply |
| `ethlet_dma_load` | 1280 | DMA `byte_count` bytes from packet `data` to 28‑bit address | LE16 @ `seq_num` (map offset) | byte‑identical echo of the 1280‑byte packet |
| `ethlet_all_done_basic2` | 1024 | set BASIC end pointer, optionally `RUN` | LE16 @ 1022 | any 1024‑byte reply |

`dma_load` patch fields (offsets from the Ophis `.map` file): `dest_address` (LE16, low 16
bits), `dest_bank` (bits 16‑19), `dest_mb` (bits 20‑27), `byte_count` (LE16),
`rom_write_enable+1`, `data` (payload). A transfer must not cross a 1 MB boundary (split it).
"Duplicate" = another in‑flight packet with the same destination address (must wait).

Bootstrap sequence used by mega65_ftp:

1. trigger hyperrupt; `ping` (echo ethlet, 3 s timeout) — failure ⇒ "ETHLOAD.M65 missing on SD".
2. Upload `remotesd_eth.prg` minus its 2‑byte load address, to `$0801`, in 1024‑byte
   `dma_load` packets, window 4. Wait all ACKs.
3. Patch `all_done_basic2`: `data_end_address = $0801` (sic — mega65_ftp writes `01 08`),
   `do_run = 1`, `enable_cart_signature = 0`. Send as single command (2 s).

### 1.4 Phase 2 — `remotesd_eth` session protocol

All multi‑byte fields little‑endian. Header (7 bytes):

```
0..3  magic   "mreq" (host→M65)  /  "mrsp" (M65→host)
4..5  seq     LE16, assigned by the sliding window
6     opcode
```

Session rules enforced by the helper:
* The first accepted packet must be **Hello (0xFD)**; it locks the session to the client's
  IPv6 address **and UDP source port**. Anything else before that is dropped; packets from
  other clients are ignored (a second Hello from another port is fatal on the M65).
  ⇒ one `FileSystem` instance per MEGA65, one socket for its lifetime.
* UDP checksum is verified; bad packets are silently dropped.

| Op | Request (after header) | Req len | Response (after header) | Resp len | Host matches response by |
|---|---|---|---|---|---|
| `0x02` write sector | `batch_ctr u8, n‑1 u8, slot u8, start_sector u32, data[512]` | 526 | `batch_ctr, n‑1, slot, sector u32` | 14 | bytes 7..13 equal |
| `0x04` read sectors | `n‑1 u8, 0x00, start_sector u32` | 13 | `0, 0, sector u32, data[512]` — **one packet per sector**, seq = req.seq + i | 525 | bytes 9..12 (sector) |
| `0x11` read memory | `n‑1 u8, addr u32` (28‑bit) | 12 | `n‑1, addr u32, data[n]` | 12+n | bytes 7..11 |
| `0x12` mount D81 | `filename\0` (≤63 chars, root dir only) | 8..71 | `status u8` (0 ok, 1 error) | 8 | seq |
| `0xFD` hello | — | 7 | — | 7 | opcode/len |
| `0xFE` reset TX | — | 7 | *(no reply)* | — | not used by mega65_ftp |
| `0xFF` quit | — | 7 | — | 7 | opcode/len; M65 then resets (jmp $E4B8) |

Semantics and limits worth preserving:

* **Read:** max **128** sectors per request (helper: `n‑1 > 127` is fatal). The host sends
  *one* request but registers N *virtual* pending requests (one per sector, consecutive
  seqs). If a sector reply is lost, only that virtual single‑sector request (`n‑1 = 0`)
  is retransmitted. mega65_ftp reads 16 sectors per call (read‑ahead into a cache).
* **Write, single sector** (`n‑1 = 0`): written and verified synchronously; ACK carries
  `start_sector + slot`.
* **Write, batch** (`n‑1 ≥ 1`, i.e. 2..128 sectors, contiguous): packets carry slot indices
  0..n‑1, buffered at `$40000+slot*512` on the M65, written with SD multi‑sector commands
  in order, then **read back and verified**. `batch_ctr` (u8, wrapping) identifies the batch;
  late retransmits of older batches are ignored ("outdated" via 8‑bit serial arithmetic).
  The host must wait for **all ACKs** before starting a new batch and before switching
  between read and write (mixing is fatal on the M65).
* **Window size:** reads use queue length 256 (bounded by the 128‑sector batch). Writes /
  other ops use 3, or **28** for hardware model ids `0x01, 0x03, 0x04, 0x21` (A200T boards
  with more RX buffers). Model id is `peek(0xFFD3629)`.
* **Read memory**: helper allows up to 256 bytes; mega65_ftp only ever reads 1 byte.
* mega65_ftp's own "SDHC check" reads sectors 0, 1 and 0x200 after login.

### 1.5 Reliability layer (`etherload_common.c`)

A generic selective‑repeat window used for both phases, parameterised by callbacks
(`get_seq`, `embed_seq`, `match`, `is_duplicate`, `timeout_handler`):

* `N` slots (≤256). `send(p)`: wait until a slot is free and no in‑flight duplicate exists;
  embed next seq (global LE16 counter, wraps), store copy + send time, transmit.
* Any received datagram from target:4510 → `get_seq ≥ 0` → reset retry counter, update
  inter‑arrival time → first in‑flight slot whose `match(rx, tx)` is true is freed (and the
  match callback is where the response payload is consumed).
* Retransmit: **only the oldest** unacked slot, when `now − last_resend > retx_interval`,
  `retx_interval = clamp(2 ms + retx_cnt · last_rx_interval, 50 ms, 1 s)`.
* Fatal timeout: oldest slot unacked for > 4 s **and** nothing received for > 4 s.
* Operation timeouts: 3 s (`ETHERNET_TIMEOUT`) for waiting on slots / all ACKs.
* `single_command` temporarily swaps in echo‑style callbacks (1024‑byte packets, seq @1022).

### 1.6 Blobs we need to ship

| Blob | Built from | Toolchain |
|---|---|---|
| `remotesd_eth.prg` | `src/utilities/remotesd_eth.c` + `.s` files, `mega65-libc` | cc65 (`/usr/bin/cl65` present) |
| `ethlet_echo.bin`, `ethlet_dma_load.bin`, `ethlet_all_done_basic2.bin` (+ `.map`) | `src/tools/etherload/*.a65` | Ophis (in mega65-tools tree) |

These are **GPL‑3** (mega65-tools). Shipping them inside the jar makes the jar GPL‑3 as
well — fine if m65fs is GPL‑3, otherwise load them from a user‑supplied path. (Decision
needed, see §3.)

---

## Part 2 — Implementation plan

### 2.1 Goals / non‑goals

Goals
* `FileSystems.newFileSystem(URI.create("mega65://[fe80::…%25enp3s0]/"), env)` and
  `mega65://auto/` (discovery) giving a read/write view of the MEGA65 SD card's FAT32
  partition: directory listing, read, write, create, truncate, delete, mkdir, rmdir,
  rename/move, attributes, `FileStore` space info.
* Extras outside NIO on the FS object: `mountD81(Path)`, `peek(addr, n)`, `hardwareModel()`.
* Pure Java (target JDK 25 LTS bytecode — you have 27 locally), no runtime dependencies,
  no native code, JPMS module.

Non‑goals (v1)
* Serial and direct‑device modes; slot flashing (`putslot`/`getslot`), `.cor` flashing.
* MEGA65 system partition (type `0x41`) features (freeze slots, service area).
* Symlinks, `WatchService`, POSIX attributes, file locks (→ `UnsupportedOperationException`).
* Concurrent use from multiple JVMs/hosts (the protocol forbids it anyway).

### 2.2 Architecture (bottom‑up)

```
nio        Mega65FileSystemProvider · Mega65FileSystem · Mega65Path · channels · attrs · FileStore
fat32      Mbr · BootSector(BPB) · FsInfo · FatTable · ClusterChain · DirEntry/LfnEntry · Directory · Fat32Volume
block      BlockDevice  ←  CachingBlockDevice  ←  { RemoteSdBlockDevice | ImageFileBlockDevice }
remotesd   RemoteSdSession (hello/read/write/peek/mount/quit) · Request/Response records
etherload  Bootstrapper (trigger, ping, dmaLoad, runBasic) · Ethlet blobs + offsets
transport  Mega65Locator (discovery) · UdpLink · SlidingWindow<T>
```

Package root `io.github.paw.m65fs` (sub-packages as above).

#### transport
* `record Target(Inet6Address address, NetworkInterface nif)`; `Mega65Locator.discover(Duration)`
  and `Target.parse("fe80::1%enp3s0")`.
* `UdpLink implements AutoCloseable` — one non‑blocking `DatagramChannel` + `Selector`;
  `send(ByteBuffer)`, `sendMulticast(...)`, `receive(Duration) → Optional<Datagram>`
  filtering on source `target:4510`.
* `SlidingWindow` — a faithful port of §1.5, but with the callbacks replaced by a
  `Protocol` strategy object (`seqOf`, `embedSeq`, `matches`, `isDuplicate`) and a per‑slot
  `CompletableFuture`/callback for the response. Single‑threaded, driven by the caller
  (`submit`, `awaitFreeSlots(n)`, `awaitAll()`); timing via `System.nanoTime()`.
  Timeouts → `Mega65TimeoutException extends IOException` instead of `exit(-1)`.
  Retransmit policy and constants identical to C first; tune later.

#### etherload
* `enum Ethlet { ECHO, DMA_LOAD, ALL_DONE_BASIC2 }` loading the blob from resources, plus a
  `record EthletLayout(int seqNum, int destAddress, …)` with offsets.
* `Bootstrapper.bootstrap(UdpLink, byte[] helperPrg)` — trigger, ping, upload, run.

#### remotesd
* `sealed interface Request permits Hello, ReadSectors, WriteSector, ReadMemory, Mount, Quit`
  (records, each with `encode(ByteBuffer)`), and
  `sealed interface Response permits HelloAck, SectorData, WriteAck, MemoryData, MountResult, QuitAck`
  with a `static Response parse(ByteBuffer)`; matching via `switch` with record patterns.
* `RemoteSdSession implements AutoCloseable`:
  `readSectors(long lba, int count, ByteBuffer dst)` (split into ≤128, virtual per‑sector
  pending entries exactly like C), `writeSectors(long lba, ByteBuffer src)` (contiguous
  run → batches of 2..128 or single; await all ACKs per batch), `peek`, `mount`, `close()` → quit.
  Window size set from the model id after hello.
* Enforces the "no read/write mixing while in flight" rule internally.

#### block
* `interface BlockDevice extends AutoCloseable { int SECTOR = 512; long sectorCount(); read(long lba, ByteBuffer); write(long lba, ByteBuffer); flush(); }`
* `ImageFileBlockDevice` (`FileChannel`) — for tests and for working on `.img` dumps.
* `CachingBlockDevice` — LRU read cache with read‑ahead (default 16, configurable), and a
  write‑back buffer that coalesces dirty sectors into sorted contiguous runs on `flush()`
  (mega65_ftp flushes at 32 KB; we flush at 64 sectors and on `close`/`force`).
  Cache stays coherent with writes (write‑through into cache).

#### fat32 (largest part; independent of the network, fully testable with images)
* `Mbr` → pick partition type `0x0B`/`0x0C`; also accept a superfloppy (no MBR).
* `BootSector` record (bytes/sector must be 512, 2 FATs, sectors/cluster, reserved, FAT size,
  root cluster, FSInfo sector) with validation as in `open_file_system`.
* `FatTable` — read/write entries (28‑bit), mirror every write to FAT #2, free cluster search
  using FSInfo hint, `FsInfo` free‑count/next‑free maintenance.
* `ClusterChain` — follow / extend / truncate / free; contiguous‑run detection for bulk I/O.
* Directory entries: 8.3 + VFAT LFN (UCS‑2, checksum), short‑name generation `NAME~N.EXT`
  with collision check, case‑insensitive lookup, `.`/`..`, deleted entries (0xE5),
  attributes, FAT timestamps (2 s resolution, local time).
* `Fat32Volume` — façade: `lookup`, `list`, `createFile`, `mkdir`, `delete`, `rename/move`
  (within volume, incl. updating `..` of moved directories), `openForRead/Write`.
* File I/O: cluster‑aligned reads/writes go straight through `readSectors/writeSectors`
  in large contiguous runs (this is where throughput comes from); partial clusters via cache.
* Single global `ReentrantLock` on the volume — the transport is single‑threaded anyway.

#### Hard requirement: files are never fragmented

MEGA65 cores (and Hyppo for D81 mounts) address files by start sector + length, so **every
regular file's cluster chain must be one contiguous run**. This is an invariant of the
library, not a best effort:

* **Allocator:** `FatTable.allocateContiguous(n)` finds a run of `n` free clusters
  (first‑fit starting at the FSInfo next‑free hint, wrapping once; bounded by the cluster
  count — mega65_ftp's `find_contiguous_clusters` has no end‑of‑disk check and loops
  forever when the disk is full). There is **no** API that allocates a single non‑adjacent
  cluster for a file.
* **Write model — commit on close.** A channel opened with `WRITE` writes into a host‑side
  shadow buffer (memory up to a threshold, default 8 MiB, then a temp file). The existing
  content is loaded into the shadow only when needed (not for `TRUNCATE_EXISTING` /
  `CREATE_NEW`). On `close()` the final size is known and the file is laid out in one go:
  1. same cluster count, already contiguous → overwrite in place;
  2. fewer clusters → overwrite in place, free the tail;
  3. more clusters and the clusters directly after the current run are free → extend in place;
  4. otherwise → allocate a new contiguous run, write the data, then repoint the
     directory entry and free the old run.
  Order of writes for 4: data → both FATs → directory entry → free old chain → FSInfo, so an
  interruption leaves either the old or the new file, plus at worst leaked clusters
  (`fsck.fat` fixable) — never a fragmented or cross‑linked file.
  In‑place cases (1‑3) keep the start sector stable, which matters for an image that is
  currently mounted.
* **No space:** if no contiguous run of the required size exists, `close()` throws
  `NoContiguousSpaceException extends FileSystemException` (message states clusters needed
  and the largest free run) and the original file is left untouched. No fragmented fallback.
* **Existing fragmented files** (written by other tools): readable as usual; the
  `mega65:fragmented` attribute reports it; any write to such a file relocates it into a
  contiguous run on close (same as mega65_ftp's "fragmented, deleting to recreate"). A
  helper `Mega65Files.defragment(Path)` does the same without content changes.
* **Directories** are extended cluster‑by‑cluster when they fill up; contiguity is preferred
  (try the cluster right after the last one first) but not required. Evidence (searched
  mega65-tools, mega65-core/hyppo, mega65-freezemenu): every contiguity check/allocation
  concerns *files* — Hyppo `dos_d81check` rejects fragmented images with
  `dos_errorcode_image_fragmented` ($8B), `dos_find_contiguous_free_space` /
  `fat32_create_contiguous_file` create contiguous files. Hyppo's `dos_readdir` advances via
  `dos_file_advance_to_next_sector` → `dos_file_advance_to_next_cluster`, i.e. it follows
  the FAT chain. Nothing requires contiguous directories.
* **Tests:** every mutating FAT32/NIO test ends with an invariant check that walks the
  whole volume and asserts every regular file is contiguous, plus `fsck.fat -n`. Dedicated
  tests: fill the disk with a checkerboard of small files, delete every other one, then
  write a file larger than any free run → `NoContiguousSpaceException`, original intact.

#### nio
* `Mega65FileSystemProvider` — scheme `mega65`; URI `mega65://<host>/path` where host is
  `auto` or a bracketed IPv6 literal with zone (`[fe80::1%25enp3s0]`). Registry keyed by
  target; `newFileSystem` / `getFileSystem` / `getPath(URI)`.
  `env` keys: `discoveryTimeout`, `readAhead`, `windowSize` (override), `quitOnClose`
  (default true: sends quit ⇒ MEGA65 resets), `helperPrg` (path override), `imageFile`
  (use a local image instead of the network — great for tests/offline).
* `Mega65Path` — a `record` (`fs`, `List<String> names`, `boolean absolute`) implementing
  `Path`, with `equals/hashCode/compareTo` overridden to be ASCII case‑insensitive (FAT
  semantics). Separator `/`.
* `SeekableByteChannel` implementation for READ / WRITE / APPEND / CREATE / CREATE_NEW /
  TRUNCATE_EXISTING; size + dir entry updated on close/`force`.
* `DirectoryStream<Path>` with filter support.
* Attributes: `BasicFileAttributes` / `BasicFileAttributeView` (times settable), `DosFileAttributes`
  / `DosFileAttributeView` (readonly/hidden/system/archive), plus a `"mega65"` view exposing
  short name, first cluster and `fragmented`. `readAttributes(path, "dos:*")` string form.
* `Mega65FileStore` — total/usable/unallocated from BPB + FSInfo (rescan FAT if FSInfo invalid).
* `copy`/`move` within the FS natively; cross‑provider copy works via `Files.copy` streams.
* `META-INF/services` **and** `module-info` `provides java.nio.file.spi.FileSystemProvider`.

### 2.3 Testing strategy

1. **FAT32 unit/integration tests** against images made with `mkfs.fat -F 32 -S 512`
   (present on this machine) and cross‑checked with `mtools` (`mdir`, `mcopy`, `fsck.fat -n`
   after every mutating test). Includes LFN edge cases, full directories, fragmentation,
   disk‑full.
2. **Protocol tests** with an in‑JVM **MEGA65 simulator**: a UDP server on `[::1]` port 4510
   that implements `remotesd_eth` semantics (hello lock, batch rules, per‑sector read
   replies, seq echoing) backed by an image file, with injectable **packet loss,
   duplication and reordering** to exercise the retransmit paths. (The simulator skips the
   ethlet phase; bootstrap gets a separate fake that just ACKs.) Requires the transport to
   allow a non‑4510 port / loopback target — test‑only knob.
3. **Byte‑level golden tests**: encode every request record and compare with the byte
   layout the C code produces (hand‑derived fixtures from §1.4).
4. **Hardware smoke test** (manual, tagged): discover, list `/`, upload/download/compare a
   few MB, mkdir/rename/delete, `fsck.fat` on the card afterwards; and a throughput figure
   compared to `mega65_ftp -e`.

### 2.4 Milestones

| # | Deliverable | Verifiable by |
|---|---|---|
| M0 ✅ | Build skeleton, module, CI‑able `test` target; blob build script `tools/build-blobs.sh` that runs the mega65-tools Makefile targets and copies `.bin/.prg` + extracts map offsets | `./build test` green; blobs in resources |
| M1 ✅ | `transport` + `etherload`: discover, trigger, ping, upload helper, run | hardware: helper screen appears on MEGA65 |
| M2 ✅ | `remotesd` session + simulator: hello, peek(model id), read/write sectors, quit | simulator tests with loss; hardware: read MBR |
| M3 ✅ | `block` layer + `fat32` read‑only (list, read files, attributes) | image tests; hardware: `ls` matches mega65_ftp `dir` |
| M4 ✅ | `fat32` write (create, write, truncate, delete, mkdir, rmdir, rename/move, FSInfo) | image tests + `fsck.fat`; hardware round‑trip |
| M5 ✅ | `nio` provider complete (paths, channels, streams, attrs, FileStore, URI handling) + `mountD81` | `Files.*` API tests on image‑backed FS |
| M6 ✅ | Hardening: timeouts/errors as exceptions, close semantics, logging via `System.Logger`, README with usage | review |
| M7 ✅ | Launching: boot D81s and run PRGs in C64 (GO64) and MEGA65 mode, reset/detach | hardware markers |
| M8 ✅ | Defragmentation: scan report + optional fix, `Defrag` CLI (jar main class) | image tests + hardware (file fragmented on purpose) |
| M9 ✅ | `m65.*` tools (coreutils style: ls/tree/cp/mv/rm/mkdir/cat/stat/df + boot/run/reset/mount/peek/discover/defrag), GraalVM native binary, agent skill in `.claude/skills/m65fs` | CLI tests on images + hardware (native) |
| M10 | Bash tab completion for card paths (`m65:/abs/path`, `m65:rel/path`, and plain paths for card-only tools) and options; `m65 completion bash\|install\|uninstall`; `m65 install\|uninstall` creates or removes its own `m65.*` links (simpler `install.sh`), see §2.6 | completion tests (script driven by `COMP_*` variables, image-backed); hardware: TAB with and without a running helper |

M3 can be developed in parallel with M1/M2 since it only needs `ImageFileBlockDevice`.

### 2.5 Risks / notes

* **M1 status:** done and verified on hardware (2026-11-20): discovery, trigger, ping,
  helper upload (our own cc65 build), RUN, then a minimal hello + quit — 0.6 s total.
  Hardware bug found: the trigger magic starts with the *byte* `0x65`, not the characters
  "65" (`magic_string` comment "65 G S" is misleading); pinned by a unit test.
* **M9 status (2026-11-20):** `tools.M65fs`: one program, invoked as `m65.CMD` (argv[0]
  via GraalVM's `ProcessProperties` in the native binary) or `m65 CMD`. GNU cp/mv semantics
  (overwrite by default, `-n`, `-r`, `-p`), card paths `m65:/PATH` (scp style). Environment
  `M65_TARGET`, `M65_IMAGE`, `M65_RESET` (default: no reset after file tools). Native binary
  via `tools/build-native.sh` (GraalVM CE 25.0.2, cached in ~/.cache/m65fs; provider
  initialized at build time because the JDK's installed-provider list is; blobs via
  reachability-metadata.json, verified after each build). `m65.ls` on an image: ~3 ms native vs
  ~60 ms JVM; on the card ~0.8 s (helper bootstrap). Skill rewritten for the `m65.*` tools.
* **M8 status (2026-11-20):** `Fat32Volume.defragment` (raw cluster copy into one free run,
  entry switched, old clusters freed; times/attributes/size kept), `nio.Defragmenter`
  (scan → report, defragment → per-file results, smallest first, NO_SPACE retried once),
  `tools.Defrag` CLI (report by default, `--fix`, `--image`). Scan of the real card: 275 files,
  0 fragmented, `/amiga/adf` directory in 2 fragments; ~5 s. Not computing the largest free run
  in the scan saved ~30 s per scan (it reads the whole 27 MiB FAT).
* **M7 status (2026-11-20):** `launch` package. Ethlets `all_done_basic65` and
  `all_done_jump` added; own `c64boot.a65` loader (LOAD"name",8,1 + RUN via keyboard buffer,
  runs from $0334). MEGA65-mode disk boot uses a BASIC 65 stub `MOUNT"img":RUN"name"`: with a
  disk attached by the reset ethlet the ROM does not start the program in memory (hardware
  finding). After a reset the hypervisor may ignore the trigger for > 6 s; `Etherload.connect`
  retries for ~15 s and the `closeAnd…` methods skip the session's quit. A MEGA65-mode reset
  detaches drive 0. Verified on hardware with RAM markers (nonce + PEEK(44) for C64 mode).
* **M6 status (2026-11-20):** project renamed m65lib → m65fs (artifact, package
  `io.github.paw.m65fs`, module). Failure handling: `FailFastBlockDevice` poisons a
  `Fat32Volume` after the first device error (cached FAT/directories may be stale);
  `RemoteSdSession` marks itself failed on timeout/interrupt/socket error, later calls throw
  with the original cause, `close()` then sends quit best-effort (3 × 300 ms) and logs a
  warning if unanswered. `mutate` no longer masks the original exception with a failing
  flush. Interrupts → `InterruptedIOException` (no spinning until the timeout). Logging:
  nothing at INFO; sessions/bootstrap/layouts at DEBUG, retransmits at TRACE. README.md.
  Hardware: `mountD81` verified (drive 0 sector register = file start sector); quit's soft
  reset keeps the mount, only a hardware reset detaches it. Full hardware suite re-run green.
* **M5 status (2026-11-20):** `Mega65FileSystemProvider` (scheme `mega65`, registered via
  `module-info` and `META-INF/services`), paths (case-insensitive), write-on-close channels
  (shadow in memory ≤ 8 MiB, then temp file), directory streams, copy/move, basic/dos/mega65
  attribute views, `Mega65FileStore` (incl. largest contiguous space), glob/regex matchers,
  `mountD81`, `peek`, image files via `newFileSystem(Path)`. Verified on hardware through
  `FileSystems.newFileSystem(URI.create("mega65://auto/"))` (D81 write 1.7 s / read 0.8 s).
  `mountD81` tested against the simulator only (needs an image in the card's root directory).
  IPv6 zones: Java's `URI` keeps `%if` unencoded; `%25if` (RFC 6874) is accepted as well.
  `Mega65FileSystem.commit()`: writes the current content of still-open write channels
  (they stay open) and returns once every sector is acknowledged; the session continues.
  All other operations are already committed when they return (no host write-back cache;
  remotesd_eth acks a batch's last sector only after writing and verifying the batch).
  Closing the file system closes (and thus writes) channels left open.
* **M4 status (2026-11-20):** write side done and tested offline — create/mkdir/delete/
  move/rename/attributes/times, VFAT long names + `~N` aliases, directory growth, FSInfo,
  both FATs, contiguous-only content writes (in place / shrink / extend / relocate,
  `NoContiguousSpaceException`). Verified by own reader + mtools + `fsck.fat -n` after every
  test, and 4×400 random operations against a model. Hardware test
  `HardwareSmokeTest#writeInsideTestDirectory` ready (FAT snapshot → work in
  `<testdir>/run-*` → clean up → FATs must be byte-identical). **Passed on hardware
  2026-11-20** (27 MiB FATs, 4 KiB clusters, ~480 KiB/s writing); the C mega65_ftp sees the
  test directory empty afterwards. (A first attempt failed on a bug in the test itself —
  it looked up a file it had just moved; the card's directory slots showed the library had
  done the right thing.)
* **M2 status:** done; verified on hardware (read-only): listing of `/` matches mega65_ftp,
  ~940 KiB/s reading.
* **M3 status:** `Fat32Volume` read side done (MBR/bare volume, BPB, FAT chains as extents,
  8.3 + VFAT names, timestamps, byte-range reads); tested against mkfs.fat/mtools images.
  `CachingBlockDevice` still to do (needed once the remote device exists, M2).
* **M0 findings:** `ethlet_dma_load.bin` is only 1260 bytes while mega65_ftp always sends
  1280 (it reads 20 bytes past the C array) — we zero-pad to 1280. Fedora's cc65 2.19
  segfaults on `remotesd_eth.c`; `tools/build-blobs.sh` builds the cc65 pinned by
  mega65-tools instead.

* **Write safety:** a bug in FAT32 code corrupts the user's SD card. Mitigation: image‑based
  tests + `fsck.fat` before any hardware writes; hardware testing on a spare card.
* **Quit resets the MEGA65.** `close()` must send quit or the helper keeps running and a new
  session cannot Hello (it's locked to the old source port). Re‑attaching to a running
  helper is impossible by design, so a JVM crash requires a MEGA65 reset.
* **`data_end_address` patch:** mega65_ftp sets it to `$0801`, which looks odd but is what
  works; copy verbatim.
* **Blocking API:** NIO channels are blocking; the transport polls with `Selector.select(timeout)`
  rather than the C busy loop (`usleep(20)`). Virtual‑thread friendly.
* FAT timestamps use local time; convert via a configurable `ZoneId` (default system).

### 2.6 Shell completion (M10, planned)

Goal: TAB in bash completes card paths like local ones, in every tool that takes them.

* **Which words are card paths:** in `cp` (and any tool that takes both): only words starting
  with `m65:` (`m65:/abs/path`, `m65:rel/path`, relative to the shared working directory);
  other words get bash's normal file completion. In card-only tools (`ls`, `tree`, `cd`, `rm`,
  `mkdir`, `mv`, `cat`, `stat`, `dsplit`, `mount`, `defrag`): plain `/PATH` and `PATH` too,
  except where a tool also takes local paths (`dsplit`: a local directory that exists wins,
  as in the tool itself).
* **Where the names come from:** a hidden subcommand, e.g. `m65 __complete WORD`, prints the
  matching entries of the directory in WORD (one per line, directories with a trailing `/`),
  using the library. A bash script (`tools/completion/m65.bash`) calls it.
* **Never take over the MEGA65 for a TAB:** completion uses only a helper that is already
  running and free (`RemoteSdSession.attachRunning`, no trigger, no bootstrap). If the MEGA65
  is in BASIC or running a program, or the helper is busy, completion offers nothing for card
  paths (silently, no message, no waiting).
* **Quiet on the MEGA65:** no log lines on the helper's screen for completion sessions
  (`showOnMega65` off), so the log is not flooded by TABs.
* **Fast enough:** a session with the running helper costs about 40 ms, plus one directory
  read; a short cache (a few seconds, keyed by directory) in `~/.cache/m65fs/` keeps repeated
  TABs from asking again.
* **Bash details:** `:` is in `COMP_WORDBREAKS`, so `m65:/x` arrives split; handle it like
  bash-completion's `__ltrim_colon_completions` (or `_get_comp_words_by_ref -n :`). Names with
  spaces and special characters must come back escaped; `compopt -o nospace` after a
  directory, `-o filenames` for local words.
* **Options:** from each tool's usage line (`--help`), e.g. `-r`, `--dry-run`, `-L`.
* **Installation, from the binary itself:** the completion script is a resource in the `m65`
  binary (so it always matches the version), and `m65` manages it:
  * `m65 completion bash`: prints the script (for `source <(m65 completion bash)` in
    `~/.bashrc`, or for packagers).
  * `m65 completion install`: installs it for bash-completion's lazy loading in
    `${XDG_DATA_HOME:-~/.local/share}/bash-completion/completions/`: one file `m65` plus an
    entry for every `m65.*` command (bash-completion looks files up by command name), each
    marked as written by m65fs. Prints what it did and that new shells pick it up (or
    `source` the file for the current one). Running it again after an update replaces the
    files.
  * `m65 completion uninstall`: removes exactly the files it installed (only those with the
    m65fs marker), nothing else.
  * The release archive's README says to run it once.
* **The binary sets up its own commands:** `m65 install` / `m65 uninstall`, so that only the
  binary knows which `m65.*` commands exist (today `tools/install.sh`,
  `tools/build-native.sh` and the native workflow ask `m65 --list-commands` and create the
  links, or check them, themselves).
  * `m65 install`: creates the `m65.CMD` entries next to the running binary (its own path:
    `/proc/self/exe` or `ProcessHandle`): relative symlinks `m65.ls -> m65` on Linux and
    macOS, `m65.ls.cmd` wrappers on Windows (as the release archive has them). An existing
    file of that name that is not an m65 link or wrapper is left alone (with a warning).
    Entries for commands that no longer exist (e.g. `m65.split`, renamed to `m65.dsplit`) are
    removed. Then, on Linux and macOS, `m65 completion install` (`--no-completion` to skip).
  * `m65 uninstall`: removes the `m65.*` links and wrappers next to the binary that point to
    it, and the completion files; the binary itself stays (the user deletes it).
  * `tools/install.sh` shrinks to: build the native binary, copy it to DIR, run
    `DIR/m65 install`. `tools/build-native.sh` and the release packaging (each platform's
    archive is built on that platform) can do the same; `--list-commands` stays for the
    archive check and for compatibility.
* **Later:** zsh and fish (the `__complete` subcommand is shell independent).

---

## Part 3 — Decisions

| # | Topic | Decision |
|---|---|---|
| 1 | Build tool | **Maven** (with wrapper) |
| 2 | Java baseline | **25** |
| 3 | Package / module name | **`io.github.paw.m65fs`** (groupId `io.github.paw`, artifactId `m65fs`) |
| 4 | Licence | **GPL‑3**, MEGA65 blobs bundled in the jar |
| 5 | `quitOnClose` default | **true** |
| 6 | Scope | as listed in §2.1 |

Note on `mountD81`: unlike everything else, mounting is resolved **on the MEGA65** by the
hypervisor's own FAT32 code (filename lookup, root directory only). So the host must flush
its write‑back cache before sending the mount request, otherwise Hyppo may see a stale
directory/FAT.
