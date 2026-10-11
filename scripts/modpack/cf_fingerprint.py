#!/usr/bin/env python3
"""CurseForge file fingerprint — the Python twin of ``cheat/CurseForgeFingerprint.java``.

CurseForge identifies a file by MurmurHash2 (32-bit, Austin Appleby's original) over the file with
every whitespace byte removed (tab 9, LF 10, CR 13, space 32), seed 1. ``POST /v1/fingerprints``
matches on it, which is how the relay can ask "which published file is this?" for a jar Modrinth
has never seen.

This is the reference the Java unit-test fixtures are generated from (``CurseForgeFingerprintTest``),
and what ``check-mod-ids.py`` records alongside each pinned jar's sha1.

Usage:  python3 scripts/modpack/cf_fingerprint.py <file>...      prints "<fingerprint>  <sha1>  <file>"
        python3 scripts/modpack/cf_fingerprint.py --fixtures      prints the JSON the Java test embeds
"""
import hashlib
import json
import sys

M = 0x5BD1E995
R = 24
SEED = 1
MASK = 0xFFFFFFFF
WHITESPACE = frozenset((9, 10, 13, 32))


def normalize(data: bytes) -> bytes:
    return bytes(b for b in data if b not in WHITESPACE)


def murmur2(data: bytes, seed: int = SEED) -> int:
    length = len(data)
    h = (seed ^ length) & MASK
    i = 0
    while length - i >= 4:
        k = int.from_bytes(data[i:i + 4], "little")
        k = (k * M) & MASK
        k ^= k >> R
        k = (k * M) & MASK
        h = (h * M) & MASK
        h ^= k
        i += 4
    tail = length - i
    if tail >= 3:
        h ^= data[i + 2] << 16
    if tail >= 2:
        h ^= data[i + 1] << 8
    if tail >= 1:
        h ^= data[i]
        h = (h * M) & MASK
    h ^= h >> 13
    h = (h * M) & MASK
    h ^= h >> 15
    return h


def fingerprint(data: bytes) -> int:
    """CurseForge's fingerprint of a file's bytes."""
    return murmur2(normalize(data))


FIXTURES = [
    b"",
    b"a",
    b"ab",
    b"abc",
    b"abcd",
    b"hello world",            # the space is stripped before hashing
    b"line one\nline two\r\n\tdone",
    bytes(range(256)),
    bytes(range(256)) * 3 + b"xyz",
]


def main(argv):
    if argv and argv[0] == "--fixtures":
        print(json.dumps([{"hex": d.hex(), "fingerprint": fingerprint(d)} for d in FIXTURES], indent=2))
        return 0
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    for path in argv:
        with open(path, "rb") as f:
            data = f.read()
        print(f"{fingerprint(data)}  {hashlib.sha1(data).hexdigest()}  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
