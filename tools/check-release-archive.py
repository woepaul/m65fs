#!/usr/bin/env python3
"""Checks a release archive from tools/package-release.sh: every entry name is printable
ASCII with "/" separators, and the binary, README, LICENSE, CHANGELOG and one entry per
command (m65.CMD links, or m65.CMD.cmd wrappers on Windows) are there.

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
files = {n.rstrip("/").split("/")[-1] for n in names}
windows = archive.endswith(".zip")
want = {"README.md", "LICENSE", "CHANGELOG.md", "m65.exe" if windows else "m65"}
want |= {f"m65.{c}.cmd" if windows else f"m65.{c}" for c in commands}
missing = sorted(want - files)
if missing:
    sys.exit("missing: " + ", ".join(missing))
print(f"{archive}: {len(names)} entries ok")
