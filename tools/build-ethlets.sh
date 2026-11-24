#!/usr/bin/env bash
# Assembles m65fs' ethlets (src/main/asm/ethlets/, see docs/ethlets-spec.md) with ACME into
# src/main/resources/io/github/paw/m65fs/blobs/ethlet_{echo,load,start}.bin.
# The sources check their own field offsets; this script checks the sizes.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
SRC="$HERE/src/main/asm/ethlets"
OUT="$HERE/src/main/resources/io/github/paw/m65fs/blobs"
command -v acme >/dev/null || { echo "acme (0.97 or newer) not found" >&2; exit 1; }

declare -A SIZE=([echo]=256 [load]=1280 [start]=1088)
for e in echo load start; do
  (cd "$SRC" && acme --cpu m65 -f plain -o "$OUT/ethlet_$e.bin" "$e.a")
  size=$(stat -c %s "$OUT/ethlet_$e.bin")
  if [[ $size -ne ${SIZE[$e]} ]]; then
    echo "ethlet_$e.bin is $size bytes, expected ${SIZE[$e]}" >&2
    exit 1
  fi
  echo "ethlet_$e.bin  $size bytes"
done
