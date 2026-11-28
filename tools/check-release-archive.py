#!/usr/bin/env python3
"""Checks a release archive from tools/package-release.sh: every entry name is printable
ASCII with "/" separators; README.md, LICENSE and CHANGELOG.md are at the top of the
archive's directory, and the binary (bin/m65, bin/m65.exe) in its bin/; "m65 install"
creates the commands.

Usage: check-release-archive.py ARCHIVE COMMAND...
"""
import sys, tarfile, zipfile

archive, commands = sys.argv[1], sys.argv[2:]
if archive.endswith(".zip"):
    names = zipfile.ZipFile(archive).namelist()
else:
    names = tarfile.open(archive).getnames()
bad = [n for n in names if "\\" in n or not all(0x20 <= ord(c) < 0x7f for c in n)]
if bad:
    sys.exit("bad entry names: " + ", ".join(repr(n) for n in bad))
# paths below the archive's top directory (m65-VERSION-PLATFORM/)
files = {n.rstrip("/").split("/", 1)[1] for n in names if "/" in n.rstrip("/")}
windows = archive.endswith(".zip")
want = {"README.md", "LICENSE", "CHANGELOG.md", "bin/m65.exe" if windows else "bin/m65"}
# only the binary: "m65 install" creates the m65.CMD commands (links, or launchers on Windows)
missing = sorted(want - files)
if missing:
    sys.exit("missing: " + ", ".join(missing))
print(f"{archive}: {len(names)} entries ok")
