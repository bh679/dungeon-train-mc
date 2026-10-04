#!/usr/bin/env python3
"""Per-chunk decoration census of an End-band strip, straight from the region files.

Dependency-free Anvil (.mca) + NBT reader for Minecraft 1.21 chunks. For every chunk in the requested
block range it reports the chunk status, the non-air block count, how many of those are End ground
(end stone / chorus), how many are BetterEnd blocks, what else is there, and whether the chunk still
carries Dungeon Train's `end_band_pending` flag or the `end_band_sampled_cells` marker — the two
attachments issue #1785 asks about.

    count_decorations.py run/world/region --x 38400 39423 --z 32 95            # table + summary
    count_decorations.py run/world/region --x ... --z ... --csv > a.csv        # machine-readable
    count_decorations.py --compare A/region B/region --x ... --z ...            # A/B per-chunk + per-X-slab diff
    count_decorations.py run/world/region --x ... --z ... --count-full         # just the FULL-chunk count (polling)

A chunk is classed **bare** when it has End ground but nothing else, **ore-only** when the only
BetterEnd blocks on it are the ores BetterEnd injects into vanilla's End biomes, and the per-chunk
`vanilla_end_quarts` counts section palette entries naming a vanilla End biome — a BetterEnd band
generated with endBandBetterEndOnly (#1785) should show 0 of each.

The pure pieces (`parse_nbt`, `section_counts`, `classify`, `slab_totals`) are unit-tested in
`test_count_decorations.py` against a synthetic region file.
"""
from __future__ import annotations

import argparse
import gzip
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Dict, Iterator, List, Optional, Tuple

# --- NBT ------------------------------------------------------------------------------------------

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE = range(7)
TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(7, 13)

# Vanilla's End biomes. BetterEnd's End map keeps them beside its own; end_barrens and small_end_islands
# place nothing, which is what #1785's bare chunks turned out to be. The band's remap (endBandBetterEndOnly)
# should leave none of these in a BetterEnd band.
VANILLA_END_BIOMES = frozenset({
    "minecraft:the_end", "minecraft:end_highlands", "minecraft:end_midlands",
    "minecraft:end_barrens", "minecraft:small_end_islands",
})
# BetterEnd's ores, injected into the vanilla End biomes by a biome modification: a chunk whose only
# betterend:* blocks are these is a vanilla-biome chunk that caught a vein, not a decorated one.
BETTEREND_ORES = frozenset({"betterend:flavolite", "betterend:thallasium_ore", "betterend:ender_ore"})


class _Reader:
    __slots__ = ("buf", "pos")

    def __init__(self, buf: bytes):
        self.buf = buf
        self.pos = 0

    def take(self, n: int) -> bytes:
        b = self.buf[self.pos:self.pos + n]
        if len(b) != n:
            raise ValueError("truncated NBT")
        self.pos += n
        return b

    def u1(self) -> int:
        return self.take(1)[0]

    def i2(self) -> int:
        return struct.unpack(">h", self.take(2))[0]

    def i4(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def i8(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def string(self) -> str:
        n = struct.unpack(">H", self.take(2))[0]
        return self.take(n).decode("utf-8", "replace")


def _payload(r: _Reader, tag: int):
    if tag == TAG_BYTE:
        return struct.unpack(">b", r.take(1))[0]
    if tag == TAG_SHORT:
        return r.i2()
    if tag == TAG_INT:
        return r.i4()
    if tag == TAG_LONG:
        return r.i8()
    if tag == TAG_FLOAT:
        return struct.unpack(">f", r.take(4))[0]
    if tag == TAG_DOUBLE:
        return struct.unpack(">d", r.take(8))[0]
    if tag == TAG_BYTE_ARRAY:
        n = r.i4()
        return list(struct.unpack(">%db" % n, r.take(n)))
    if tag == TAG_STRING:
        return r.string()
    if tag == TAG_LIST:
        elem = r.u1()
        n = r.i4()
        return [_payload(r, elem) for _ in range(n)]
    if tag == TAG_COMPOUND:
        out = {}
        while True:
            t = r.u1()
            if t == TAG_END:
                return out
            name = r.string()
            out[name] = _payload(r, t)
    if tag == TAG_INT_ARRAY:
        n = r.i4()
        return list(struct.unpack(">%di" % n, r.take(4 * n)))
    if tag == TAG_LONG_ARRAY:
        n = r.i4()
        return list(struct.unpack(">%dq" % n, r.take(8 * n)))
    raise ValueError("unknown NBT tag %d" % tag)


def parse_nbt(buf: bytes) -> dict:
    """The root compound of an uncompressed NBT blob (the root's name is discarded)."""
    r = _Reader(buf)
    tag = r.u1()
    if tag != TAG_COMPOUND:
        raise ValueError("NBT root is not a compound")
    r.string()
    return _payload(r, TAG_COMPOUND)


# --- Anvil ----------------------------------------------------------------------------------------

SECTOR = 4096


def read_region(path: str) -> Iterator[Tuple[int, int, dict]]:
    """Yield (chunkX, chunkZ, rootCompound) for every chunk stored in one .mca file."""
    base = os.path.basename(path)
    parts = base.split(".")
    rx, rz = int(parts[1]), int(parts[2])
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 2 * SECTOR:
        return
    for i in range(1024):
        off_sectors = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        count = data[i * 4 + 3]
        if off_sectors == 0 or count == 0:
            continue
        start = off_sectors * SECTOR
        if start + 5 > len(data):
            continue
        length = struct.unpack(">i", data[start:start + 4])[0]
        kind = data[start + 4]
        blob = data[start + 5:start + 4 + length]
        if kind & 0x80:
            continue  # external .mcc chunk — not produced by the dev server
        try:
            if kind == 1:
                raw = gzip.decompress(blob)
            elif kind == 2:
                raw = zlib.decompress(blob)
            elif kind == 3:
                raw = bytes(blob)
            else:
                print("warning: unsupported chunk compression %d in %s" % (kind, base), file=sys.stderr)
                continue
            root = parse_nbt(raw)
        except Exception as e:  # noqa: BLE001 — a corrupt chunk must not sink the census
            print("warning: %s chunk #%d unreadable: %s" % (base, i, e), file=sys.stderr)
            continue
        cx = root.get("xPos", rx * 32 + (i & 31))
        cz = root.get("zPos", rz * 32 + (i >> 5))
        yield cx, cz, root


# --- Block census ---------------------------------------------------------------------------------

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
END_GROUND = {"minecraft:end_stone", "minecraft:chorus_plant", "minecraft:chorus_flower"}
# Not decoration either: the world floor and the track corridor, should the range clip it.
STRUCTURAL = {"minecraft:bedrock", "minecraft:rail", "minecraft:powered_rail", "minecraft:oak_planks",
              "minecraft:smooth_stone", "minecraft:stone_bricks"}

ATTACH_KEY = "neoforge:attachments"
PENDING_KEY = "dungeontrain:end_band_pending"
SAMPLED_KEY = "dungeontrain:end_band_sampled_cells"


def bits_per_entry(palette_size: int) -> int:
    """Vanilla's block-state container: at least 4 bits, else ceil(log2(palette))."""
    bits = max(1, (palette_size - 1).bit_length())
    return max(4, bits)


def section_counts(palette: List[dict], data: Optional[List[int]],
                   columns: Optional[Tuple[int, int, int, int]] = None) -> Counter:
    """Block-name counts of one 16³ section. `columns` = (lx0, lx1, lz0, lz1) inclusive chunk-local
    bounds to count within (None = whole section). A section with no `data` is all palette[0]."""
    names = [p["Name"] for p in palette]
    out: Counter = Counter()
    if columns is None:
        lx0, lx1, lz0, lz1 = 0, 15, 0, 15
    else:
        lx0, lx1, lz0, lz1 = columns
    cols = (lx1 - lx0 + 1) * (lz1 - lz0 + 1)
    if cols <= 0:
        return out
    if data is None or len(names) == 1:
        out[names[0]] += 16 * cols
        return out
    bits = bits_per_entry(len(names))
    per_long = 64 // bits
    mask = (1 << bits) - 1
    # Unpack every index once (4096), then count the wanted columns.
    idx = []
    for word in data:
        word &= (1 << 64) - 1
        for _ in range(per_long):
            idx.append(word & mask)
            word >>= bits
            if len(idx) == 4096:
                break
        if len(idx) == 4096:
            break
    if len(idx) < 4096:
        raise ValueError("block_states data too short: %d entries" % len(idx))
    for y in range(16):
        for z in range(lz0, lz1 + 1):
            row = (y * 16 + z) * 16
            for x in range(lx0, lx1 + 1):
                out[names[idx[row + x]]] += 1
    return out


@dataclass
class ChunkStats:
    cx: int
    cz: int
    status: str
    counts: Counter = field(default_factory=Counter)
    pending: bool = False
    sampled_marker: bool = False
    # non-air per chunk-local X column (16 entries), for the per-X-slab comparison
    slab: List[int] = field(default_factory=lambda: [0] * 16)
    # biome palette entries per section, by biome id — the sections' palettes, not a per-quart count
    biomes: Counter = field(default_factory=Counter)

    @property
    def non_air(self) -> int:
        return sum(n for k, n in self.counts.items() if k not in AIR)

    @property
    def ground(self) -> int:
        return sum(n for k, n in self.counts.items() if k in END_GROUND)

    @property
    def betterend(self) -> int:
        return sum(n for k, n in self.counts.items() if k.startswith("betterend:"))

    @property
    def betterend_non_ore(self) -> int:
        return sum(n for k, n in self.counts.items() if k.startswith("betterend:") and k not in BETTEREND_ORES)

    @property
    def vanilla_end_quarts(self) -> int:
        """Section palette entries naming a vanilla End biome (0 in a fully remapped BetterEnd band chunk)."""
        return sum(n for k, n in self.biomes.items() if k in VANILLA_END_BIOMES)

    @property
    def other(self) -> int:
        return sum(n for k, n in self.counts.items()
                   if k not in AIR and k not in END_GROUND and k not in STRUCTURAL and not k.startswith("betterend:"))

    def top(self, n: int = 5) -> str:
        items = [(k, v) for k, v in self.counts.most_common() if k not in AIR][:n]
        return " ".join("%s=%d" % (k.split(":", 1)[-1], v) for k, v in items)


def classify(stats: ChunkStats) -> str:
    """empty (nothing at all), bare (End ground and nothing else), ore-only (End ground plus BetterEnd's
    injected ores and nothing else — a vanilla-biome chunk that caught a vein), decorated, or other (no End
    ground)."""
    if stats.non_air == 0:
        return "empty"
    if stats.ground == 0:
        return "other"
    if stats.betterend == 0 and stats.other == 0:
        return "bare"
    if stats.betterend_non_ore == 0 and stats.other == 0:
        return "ore-only"
    return "decorated"


def chunk_stats(cx: int, cz: int, root: dict, z0: int, z1: int) -> ChunkStats:
    st = ChunkStats(cx, cz, str(root.get("Status", "?")))
    lz0 = max(0, z0 - cz * 16)
    lz1 = min(15, z1 - cz * 16)
    for sec in root.get("sections", []):
        bs = sec.get("block_states")
        if not bs:
            continue
        palette = bs.get("palette") or []
        if not palette:
            continue
        names = [p["Name"] for p in palette]
        if all(n in AIR for n in names):
            continue
        data = bs.get("data")
        st.counts.update(section_counts(palette, data, (0, 15, lz0, lz1)))
        # per-X-column non-air, same Z clip
        for lx in range(16):
            col = section_counts(palette, data, (lx, lx, lz0, lz1))
            st.slab[lx] += sum(n for k, n in col.items() if k not in AIR)
    for sec in root.get("sections", []):
        for name in ((sec.get("biomes") or {}).get("palette") or []):
            st.biomes[name] += 1
    att = root.get(ATTACH_KEY) or {}
    st.pending = bool(att.get(PENDING_KEY, 0))
    st.sampled_marker = bool(att.get(SAMPLED_KEY, 0))
    return st


def census(region_dir: str, x0: int, x1: int, z0: int, z1: int) -> Dict[Tuple[int, int], ChunkStats]:
    cx0, cx1 = x0 >> 4, x1 >> 4
    cz0, cz1 = z0 >> 4, z1 >> 4
    out: Dict[Tuple[int, int], ChunkStats] = {}
    for rx in range(cx0 >> 5, (cx1 >> 5) + 1):
        for rz in range(cz0 >> 5, (cz1 >> 5) + 1):
            path = os.path.join(region_dir, "r.%d.%d.mca" % (rx, rz))
            if not os.path.exists(path):
                continue
            for cx, cz, root in read_region(path):
                if cx0 <= cx <= cx1 and cz0 <= cz <= cz1:
                    out[(cx, cz)] = chunk_stats(cx, cz, root, z0, z1)
    return out


def slab_totals(stats: Dict[Tuple[int, int], ChunkStats]) -> Dict[int, int]:
    """Non-air per world-X column (summed over the Z clip and every chunk row) — the #1746 metric."""
    out: Dict[int, int] = defaultdict(int)
    for (cx, _cz), st in stats.items():
        for lx in range(16):
            out[cx * 16 + lx] += st.slab[lx]
    return dict(out)


def summary(stats: Dict[Tuple[int, int], ChunkStats]) -> dict:
    classes = Counter(classify(s) for s in stats.values())
    return {
        "chunks": len(stats),
        "full": sum(1 for s in stats.values() if s.status == "minecraft:full"),
        "empty": classes["empty"], "bare": classes["bare"], "ore_only": classes["ore-only"],
        "decorated": classes["decorated"], "other": classes["other"],
        "vanilla_end_quarts": sum(s.vanilla_end_quarts for s in stats.values()),
        "vanilla_end_chunks": sum(1 for s in stats.values() if s.vanilla_end_quarts),
        "pending": sum(1 for s in stats.values() if s.pending),
        "sampled_marker": sum(1 for s in stats.values() if s.sampled_marker),
        "non_air": sum(s.non_air for s in stats.values()),
        "end_ground": sum(s.ground for s in stats.values()),
        "betterend": sum(s.betterend for s in stats.values()),
        "other_blocks": sum(s.other for s in stats.values()),
    }


# --- CLI ------------------------------------------------------------------------------------------

def _print_table(stats: Dict[Tuple[int, int], ChunkStats], csv: bool) -> None:
    rows = sorted(stats.values(), key=lambda s: (s.cx, s.cz))
    if csv:
        print("cx,cz,status,class,non_air,end_ground,betterend,other,pending,sampled_marker,vanilla_end_quarts,top")
        for s in rows:
            print(",".join(str(v) for v in (s.cx, s.cz, s.status, classify(s), s.non_air, s.ground, s.betterend,
                                             s.other, int(s.pending), int(s.sampled_marker), s.vanilla_end_quarts,
                                             '"%s"' % s.top())))
        return
    print("%6s %5s %-18s %-9s %7s %7s %7s %6s %3s %3s %4s  %s" % (
        "cx", "cz", "status", "class", "nonair", "ground", "bend", "other", "pnd", "smp", "vanQ", "top blocks"))
    for s in rows:
        print("%6d %5d %-18s %-9s %7d %7d %7d %6d %3s %3s %4d  %s" % (
            s.cx, s.cz, s.status.replace("minecraft:", ""), classify(s), s.non_air, s.ground, s.betterend, s.other,
            "Y" if s.pending else "-", "Y" if s.sampled_marker else "-", s.vanilla_end_quarts, s.top()))


def _print_summary(label: str, s: dict) -> None:
    print("%s: chunks=%d full=%d | decorated=%d ore-only=%d bare=%d empty=%d other=%d | pending=%d sampled_marker=%d | "
          "vanilla_end_biome chunks=%d quarts=%d | non_air=%d end_ground=%d betterend=%d other_blocks=%d" % (
              label, s["chunks"], s["full"], s["decorated"], s["ore_only"], s["bare"], s["empty"], s["other"],
              s["pending"], s["sampled_marker"], s["vanilla_end_chunks"], s["vanilla_end_quarts"],
              s["non_air"], s["end_ground"], s["betterend"], s["other_blocks"]))


def compare(a: Dict[Tuple[int, int], ChunkStats], b: Dict[Tuple[int, int], ChunkStats]) -> int:
    """Print per-chunk class/count differences and the per-X-slab diff. Returns the number of differing slabs."""
    keys = sorted(set(a) | set(b))
    print("%6s %5s  %-9s %-9s %8s %8s %8s %5s %5s" % ("cx", "cz", "classA", "classB", "nonairA", "nonairB", "bendDiff", "vanQA", "vanQB"))
    for k in keys:
        sa, sb = a.get(k), b.get(k)
        ca = classify(sa) if sa else "missing"
        cb = classify(sb) if sb else "missing"
        na = sa.non_air if sa else 0
        nb = sb.non_air if sb else 0
        ba = sa.betterend if sa else 0
        bb = sb.betterend if sb else 0
        va = sa.vanilla_end_quarts if sa else 0
        vb = sb.vanilla_end_quarts if sb else 0
        flag = "" if (ca == cb and na == nb and va == vb) else "  <-- differs"
        print("%6d %5d  %-9s %-9s %8d %8d %+8d %5d %5d%s" % (k[0], k[1], ca, cb, na, nb, bb - ba, va, vb, flag))
    ta, tb = slab_totals(a), slab_totals(b)
    xs = sorted(set(ta) | set(tb))
    diff = [(x, ta.get(x, 0), tb.get(x, 0)) for x in xs if ta.get(x, 0) != tb.get(x, 0)]
    print("per-X-slab non-air: %d of %d slabs differ" % (len(diff), len(xs)))
    if diff:
        print("  first/last differing X: %d .. %d; max |delta| = %d" % (
            diff[0][0], diff[-1][0], max(abs(x[1] - x[2]) for x in diff)))
    return len(diff)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("region", nargs="?", help="region directory (e.g. run/world/region)")
    ap.add_argument("--compare", nargs=2, metavar=("A_REGION", "B_REGION"), help="diff two worlds instead")
    ap.add_argument("--x", nargs=2, type=int, required=True, metavar=("X0", "X1"), help="world X range, inclusive")
    ap.add_argument("--z", nargs=2, type=int, required=True, metavar=("Z0", "Z1"), help="world Z range, inclusive")
    ap.add_argument("--csv", action="store_true", help="CSV table instead of aligned text")
    ap.add_argument("--count-full", action="store_true", help="print only the number of FULL chunks in range")
    ap.add_argument("--no-table", action="store_true", help="summary only")
    args = ap.parse_args(argv)
    x0, x1 = sorted(args.x)
    z0, z1 = sorted(args.z)
    if args.compare:
        a = census(args.compare[0], x0, x1, z0, z1)
        b = census(args.compare[1], x0, x1, z0, z1)
        _print_summary("A", summary(a))
        _print_summary("B", summary(b))
        compare(a, b)
        return 0
    if not args.region:
        ap.error("a region directory (or --compare) is required")
    stats = census(args.region, x0, x1, z0, z1)
    if args.count_full:
        print(summary(stats)["full"])
        return 0
    if not args.no_table:
        _print_table(stats, args.csv)
    if not args.csv:
        _print_summary(args.region, summary(stats))
    return 0


if __name__ == "__main__":
    sys.exit(main())
