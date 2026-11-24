#!/usr/bin/env bash
# Installs the m65 tools: the native binary as DIR/m65 and the links DIR/m65.ls, m65.cp, ...
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
else
  "$HERE/tools/build-native.sh"
  install -m 755 "$HERE/target/native/m65" "$DIR/m65"
fi
mapfile -t COMMANDS < <("$DIR/m65" --list-commands)
[[ ${#COMMANDS[@]} -gt 0 ]] || { echo "$DIR/m65 --list-commands gave no commands" >&2; exit 1; }
for c in "${COMMANDS[@]}"; do
  ln -sf m65 "$DIR/m65.$c"
done
echo "installed m65 and m65.{$(IFS=,; echo "${COMMANDS[*]}")} in $DIR"
case ":$PATH:" in
  *":$DIR:"*) ;;
  *) echo "note: $DIR is not on your PATH" ;;
esac
