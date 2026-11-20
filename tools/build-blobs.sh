#!/usr/bin/env bash
# Builds the MEGA65-side binaries m65fs needs and copies them into
# src/main/resources/io/github/paw/m65fs/blobs/.
#
#   ethlet_echo.bin / .map            (Ophis)
#   ethlet_dma_load.bin / .map        (Ophis)
#   ethlet_all_done_basic2.bin / .map (Ophis)
#   remotesd_eth.prg                  (cc65 + mega65-libc)
#
# Everything is built out-of-tree in a temp dir; the mega65-tools checkout is only read.
#
# Usage: tools/build-blobs.sh [path-to-mega65-tools]   (default: ~/Labs/mega65/mega65-tools)
# Env:   CC65_PREFIX  prefix for cc65 tools, e.g. /opt/cc65/bin/
#                     (default: build the cc65 submodule pinned by mega65-tools into target/cc65;
#                      Fedora's cc65 2.19 segfaults on remotesd_eth.c)
set -euo pipefail

M65TOOLS=$(realpath "${1:-$HOME/Labs/mega65/mega65-tools}")
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT="$HERE/src/main/resources/io/github/paw/m65fs/blobs"
OPHIS="$M65TOOLS/Ophis/bin/ophis"

if [[ -z "${CC65_PREFIX:-}" ]]; then
  CC65_DIR="$HERE/target/cc65"
  if [[ ! -x "$CC65_DIR/bin/cl65" || ! -f "$CC65_DIR/lib/c64.lib" ]]; then
    echo "== building pinned cc65 into $CC65_DIR"
    rm -rf "$CC65_DIR"; mkdir -p "$HERE/target"
    cp -r "$M65TOOLS/cc65" "$CC65_DIR"
    make -C "$CC65_DIR" -j"$(nproc)" bin >/dev/null
    make -C "$CC65_DIR" -j"$(nproc)" lib TARGETS=c64 >/dev/null
  fi
  CC65_PREFIX="$CC65_DIR/bin/"
fi

[[ -x "$OPHIS" ]] || { echo "Ophis not found at $OPHIS (git submodule update --init Ophis?)" >&2; exit 1; }
command -v "${CC65_PREFIX}cl65" >/dev/null || { echo "cl65 not found" >&2; exit 1; }

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$OUT"

echo "== ethlets"
for e in echo dma_load all_done_basic2; do
  "$OPHIS" -4 --no-warn "$M65TOOLS/src/tools/etherload/ethlet_$e.a65" \
    -m "$BUILD/ethlet_$e.map" -o "$BUILD/ethlet_$e.bin"
  cp "$BUILD/ethlet_$e.bin" "$BUILD/ethlet_$e.map" "$OUT/"
done

echo "== mega65-libc (cc65)"
cp -r "$M65TOOLS/src/mega65-libc/cc65" "$BUILD/libc"
CA65="${CC65_PREFIX}ca65 --cpu 4510"
pushd "$BUILD/libc" >/dev/null
objs=()
for src in src/*.c src/*.s; do
  obj="${src%.*}.o"
  case "$src" in
    *.c) "${CC65_PREFIX}cc65" -O -t c64 -I include/ "$src" -o "${src%.c}.s.gen"
         $CA65 "${src%.c}.s.gen" -o "$obj" ;;
    *.s) $CA65 "$src" -o "$obj" ;;
  esac
  objs+=("$obj")
done
"${CC65_PREFIX}ar65" r libmega65.a "${objs[@]}"
popd >/dev/null

echo "== remotesd_eth.prg"
U="$M65TOOLS/src/utilities"
pushd "$BUILD" >/dev/null
"${CC65_PREFIX}cl65" --config "$U/remotesd_eth_cl65.cfg" -I "$BUILD/libc/include" -O -g \
  -o remotesd_eth.prg --mapfile remotesd_eth.map \
  "$U/remotesd_eth_asm.s" "$U/checksum.s" "$U/ip_checksum_recv.s" "$U/remotesd_eth.c" \
  "$BUILD/libc/libmega65.a"
cp remotesd_eth.prg "$OUT/"
popd >/dev/null

# Record provenance
{
  echo "mega65-tools: $(git -C "$M65TOOLS" describe --always --dirty 2>/dev/null || echo unknown)"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "cc65: $("${CC65_PREFIX}cl65" --version 2>&1 | head -1)"
  (cd "$OUT" && sha256sum ethlet_*.bin remotesd_eth.prg)
} > "$OUT/PROVENANCE.txt"

echo "== done"
ls -l "$OUT"
