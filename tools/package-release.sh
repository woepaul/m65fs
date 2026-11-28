#!/usr/bin/env bash
# Packages the native binary built by tools/build-native.sh for a release:
#   target/dist/m65-VERSION-linux-amd64.tar.gz      m65 (m65 install creates the commands)
#   target/dist/m65-VERSION-linux-aarch64.tar.gz
#   target/dist/m65-VERSION-macos-aarch64.tar.gz
#   target/dist/m65-VERSION-windows-amd64.zip       m65.exe (m65 install creates the commands)
# each in a directory m65-VERSION-PLATFORM/ with the binary, README.md, LICENSE and CHANGELOG.md.
#
# Usage: tools/package-release.sh VERSION   (prints the archive's path)
set -euo pipefail

V=${1:?usage: tools/package-release.sh VERSION}
HERE=$(cd "$(dirname "$0")/.." && pwd)
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)        P=linux-amd64 ;;
  Linux-aarch64)       P=linux-aarch64 ;;
  Darwin-arm64)        P=macos-aarch64 ;;
  Darwin-x86_64)       P=macos-amd64 ;;
  MINGW*|MSYS*|CYGWIN*) P=windows-amd64 ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

NAME="m65-$V-$P"
DIST="$HERE/target/dist"
STAGE="$DIST/$NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$HERE/README.md" "$HERE/LICENSE" "$HERE/CHANGELOG.md" "$STAGE/"

if [[ $P == windows-* ]]; then
  cp "$HERE/target/native/m65.exe" "$STAGE/"   # the commands: m65.exe install
  rm -f "$DIST/$NAME.zip"
  if command -v 7z >/dev/null; then   # standard "/" separators (Compress-Archive writes "\")
    (cd "$DIST" && 7z a -tzip "$NAME.zip" "$NAME" >/dev/null)
  else
    (cd "$DIST" && powershell -NoProfile -Command "Compress-Archive -Path '$NAME' -DestinationPath '$NAME.zip'")
  fi
  echo "$DIST/$NAME.zip"
else
  cp "$HERE/target/native/m65" "$STAGE/"   # the commands: ./m65 install
  tar -C "$DIST" -czf "$DIST/$NAME.tar.gz" "$NAME"
  echo "$DIST/$NAME.tar.gz"
fi
