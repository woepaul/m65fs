#!/usr/bin/env bash
# Prints the CHANGELOG.md section of a release: from the heading "## VERSION" (followed by
# anything, e.g. a date) up to the next "## " heading. Fails if there is no such section, so a
# release can't go out without its changelog entry.
#
# Usage: tools/release-notes.sh VERSION
set -euo pipefail

V=${1:?usage: tools/release-notes.sh VERSION}
HERE=$(cd "$(dirname "$0")/.." && pwd)
notes=$(awk -v v="$V" '
  /^## / { if (found) exit; if ($2 == v) { found = 1; next } }
  found { print }
' "$HERE/CHANGELOG.md")
if [[ -z "${notes//[[:space:]]/}" ]]; then
  echo "CHANGELOG.md has no section \"## $V\"" >&2
  exit 1
fi
printf '%s\n' "$notes"
