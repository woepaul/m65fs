#!/usr/bin/env bash
# Builds the native m65 binary with GraalVM native-image: target/native/m65 (Windows, in Git Bash:
# target/native/m65.exe). "m65 install" then sets up the m65.* commands and completion.
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
  MINGW*|MSYS*|CYGWIN*) PLATFORM=windows-x64 ;;
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
    SHA=sha256sum; command -v sha256sum >/dev/null || SHA="shasum -a 256"   # macOS
    (cd "$CACHE" && echo "$(cat "$F.sha256")  $F" | $SHA -c - >/dev/null)
    tar xzf "$CACHE/$F" -C "$CACHE"
    exec "$0" "$@"
  fi
fi
NI=""
for n in native-image native-image.cmd native-image.exe; do   # (.cmd on Windows)
  [[ -f "$GRAALVM_HOME/bin/$n" ]] && { NI="$GRAALVM_HOME/bin/$n"; break; }
done
[[ -n "$NI" ]] || { echo "native-image not found in $GRAALVM_HOME/bin" >&2; exit 1; }

echo "== jar"
(cd "$HERE" && ./mvnw -q -DskipTests package)
JAR=$(ls "$HERE"/target/m65fs-*.jar | grep -v -e sources -e javadoc | head -1)

OUT="$HERE/target/native"
mkdir -p "$OUT"
PY=python3; command -v python3 >/dev/null || PY=python

# The files "m65 install" and "m65 completion" write go into the image as one zip resource
# (io/github/paw/m65fs/install.zip, see InstallFiles), with only what this platform needs:
# the bash completion script everywhere (also for Git Bash), and on Windows the launcher.
# The launcher (src/main/windows/launcher.c, kernel32 only, a few KB) runs "m65.exe CMD ARGS"
# for m65.CMD.exe: Windows has no exec(), and unlike a .cmd wrapper it asks no "Terminate batch
# job (Y/N)?" on Ctrl-C; it switches the console to UTF-8 for the command (m65 writes UTF-8; the
# console's code page, usually 850 or 437, turned "SHIFT+£" into "SHIFT+┬ú") and passes on the
# exit status.
INSTALL_FILES=("$HERE/src/main/resources/io/github/paw/m65fs/install/m65.bash")
if [[ $PLATFORM == windows-* ]]; then
  echo "== launcher"
  rm -f "$OUT"/m65.*.cmd "$OUT"/m65.*.exe
  cp "$HERE/src/main/windows/launcher.c" "$OUT/"
  # (cl links by itself: Git Bash's /usr/bin/link would shadow MSVC's link.exe; "-" options,
  # because MSYS rewrites arguments starting with "/" as paths)
  (cd "$OUT" && cl -nologo -O1 -GS- -Zl -W3 launcher.c \
      -link -entry:start -subsystem:console -nodefaultlib -opt:ref kernel32.lib -out:launcher.exe >/dev/null)
  rm -f "$OUT/launcher.c" "$OUT/launcher.obj"
  INSTALL_FILES+=("$OUT/launcher.exe")
fi
cp "$JAR" "$OUT/m65-image.jar"
JAR="$OUT/m65-image.jar"
$PY - "$JAR" "${INSTALL_FILES[@]}" <<'PYZIP'
import io, os, sys, zipfile
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sys.argv[2:]:
        z.write(f, os.path.basename(f))
with zipfile.ZipFile(sys.argv[1], "a") as jar:
    jar.writestr("io/github/paw/m65fs/install.zip", buf.getvalue())
print("== install.zip:", ", ".join(os.path.basename(f) for f in sys.argv[2:]), f"({len(buf.getvalue())} bytes)")
PYZIP

echo "== native-image ($("$NI" --version | head -1))"
"$NI" -jar "$JAR" -o "$OUT/m65" -march=compatibility "$@"

BIN="$OUT/m65"
[[ $PLATFORM == windows-* ]] && BIN="$OUT/m65.exe"

# The MEGA65 blobs must be embedded (META-INF/native-image/.../reachability-metadata.json).
if command -v $PY >/dev/null; then
  $PY - "$BIN" "$HERE/src/main/resources/io/github/paw/m65fs/blobs" <<'PY'
import os, sys
binary = open(sys.argv[1], 'rb').read()
missing = [f for f in sorted(os.listdir(sys.argv[2]))
           if open(os.path.join(sys.argv[2], f), 'rb').read() not in binary]
if missing:
    sys.exit("resources missing from the native image: " + ", ".join(missing))
print("== all", len(os.listdir(sys.argv[2])), "blobs embedded")
PY
fi

rm -f "$OUT/m65-image.jar"
[[ $PLATFORM == windows-* ]] && rm -f "$OUT/launcher.exe"
echo "== done: $BIN (\"m65 install\" sets up the m65.* commands and completion)"
