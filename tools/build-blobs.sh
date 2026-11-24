#!/usr/bin/env bash
# Assembles the MEGA65 code m65fs sends to the machine into
# src/main/resources/io/github/paw/m65fs/blobs/. Everything is m65fs' own 45GS02 assembly
# (src/main/asm/), built with ACME 0.97 or newer:
#
#   ethlet_echo/load/start.bin  src/main/asm/ethlets/   (docs/mega65-code.md)
#   helper.prg                  src/main/asm/helper/    transfer helper
#   c64boot.prg                 src/main/asm/c64boot.a  C64 disk boot loader
#
# The sources check their own field offsets and sizes; this script checks the ethlet sizes.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
ASM="$HERE/src/main/asm"
OUT="$HERE/src/main/resources/io/github/paw/m65fs/blobs"
command -v acme >/dev/null || { echo "acme (0.97 or newer) not found" >&2; exit 1; }
mkdir -p "$OUT"

for e in echo:256 load:1280 start:1088; do  # name:size (no associative arrays: bash 3.2)
  name=${e%:*} expected=${e#*:}
  (cd "$ASM/ethlets" && acme --cpu m65 -f plain -o "$OUT/ethlet_$name.bin" "$name.a")
  size=$(wc -c < "$OUT/ethlet_$name.bin" | tr -d ' ')
  if [[ $size -ne $expected ]]; then
    echo "ethlet_$name.bin is $size bytes, expected $expected" >&2
    exit 1
  fi
done
(cd "$ASM/helper" && acme --cpu m65 -f cbm -o "$OUT/helper.prg" helper.a)
(cd "$ASM" && acme -f cbm -o "$OUT/c64boot.prg" c64boot.a)

ls -l "$OUT"
