#!/usr/bin/env python3
"""Build the 3x3 room frames from stone_frame.

A frame is the room box plus a one-block shell: stone_frame is 18 x 34 x 18 for the one-chunk
room, and a 3x3 room (48 x 32 x 48) takes a 50 x 34 x 50 frame. Each 3x3 frame here is stone_frame
laid out wider: every cell of the new frame is mapped to a cell of stone_frame, and takes that cell's
block and its block-variant roll from the sidecar, so the new frames keep stone_frame's look and
rolls. Once generated they are ordinary frames: open, edit and save them in the editor.

    stone_frame_bays       stone_frame's inside tiled 3x3: nine bays marked by pillars and beams
    stone_frame_hall       corners kept, the 5-block post and beam rhythm carried on, mirrored at the middle
    stone_frame_courtyard  the hall's walls and corners only: floor and roof open past a 2-block rim

Usage:
    python3 scripts/portal/make-3x3-frames.py [--check]

`--check` exits non-zero when a generated file is missing or stale, without writing.
"""

import argparse
import gzip
import json
import sys
from pathlib import Path

from nbt import COMPOUND, INT, LIST, STRING, Tag, clone, get, put, read, write

FRAMES = (Path(__file__).resolve().parents[2]
          / "src/main/resources/data/dungeontrain/chunk_frames")
SOURCE = "stone_frame"

OLD = 18   # stone_frame's width and depth
NEW = 50   # a 3x3 room's frame: 48 + a one-block shell each side
INSIDE = 16
PERIOD = 5           # stone_frame's post and beam rhythm along a wall
RHYTHM_START = 6     # where that rhythm's first whole period starts
RIM = 2              # how far in from the edge the courtyard keeps its floor and roof
WALL_BAND = (0, 1, INSIDE - 2, INSIDE - 1)   # a tile's wall columns and the posts against them
FLOOR_ROWS = (0, 2)      # stone_frame's floor and its beams
CEILING_ROWS = (31, 33)  # its ceiling beams and roof


def bays(i):
    """The old cell for new cell i, tiling stone_frame's inside three times."""
    if i == 0:
        return 0
    if i == NEW - 1:
        return OLD - 1
    return 1 + (i - 1) % INSIDE


def hall_half(i):
    """The old cell for new cell i in the left half: the corner as it is, then the rhythm repeated."""
    if i < RHYTHM_START:
        return i
    return RHYTHM_START + (i - RHYTHM_START) % PERIOD


def hall(i):
    """The old cell for new cell i: the left half, and the right half mirrored onto stone_frame's right."""
    if i < NEW // 2:
        return hall_half(i)
    return OLD - 1 - hall_half(NEW - 1 - i)


def bays_keeps(x, y, z):
    """Whether the bays keep a cell: not where stone_frame's own walls would stand on a chunk line.

    Tiling stone_frame's inside also tiles its plank walls, which would wall the room into nine
    boxes. On the chunk lines inside the room only the corner pillars (where two lines cross) and the
    floor and ceiling beams stay, so the room stays one space with nine bays marked out overhead.
    """
    # The outer walls stay whole: only the chunk lines inside the room are thinned.
    if min(x, NEW - 1 - x, z, NEW - 1 - z) <= RIM:
        return True
    line_x = 0 < x < NEW - 1 and (x - 1) % INSIDE in WALL_BAND and x not in (1, 2, NEW - 3, NEW - 2)
    line_z = 0 < z < NEW - 1 and (z - 1) % INSIDE in WALL_BAND and z not in (1, 2, NEW - 3, NEW - 2)
    if not (line_x or line_z):
        return True
    if FLOOR_ROWS[0] <= y <= FLOOR_ROWS[1] or CEILING_ROWS[0] <= y <= CEILING_ROWS[1]:
        return True
    pillar_x = line_x and (x - 1) % INSIDE in (0, INSIDE - 1)
    pillar_z = line_z and (z - 1) % INSIDE in (0, INSIDE - 1)
    return pillar_x and pillar_z


def courtyard_keeps(x, y, z):
    """Whether the courtyard keeps a cell: anything within the rim of a wall, at any height."""
    return min(x, NEW - 1 - x, z, NEW - 1 - z) <= RIM


DESIGNS = {
    "stone_frame_bays": (bays, bays_keeps),
    "stone_frame_hall": (hall, None),
    "stone_frame_courtyard": (hall, courtyard_keeps),
}


def cells(template):
    """The source frame's blocks by position."""
    out = {}
    for block in get(template, "blocks").value[1]:
        x, y, z = (t.value for t in get(block, "pos").value[1])
        out[(x, y, z)] = block
    return out


def air_state(template):
    """The palette index of air, appended when the source has none."""
    palette = get(template, "palette").value[1]
    for i, entry in enumerate(palette):
        if get(entry, "Name").value == "minecraft:air":
            return i
    palette.append(Tag(COMPOUND, [("Name", Tag(STRING, "minecraft:air"))]))
    return len(palette) - 1


def build_frame(template, mapping, keeps):
    out = clone(template)
    height = get(template, "size").value[1][1].value
    source = cells(template)
    air = air_state(out)
    blocks = []
    for y in range(height):
        for z in range(NEW):
            for x in range(NEW):
                pos = Tag(LIST, (INT, [Tag(INT, x), Tag(INT, y), Tag(INT, z)]))
                old = source.get((mapping(x), y, mapping(z)))
                if keeps is not None and not keeps(x, y, z):
                    old = None
                if old is None:
                    blocks.append(Tag(COMPOUND, [("pos", pos), ("state", Tag(INT, air))]))
                    continue
                block = clone(old)
                put(block, "pos", pos)
                blocks.append(block)
    put(out, "size", Tag(LIST, (INT, [Tag(INT, NEW), Tag(INT, height), Tag(INT, NEW)])))
    put(out, "blocks", Tag(LIST, (COMPOUND, blocks)))
    return out


def build_variants(sidecar, height, mapping, keeps):
    out = {k: v for k, v in sidecar.items() if k != "variants"}
    variants = {}
    old = sidecar.get("variants", {})
    for y in range(height):
        for z in range(NEW):
            for x in range(NEW):
                if keeps is not None and not keeps(x, y, z):
                    continue
                entry = old.get(f"{mapping(x)},{y},{mapping(z)}")
                if entry is not None:
                    variants[f"{x},{y},{z}"] = entry
    out["variants"] = variants
    return out


def sidecar_text(doc):
    """The sidecar in the editor's own layout: one variant cell per line, as stone_frame's is."""
    lines = ["{", f'  "schemaVersion": {json.dumps(doc["schemaVersion"])},']
    if "mirror" in doc:
        lines.append(f'  "mirror": {json.dumps(doc["mirror"], separators=(", ", ": "))},')
    lines.append('  "variants": {')
    entries = list(doc["variants"].items())
    for i, (key, value) in enumerate(entries):
        comma = "," if i < len(entries) - 1 else ""
        lines.append(f'    {json.dumps(key)}: {json.dumps(value, separators=(", ", ": "))}{comma}')
    lines.append("  }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    name, template = read(gzip.decompress((FRAMES / f"{SOURCE}.nbt").read_bytes()))
    height = get(template, "size").value[1][1].value
    sidecar = json.loads((FRAMES / f"{SOURCE}.variants.json").read_text())
    stale = []
    for frame, (mapping, keeps) in DESIGNS.items():
        outputs = {
            f"{frame}.nbt": gzip.compress(write(name, build_frame(template, mapping, keeps)), mtime=0),
            f"{frame}.variants.json": sidecar_text(build_variants(sidecar, height, mapping, keeps)).encode(),
            f"{frame}.frame.json": (json.dumps({"schemaVersion": 1, "weight": 1}, indent=2) + "\n").encode(),
        }
        for file, data in outputs.items():
            path = FRAMES / file
            current = path.read_bytes() if path.exists() else None
            if file.endswith(".nbt") and current is not None:
                same = gzip.decompress(current) == gzip.decompress(data)
            else:
                same = current == data
            if same:
                continue
            stale.append(file)
            if not args.check:
                path.write_bytes(data)
                print(f"wrote {file}")
    if args.check and stale:
        print("stale:", ", ".join(stale), "- run scripts/portal/make-3x3-frames.py")
        sys.exit(1)


if __name__ == "__main__":
    main()
