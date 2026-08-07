#!/usr/bin/env python3
"""Stretch the portal corridor template to the length PortalCorridorSize asks for.

A portal corridor is longer than the carriage slot it is placed in — it runs past its own slot
into the cart between a portal's pair (see PortalCorridorSize.java). The shipped
`portal.nbt` was authored at exactly one carriage, and CarriagePlacer.sizeMatches rejects a
template whose recorded size is not the expected one, so a corridor asking for the longer box
would silently fall back to the built-in geometry and the authored art would vanish.

Rather than re-author it, this splices the corridor in the middle. The crossing zone between the
two baffles is a uniform stretch of walls, lantern floor and ceiling, so duplicating one of its
columns lengthens the corridor without touching either authored end: the doors, the baffles and
the run between them all land exactly where PortalCarriageLayout computes them for the new length.

    x:  0     1      2      3..L-4      L-3    L-2   L-1
       door  gap   baffle  CROSSING    baffle  gap  door
                           ^ duplicated here

Usage:
    python3 scripts/portal/stretch-portal-template.py [--check]

`--check` reports what would change without writing, which is what CI wants.
"""

import argparse
import gzip
import struct
import sys
from pathlib import Path

TEMPLATE = (Path(__file__).resolve().parents[2]
            / "src/main/resources/data/dungeontrain/templates/portal.nbt")

# Tag ids, per the NBT spec.
END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, \
    INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    """An NBT value that remembers its own tag id, so a read/write round-trip is byte-exact."""

    def __init__(self, tag_id, value):
        self.id = tag_id
        self.value = value


# ── read ────────────────────────────────────────────────────────────────────

class Reader:
    def __init__(self, data):
        self.data = data
        self.at = 0

    def take(self, n):
        chunk = self.data[self.at:self.at + n]
        if len(chunk) != n:
            raise ValueError(f"truncated NBT at byte {self.at}")
        self.at += n
        return chunk

    def num(self, fmt, n):
        return struct.unpack(fmt, self.take(n))[0]

    def string(self):
        return self.take(self.num(">H", 2)).decode("utf-8")

    def payload(self, tag_id):
        if tag_id == BYTE:
            return Tag(tag_id, self.num(">b", 1))
        if tag_id == SHORT:
            return Tag(tag_id, self.num(">h", 2))
        if tag_id == INT:
            return Tag(tag_id, self.num(">i", 4))
        if tag_id == LONG:
            return Tag(tag_id, self.num(">q", 8))
        if tag_id == FLOAT:
            return Tag(tag_id, self.num(">f", 4))
        if tag_id == DOUBLE:
            return Tag(tag_id, self.num(">d", 8))
        if tag_id == BYTE_ARRAY:
            return Tag(tag_id, self.take(self.num(">i", 4)))
        if tag_id == STRING:
            return Tag(tag_id, self.string())
        if tag_id == LIST:
            item_id = self.num(">b", 1)
            count = self.num(">i", 4)
            return Tag(tag_id, (item_id, [self.payload(item_id) for _ in range(count)]))
        if tag_id == COMPOUND:
            entries = []
            while True:
                child_id = self.num(">b", 1)
                if child_id == END:
                    return Tag(tag_id, entries)
                entries.append((self.string(), self.payload(child_id)))
        if tag_id in (INT_ARRAY, LONG_ARRAY):
            width, fmt = (4, ">i") if tag_id == INT_ARRAY else (8, ">q")
            return Tag(tag_id, [self.num(fmt, width) for _ in range(self.num(">i", 4))])
        raise ValueError(f"unknown NBT tag id {tag_id}")


def read(data):
    r = Reader(data)
    tag_id = r.num(">b", 1)
    return r.string(), r.payload(tag_id)


# ── write ───────────────────────────────────────────────────────────────────

def write_string(out, s):
    raw = s.encode("utf-8")
    out += struct.pack(">H", len(raw))
    out += raw


def write_payload(out, tag):
    if tag.id == BYTE:
        out += struct.pack(">b", tag.value)
    elif tag.id == SHORT:
        out += struct.pack(">h", tag.value)
    elif tag.id == INT:
        out += struct.pack(">i", tag.value)
    elif tag.id == LONG:
        out += struct.pack(">q", tag.value)
    elif tag.id == FLOAT:
        out += struct.pack(">f", tag.value)
    elif tag.id == DOUBLE:
        out += struct.pack(">d", tag.value)
    elif tag.id == BYTE_ARRAY:
        out += struct.pack(">i", len(tag.value))
        out += tag.value
    elif tag.id == STRING:
        write_string(out, tag.value)
    elif tag.id == LIST:
        item_id, items = tag.value
        out += struct.pack(">b", item_id)
        out += struct.pack(">i", len(items))
        for item in items:
            write_payload(out, item)
    elif tag.id == COMPOUND:
        for name, child in tag.value:
            out += struct.pack(">b", child.id)
            write_string(out, name)
            write_payload(out, child)
        out += struct.pack(">b", END)
    elif tag.id in (INT_ARRAY, LONG_ARRAY):
        fmt = ">i" if tag.id == INT_ARRAY else ">q"
        out += struct.pack(">i", len(tag.value))
        for n in tag.value:
            out += struct.pack(fmt, n)
    else:
        raise ValueError(f"unknown NBT tag id {tag.id}")


def write(name, tag):
    out = bytearray()
    out += struct.pack(">b", tag.id)
    write_string(out, name)
    write_payload(out, tag)
    return bytes(out)


# ── compound helpers ────────────────────────────────────────────────────────

def get(compound, key):
    for name, child in compound.value:
        if name == key:
            return child
    raise KeyError(f"no '{key}' in compound")


def put(compound, key, tag):
    compound.value = [(name, tag if name == key else child)
                      for name, child in compound.value]


# ── the stretch itself ──────────────────────────────────────────────────────

def overrun(carriage_length):
    """PortalCorridorSize.overrun — half the cart, rounded down. MAX_LENGTH is 32."""
    return max(0, min((carriage_length - 1) // 2, 32 - carriage_length))


def stretch(root, carriage_length):
    """Lengthen `root` in place to the corridor length `carriage_length` implies.

    Idempotent: a template already at the target length is left untouched, so this can be re-run
    (and checked in CI) without stretching an already-stretched corridor a second time.
    """
    size = get(root, "size")
    length, height, width = (t.value for t in size.value[1])

    target = carriage_length + overrun(carriage_length)
    if length == target:
        return None, length, target
    if length != carriage_length:
        raise SystemExit(
            f"template is {length} long — expected either the carriage length {carriage_length} "
            f"or the corridor length {target}; refusing to guess")

    grow = target - length

    # A column strictly inside the crossing zone (baffles sit at 2 and length-3), so the copies
    # carry only wall / lantern-floor / ceiling and never a door or a baffle.
    source_x = length // 2
    if not (2 < source_x < length - 3):
        raise SystemExit(f"length {length} has no clear crossing column to duplicate")

    blocks_id, blocks = get(root, "blocks").value

    # Snapshot the source column BEFORE shifting, or the copies would pick up shifted positions.
    column = [block for block in blocks
              if get(block, "pos").value[1][0].value == source_x]

    for block in blocks:
        entries = get(block, "pos").value[1]
        if entries[0].value > source_x:
            entries[0] = Tag(INT, entries[0].value + grow)

    for step in range(1, grow + 1):
        for block in column:
            copy = clone(block)
            get(copy, "pos").value[1][0] = Tag(INT, source_x + step)
            blocks.append(copy)

    put(root, "blocks", Tag(LIST, (blocks_id, blocks)))
    put(root, "size", Tag(LIST, (size.value[0], [
        Tag(INT, target), Tag(INT, height), Tag(INT, width)])))
    return root, length, target


def clone(tag):
    if tag.id == COMPOUND:
        return Tag(COMPOUND, [(name, clone(child)) for name, child in tag.value])
    if tag.id == LIST:
        item_id, items = tag.value
        return Tag(LIST, (item_id, [clone(item) for item in items]))
    if tag.id in (INT_ARRAY, LONG_ARRAY):
        return Tag(tag.id, list(tag.value))
    return Tag(tag.id, tag.value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="report without writing")
    parser.add_argument("--carriage-length", type=int, default=9,
                        help="the world's carriage length the corridor is derived from "
                             "(CarriageDims.DEFAULT_LENGTH)")
    args = parser.parse_args()

    name, root = read(gzip.open(TEMPLATE, "rb").read())
    result, was, now = stretch(root, args.carriage_length)

    if result is None:
        print(f"{TEMPLATE.name}: already {was} long, nothing to do")
        return 0

    print(f"{TEMPLATE.name}: {was} -> {now} blocks long"
          f" ({len(get(result, 'blocks').value[1])} blocks)")
    if args.check:
        return 0

    with gzip.open(TEMPLATE, "wb") as out:
        out.write(write(name, result))
    print(f"wrote {TEMPLATE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
