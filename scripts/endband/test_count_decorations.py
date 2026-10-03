"""Tests for count_decorations.py against a synthetic 1.21 region file.

    python3 -m pytest scripts/endband/test_count_decorations.py

The region is built here from scratch (NBT writer + Anvil layout), so the parser, the palette unpacking,
the classification and the attachment read-back are covered without a Minecraft world.
"""
from __future__ import annotations

import os
import struct
import sys
import zlib
from collections import Counter

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import count_decorations as cd  # noqa: E402

AIR = {"Name": "minecraft:air"}
END_STONE = {"Name": "minecraft:end_stone"}
MOSS = {"Name": "betterend:end_moss"}
LOG = {"Name": "betterend:lacugrove_log"}


# --- a minimal NBT writer ---------------------------------------------------------------------------

def _s(text: str) -> bytes:
    b = text.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _tag_of(v) -> int:
    if isinstance(v, bool):
        return cd.TAG_BYTE
    if isinstance(v, int):
        return cd.TAG_INT
    if isinstance(v, str):
        return cd.TAG_STRING
    if isinstance(v, dict):
        return cd.TAG_COMPOUND
    if isinstance(v, LongArray):
        return cd.TAG_LONG_ARRAY
    if isinstance(v, Byte):
        return cd.TAG_BYTE
    if isinstance(v, list):
        return cd.TAG_LIST
    raise TypeError(type(v))


class LongArray(list):
    pass


class Byte(int):
    pass


def _payload(v) -> bytes:
    t = _tag_of(v)
    if t == cd.TAG_BYTE:
        return struct.pack(">b", int(v))
    if t == cd.TAG_INT:
        return struct.pack(">i", v)
    if t == cd.TAG_STRING:
        return _s(v)
    if t == cd.TAG_LONG_ARRAY:
        return struct.pack(">i", len(v)) + b"".join(struct.pack(">q", x) for x in v)
    if t == cd.TAG_LIST:
        elem = _tag_of(v[0]) if v else cd.TAG_END
        return bytes([elem]) + struct.pack(">i", len(v)) + b"".join(_payload(x) for x in v)
    if t == cd.TAG_COMPOUND:
        out = b""
        for k, x in v.items():
            out += bytes([_tag_of(x)]) + _s(k) + _payload(x)
        return out + bytes([cd.TAG_END])
    raise TypeError(t)


def nbt_root(compound: dict) -> bytes:
    return bytes([cd.TAG_COMPOUND]) + _s("") + _payload(compound)


# --- section + chunk builders ---------------------------------------------------------------------

def pack_indices(indices, palette_size) -> LongArray:
    bits = cd.bits_per_entry(palette_size)
    per_long = 64 // bits
    words = []
    for i in range(0, 4096, per_long):
        w = 0
        for j, idx in enumerate(indices[i:i + per_long]):
            w |= idx << (j * bits)
        if w >= 1 << 63:
            w -= 1 << 64
        words.append(w)
    return LongArray(words)


def section(y: int, palette, indices=None) -> dict:
    bs = {"palette": list(palette)}
    if indices is not None:
        bs["data"] = pack_indices(indices, len(palette))
    return {"Y": Byte(y), "block_states": bs}


def chunk(cx: int, cz: int, sections, status="minecraft:full", attachments=None) -> dict:
    root = {"xPos": cx, "zPos": cz, "Status": status, "sections": sections}
    if attachments:
        root["neoforge:attachments"] = attachments
    return root


def write_region(path: str, chunks) -> None:
    """Chunks are (cx, cz, rootDict); the file is r.<rx>.<rz>.mca for the first chunk's region."""
    locations = bytearray(4096)
    timestamps = bytes(4096)
    body = b""
    sector = 2
    for cx, cz, root in chunks:
        raw = zlib.compress(nbt_root(root))
        blob = struct.pack(">i", len(raw) + 1) + bytes([2]) + raw
        pad = (-len(blob)) % 4096
        blob += bytes(pad)
        n = len(blob) // 4096
        i = (cx & 31) + (cz & 31) * 32
        locations[i * 4:i * 4 + 3] = sector.to_bytes(3, "big")
        locations[i * 4 + 3] = n
        body += blob
        sector += n
    with open(path, "wb") as f:
        f.write(bytes(locations) + timestamps + body)


# --- the fixture world ------------------------------------------------------------------------------

def decorated_indices():
    """Flat end stone at local y 0..3, moss on y 4, one log column, air above."""
    idx = [0] * 4096  # palette: 0 air, 1 end_stone, 2 moss, 3 log
    for y in range(4):
        for z in range(16):
            for x in range(16):
                idx[(y * 16 + z) * 16 + x] = 1
    for z in range(16):
        for x in range(16):
            idx[(4 * 16 + z) * 16 + x] = 2
    for y in range(5, 12):
        idx[(y * 16 + 7) * 16 + 7] = 3
    return idx


def bare_indices():
    idx = [0] * 4096  # palette: 0 air, 1 end_stone
    for y in range(4):
        for z in range(16):
            for x in range(16):
                idx[(y * 16 + z) * 16 + x] = 1
    return idx


@pytest.fixture
def region_dir(tmp_path):
    d = tmp_path / "region"
    d.mkdir()
    decorated = chunk(2400, 2, [section(4, [AIR, END_STONE, MOSS, LOG], decorated_indices())])
    bare = chunk(2401, 2, [section(4, [AIR, END_STONE], bare_indices())])
    pending = chunk(2402, 2, [section(4, [AIR])], attachments={"dungeontrain:end_band_pending": Byte(1)})
    marker = chunk(2403, 2, [section(4, [END_STONE])],  # single-palette section: no data array
                   status="minecraft:features", attachments={"dungeontrain:end_band_sampled_cells": Byte(1)})
    write_region(str(d / "r.75.0.mca"), [(2400, 2, decorated), (2401, 2, bare), (2402, 2, pending), (2403, 2, marker)])
    return str(d)


# --- tests ----------------------------------------------------------------------------------------

def test_nbt_round_trip():
    root = {"a": 1, "b": "x", "c": {"d": Byte(1)}, "e": LongArray([1, -2]), "f": [{"Name": "minecraft:air"}]}
    parsed = cd.parse_nbt(nbt_root(root))
    assert parsed["a"] == 1 and parsed["b"] == "x" and parsed["c"]["d"] == 1
    assert parsed["e"] == [1, -2] and parsed["f"][0]["Name"] == "minecraft:air"


@pytest.mark.parametrize("size,bits", [(1, 4), (2, 4), (16, 4), (17, 5), (32, 5), (33, 6), (256, 8), (257, 9)])
def test_bits_per_entry(size, bits):
    assert cd.bits_per_entry(size) == bits


def test_section_counts_unpacks_palette():
    counts = cd.section_counts([AIR, END_STONE, MOSS, LOG], pack_indices(decorated_indices(), 4))
    assert counts["minecraft:end_stone"] == 4 * 256
    assert counts["betterend:end_moss"] == 256
    assert counts["betterend:lacugrove_log"] == 7
    assert counts["minecraft:air"] == 4096 - 4 * 256 - 256 - 7


def test_section_counts_single_palette_without_data():
    assert cd.section_counts([END_STONE], None) == Counter({"minecraft:end_stone": 4096})


def test_section_counts_z_clip():
    counts = cd.section_counts([AIR, END_STONE, MOSS, LOG], pack_indices(decorated_indices(), 4), (0, 15, 0, 7))
    assert counts["minecraft:end_stone"] == 4 * 128
    assert counts["betterend:lacugrove_log"] == 7   # the log column is at z 7


def test_census_classifies_chunks(region_dir):
    stats = cd.census(region_dir, 2400 * 16, 2403 * 16 + 15, 32, 47)
    assert sorted(stats) == [(2400, 2), (2401, 2), (2402, 2), (2403, 2)]
    assert cd.classify(stats[(2400, 2)]) == "decorated"
    assert cd.classify(stats[(2401, 2)]) == "bare"
    assert cd.classify(stats[(2402, 2)]) == "empty"
    assert cd.classify(stats[(2403, 2)]) == "bare"
    assert stats[(2402, 2)].pending and not stats[(2400, 2)].pending
    assert stats[(2403, 2)].sampled_marker and stats[(2403, 2)].status == "minecraft:features"
    assert stats[(2400, 2)].betterend == 256 + 7


def test_summary_and_slabs(region_dir):
    stats = cd.census(region_dir, 2400 * 16, 2403 * 16 + 15, 32, 47)
    s = cd.summary(stats)
    assert (s["chunks"], s["full"], s["decorated"], s["bare"], s["empty"]) == (4, 3, 1, 2, 1)
    assert (s["pending"], s["sampled_marker"]) == (1, 1)
    slabs = cd.slab_totals(stats)
    assert slabs[2400 * 16 + 7] == 4 * 16 + 16 + 7      # the log column's X
    assert slabs[2400 * 16 + 0] == 4 * 16 + 16
    assert slabs[2403 * 16 + 3] == 4096 // 16


def test_census_respects_x_range(region_dir):
    stats = cd.census(region_dir, 2401 * 16, 2402 * 16, 32, 47)
    assert sorted(stats) == [(2401, 2), (2402, 2)]


def test_compare_reports_differing_slabs(region_dir, tmp_path, capsys):
    other = tmp_path / "other"
    other.mkdir()
    # Same world but the decorated chunk lost its log column — one differing X slab.
    idx = decorated_indices()
    for y in range(5, 12):
        idx[(y * 16 + 7) * 16 + 7] = 0
    write_region(str(other / "r.75.0.mca"), [
        (2400, 2, chunk(2400, 2, [section(4, [AIR, END_STONE, MOSS, LOG], idx)])),
        (2401, 2, chunk(2401, 2, [section(4, [AIR, END_STONE], bare_indices())])),
    ])
    a = cd.census(region_dir, 2400 * 16, 2401 * 16 + 15, 32, 47)
    b = cd.census(str(other), 2400 * 16, 2401 * 16 + 15, 32, 47)
    assert cd.compare(a, b) == 1
    out = capsys.readouterr().out
    assert "1 of 32 slabs differ" in out
    assert "<-- differs" in out
