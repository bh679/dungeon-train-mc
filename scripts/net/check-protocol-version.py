#!/usr/bin/env python3
"""Fail when a PR changes a packet's wire layout without bumping DungeonTrainNet.PROTOCOL_VERSION.

PROTOCOL_VERSION (src/main/java/games/brennan/dungeontrain/net/DungeonTrainNet.java) is the string
NeoForge's payload handshake compares between client and server. It is the ONLY guard that keeps an
older client off a newer server: NeoForge negotiates payload *types*, not their layouts, so a packet
that gains a field still handshakes fine and then blows up (`readBoolean()` past the end of the buffer
-> IndexOutOfBoundsException -> mid-session kick). PR #1449 shipped exactly that: a trailing
`boolean centre` on EditorPlotActionPacket with PROTOCOL_VERSION left at "72".

This check diffs <base>...<head> over the net/ package and looks for added/removed lines that touch
the codec idioms used there: buf.readX/buf.writeX (and the `b` lambda receiver inside readList /
writeCollection), ByteBufCodecs.X including the lowercase factories (byteArray, list, optional…),
Foo.STREAM_CODEC used as a composite argument or via .encode/.decode, and StreamCodec.of/composite/unit.
Any hit in a file other than DungeonTrainNet.java means the wire layout may have changed, and the diff
must then also change the PROTOCOL_VERSION line. Comment-only, handler-only and refactor edits don't
trip it — including the `public static final StreamCodec<…> STREAM_CODEC =` declaration line itself,
which has no leading dot. Fixture tests: scripts/net/test_check_protocol_version.py.

A bump is always safe; a missed one is not — so there is no opt-out. If a flagged diff is genuinely
wire-compatible, bump anyway.

Usage:
    python3 scripts/net/check-protocol-version.py [--base origin/main] [--head HEAD]
CI (build.yml `net-checks`) passes --base origin/${{ github.base_ref }} on pull requests.
Stdlib only. Exit 0 = ok, 1 = bump missing, 2 = git failed.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
NET_DIR = "src/main/java/games/brennan/dungeontrain/net/"
REGISTRAR = NET_DIR + "DungeonTrainNet.java"

# Codec idioms surveyed across the package — anything that reads or writes the byte stream.
WIRE_LINE = re.compile(
    r"\b(?:buf|b)\.(?:read|write)[A-Za-z]*\("
    r"|\bByteBufCodecs\.[A-Za-z_]+"
    r"|\.STREAM_CODEC\b"
    r"|\bStreamCodec\.[a-z][A-Za-z]*\("
)
VERSION_LINE = re.compile(r'PROTOCOL_VERSION\s*=\s*"[^"]*"')


def git_diff(base: str, head: str, *paths: str) -> str:
    cmd = ["git", "diff", "-U0", "--no-color", f"{base}...{head}", "--", *paths]
    try:
        return subprocess.run(cmd, cwd=REPO, check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as e:
        sys.stderr.write(f"error: {' '.join(cmd)}\n{e.stderr}")
        sys.exit(2)


def changed_lines_by_file(diff: str) -> dict[str, list[str]]:
    """Map each file in a unified diff to its added/removed content lines (no +++/--- headers)."""
    files: dict[str, list[str]] = {}
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[len("+++ b/"):]
            files.setdefault(current, [])
        elif line.startswith("+++ /dev/null"):
            current = None  # deleted file: nothing on the wire any more; a deletion is a registrar edit anyway
        elif current and (line.startswith("+") or line.startswith("-")) and not line.startswith(("+++", "---")):
            files[current].append(line)
    return files


def wire_hits(files: dict[str, list[str]]) -> dict[str, list[str]]:
    hits = {}
    for path, lines in files.items():
        if path == REGISTRAR:
            continue
        matched = [l for l in lines if WIRE_LINE.search(l)]
        if matched:
            hits[path] = matched
    return hits


def version_bumped(files: dict[str, list[str]]) -> bool:
    return any(VERSION_LINE.search(l) for l in files.get(REGISTRAR, []))


def check(diff: str) -> tuple[dict[str, list[str]], bool]:
    """Pure verdict over a unified diff: (wire-touching lines per packet file, PROTOCOL_VERSION changed)."""
    files = changed_lines_by_file(diff)
    return wire_hits(files), version_bumped(files)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default="origin/main", help="ref the PR merges into (default origin/main)")
    ap.add_argument("--head", default="HEAD", help="ref under review (default HEAD)")
    args = ap.parse_args()

    hits, bumped = check(git_diff(args.base, args.head, NET_DIR))
    if not hits:
        print(f"ok: no packet wire-layout changes in {NET_DIR} ({args.base}...{args.head})")
        return 0
    if bumped:
        print(f"ok: {len(hits)} packet file(s) changed wire layout and PROTOCOL_VERSION was bumped")
        return 0

    print(f"FAIL: packet wire layout changed without bumping PROTOCOL_VERSION in {REGISTRAR}\n")
    for path, lines in sorted(hits.items()):
        print(f"  {path}")
        for l in lines[:6]:
            print(f"      {l.rstrip()}")
        if len(lines) > 6:
            print(f"      … {len(lines) - 6} more")
    print(
        "\nAn older client would still pass the login handshake and then be kicked mid-session when the\n"
        "server decodes the new layout. Bump the string in DungeonTrainNet.java:\n"
        '    public static final String PROTOCOL_VERSION = "<n+1>";\n'
        "(a bump is always safe; if this diff is genuinely wire-compatible, bump anyway)."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
