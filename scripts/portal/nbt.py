"""Minimal NBT read/write for the portal template scripts.

Structure NBT is a small, closed format — a compound of `size`, `blocks`, `palette`, `entities` and
`DataVersion` — so a full library is overkill, but the round-trip has to be exact: these scripts
rewrite shipped game data, and a codec that quietly dropped a tag type would corrupt it silently.
Every value therefore carries its own tag id (see `Tag`) rather than being mapped onto a Python type
and guessed at on the way out.
"""

import struct

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


def clone(tag):
    """A deep copy — the container tags are rebuilt so a mutated copy cannot reach the original."""
    if tag.id == COMPOUND:
        return Tag(COMPOUND, [(name, clone(child)) for name, child in tag.value])
    if tag.id == LIST:
        item_id, items = tag.value
        return Tag(LIST, (item_id, [clone(item) for item in items]))
    if tag.id in (INT_ARRAY, LONG_ARRAY):
        return Tag(tag.id, list(tag.value))
    return Tag(tag.id, tag.value)
