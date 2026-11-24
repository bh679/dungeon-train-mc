#!/usr/bin/env python3
"""Build the 3x3-chunk dimensional room templates from their one-chunk counterparts.

A chunk dimension room's template is only its fallback: a plain floor of the look's ground block,
air above, which the sampled terrain is poured over (PortalChunkDimension). The grid a room samples
is read off this template's size (PortalChunkArea.span), so a 3x3 room is a 48 x 32 x 48 copy of the
one-chunk template, tiled three times along X and Z. Nothing else differs, so the templates are
generated rather than authored: a change to a one-chunk template is carried over by running this.

    chunk_dimension<look>.nbt  (16 x 32 x 16)  ->  chunk_3x3<look>.nbt  (48 x 32 x 48)

Usage:
    python3 scripts/portal/make-chunk-area-templates.py [--check]

`--check` exits non-zero when a generated template is missing or stale, without writing.
"""

import argparse
import gzip
import sys
from pathlib import Path

from nbt import COMPOUND, INT, LIST, Tag, clone, get, put, read, write

ROOMS = (Path(__file__).resolve().parents[2]
         / "src/main/resources/data/dungeontrain/portals/room")

# The look suffixes PortalChunkTerrain.Source.of reads off a room's name (it matches endsWith).
LOOKS = ("", "_wwoo", "_bop", "_nether", "_nether_better", "_end", "_end_better")

SPAN = 3
CHUNK = 16


def tiled(template):
    """The template tiled SPAN times along X and Z."""
    out = clone(template)
    size = [t.value for t in get(out, "size").value[1]]
    if size[0] != CHUNK or size[2] != CHUNK:
        raise ValueError(f"expected a {CHUNK}-wide template, got {size}")
    put(out, "size", Tag(LIST, (INT, [Tag(INT, CHUNK * SPAN), Tag(INT, size[1]), Tag(INT, CHUNK * SPAN)])))
    blocks = get(template, "blocks").value[1]
    copies = []
    for tx in range(SPAN):
        for tz in range(SPAN):
            for block in blocks:
                copy = clone(block)
                x, y, z = (t.value for t in get(copy, "pos").value[1])
                put(copy, "pos", Tag(LIST, (INT, [Tag(INT, x + tx * CHUNK), Tag(INT, y), Tag(INT, z + tz * CHUNK)])))
                copies.append(copy)
    # Same order a structure save writes: by y, then z, then x.
    copies.sort(key=lambda b: tuple(reversed([t.value for t in get(b, "pos").value[1]])))
    put(out, "blocks", Tag(LIST, (COMPOUND, copies)))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    stale = []
    for look in LOOKS:
        source = ROOMS / f"chunk_dimension{look}.nbt"
        target = ROOMS / f"chunk_3x3{look}.nbt"
        name, template = read(gzip.decompress(source.read_bytes()))
        data = write(name, tiled(template))
        current = gzip.decompress(target.read_bytes()) if target.exists() else None
        if current == data:
            continue
        stale.append(target.name)
        if not args.check:
            target.write_bytes(gzip.compress(data, mtime=0))
            print(f"wrote {target.name}")
    if args.check and stale:
        print("stale:", ", ".join(stale), "- run scripts/portal/make-chunk-area-templates.py")
        sys.exit(1)


if __name__ == "__main__":
    main()
