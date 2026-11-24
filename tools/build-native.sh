#!/usr/bin/env bash
# Builds the native m65 binary with GraalVM native-image and links the m65.* commands to it:
#   target/native/m65, target/native/m65.ls -> m65, m65.cp -> m65, ...
#
# GraalVM: $GRAALVM_HOME, else the copy cached in ~/.cache/m65fs (downloaded and checksum-verified
# on first use, GraalVM Community for JDK 25).
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
VERSION=25.0.2
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/m65fs"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)  PLATFORM=linux-x64 ;;
  Linux-aarch64) PLATFORM=linux-aarch64 ;;
  Darwin-arm64)  PLATFORM=macos-aarch64 ;;
  Darwin-x86_64) PLATFORM=macos-x64 ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

if [[ -z "${GRAALVM_HOME:-}" ]]; then
  GRAALVM_HOME=$(ls -d "$CACHE"/graalvm-community-openjdk-"$VERSION"*/ 2>/dev/null | head -1 || true)
  GRAALVM_HOME=${GRAALVM_HOME%/}
  if [[ -n "$GRAALVM_HOME" && -d "$GRAALVM_HOME/Contents/Home" ]]; then
    GRAALVM_HOME="$GRAALVM_HOME/Contents/Home"
  fi
  if [[ -z "$GRAALVM_HOME" ]]; then
    F="graalvm-community-jdk-${VERSION}_${PLATFORM}_bin.tar.gz"
    U="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-$VERSION"
    echo "== downloading GraalVM CE $VERSION into $CACHE"
    mkdir -p "$CACHE"
    curl -sSfL -o "$CACHE/$F" "$U/$F"
    curl -sSfL -o "$CACHE/$F.sha256" "$U/$F.sha256"
    (cd "$CACHE" && echo "$(cat "$F.sha256")  $F" | sha256sum -c - >/dev/null)
    tar xzf "$CACHE/$F" -C "$CACHE"
    exec "$0" "$@"
  fi
fi
NI="$GRAALVM_HOME/bin/native-image"
[[ -x "$NI" ]] || { echo "native-image not found in $GRAALVM_HOME" >&2; exit 1; }

echo "== jar"
(cd "$HERE" && ./mvnw -q -DskipTests package)
JAR=$(ls "$HERE"/target/m65fs-*.jar | grep -v -e sources -e javadoc | head -1)

echo "== native-image ($("$NI" --version | head -1))"
OUT="$HERE/target/native"
mkdir -p "$OUT"
"$NI" -jar "$JAR" -o "$OUT/m65" -march=compatibility "$@"

# The 6502 blobs must be embedded (META-INF/native-image/.../reachability-metadata.json).
if command -v python3 >/dev/null; then
  python3 - "$OUT/m65" "$HERE/src/main/resources/io/github/paw/m65fs/blobs" <<'PY'
import os, sys
binary = open(sys.argv[1], 'rb').read()
missing = [f for f in sorted(os.listdir(sys.argv[2]))
           if open(os.path.join(sys.argv[2], f), 'rb').read() not in binary]
if missing:
    sys.exit("resources missing from the native image: " + ", ".join(missing))
print("== all", len(os.listdir(sys.argv[2])), "blobs embedded")
PY
fi

mapfile -t COMMANDS < <("$OUT/m65" --list-commands)
[[ ${#COMMANDS[@]} -gt 0 ]] || { echo "$OUT/m65 --list-commands gave no commands" >&2; exit 1; }
for c in "${COMMANDS[@]}"; do
  ln -sf m65 "$OUT/m65.$c"
done
echo "== done: $OUT/m65 and m65.{$(IFS=,; echo "${COMMANDS[*]}")}"
