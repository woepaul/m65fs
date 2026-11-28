#!/usr/bin/env bash
# Installs the m65 tools: the native binary as DIR/m65, the links DIR/m65.ls, m65.cp, ... and bash
# completion (by "m65 install", see m65 help)
#   tools/install.sh [DIR]          (default ~/.local/bin; builds the native binary first)
#   tools/install.sh --jvm [DIR]    no GraalVM: link to bin/m65, which runs the jar (Java 25+)
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
JVM=0
if [[ "${1:-}" == "--jvm" ]]; then
  JVM=1
  shift
fi
DIR=${1:-$HOME/.local/bin}
mkdir -p "$DIR"

if [[ $JVM == 1 ]]; then
  ln -sf "$HERE/bin/m65" "$DIR/m65"
  COMMANDS=()                     # (no mapfile: macOS ships bash 3.2)
  while IFS= read -r c; do COMMANDS+=("${c//$'\r'/}"); done < <("$DIR/m65" --list-commands)
  [[ ${#COMMANDS[@]} -gt 0 ]] || { echo "$DIR/m65 --list-commands gave no commands" >&2; exit 1; }
  for c in "${COMMANDS[@]}"; do
    ln -sf m65 "$DIR/m65.$c"
  done
  echo "installed m65 and m65.{$(IFS=,; echo "${COMMANDS[*]}")} in $DIR (running the jar)"
else
  # the binary installs itself: m65, the m65.* links and bash completion (m65 uninstall: away again)
  "$HERE/tools/build-native.sh"
  "$HERE/target/native/m65" install "$DIR"
fi
