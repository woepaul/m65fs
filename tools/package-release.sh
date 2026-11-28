#!/usr/bin/env bash
# Packages the native binary built by tools/build-native.sh for a release:
#   target/dist/m65-VERSION-linux-amd64.tar.gz      m65 (m65 install creates the commands)
#   target/dist/m65-VERSION-linux-aarch64.tar.gz
#   target/dist/m65-VERSION-macos-aarch64.tar.gz
#   target/dist/m65-VERSION-windows-amd64.zip       m65.exe (m65 install creates the commands)
# each in a directory m65-VERSION-PLATFORM/ with the binary, INSTALL.txt, README.md, LICENSE and
# CHANGELOG.md.
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

# INSTALL.txt: the few steps for this platform (Windows: CRLF, for Notepad)
install_txt() {
  case $P in
    windows-*) cat <<EOF
m65 $V for Windows: the MEGA65's SD card and machine over Ethernet

Install: open PowerShell in this folder and run

.\m65.exe install

-----
This copies m65.exe to %LOCALAPPDATA%\Programs\m65 and creates the commands
there (m65.ls.exe, m65.cp.exe, ...). If that folder is not on your PATH yet,
it prints the one line that adds it; run that, then open a new terminal.
Then try:  m65.ls

Elsewhere:   .\m65.exe install C:\some\folder
Uninstall:   m65 uninstall
More:        m65 help, and README.md
EOF
      ;;
    *) cat <<EOF
m65 $V: the MEGA65's SD card and machine over Ethernet

Install: in this directory run

./m65 install

-----
This copies m65 to ~/.local/bin, creates the commands there (m65.ls, m65.cp,
...) and installs bash tab completion. If ~/.local/bin is not on your PATH
yet, it prints the one line that adds it; run that, then open a new terminal.
Then try:  m65.ls
EOF
      [[ $P == macos-* ]] && cat <<'EOF'

macOS: the binary isn't signed. If it is blocked, allow it with
xattr -d com.apple.quarantine m65
EOF
      cat <<'EOF'

Elsewhere:   ./m65 install DIR      (--no-completion: without tab completion)
Uninstall:   m65 uninstall
More:        m65 help, and README.md
EOF
      ;;
  esac
}
if [[ $P == windows-* ]]; then
  install_txt | sed 's/$/\r/' > "$STAGE/INSTALL.txt"
else
  install_txt > "$STAGE/INSTALL.txt"
fi

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
