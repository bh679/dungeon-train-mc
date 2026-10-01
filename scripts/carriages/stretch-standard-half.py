#!/usr/bin/env python3
"""Build the default Half carriage — the standard carriage stretched to a Half box.

`templates/standard.nbt` is a nine-long room: a door end at x = 0 and x = 8. A Half carriage is the
long portal corridor's length (13 at nine-long carriages, `ContentsSize.HALF`). The stretch keeps both
door ends and repeats the middle column across the extra length, so `templates/half/standard_half.nbt`
reads as the default carriage, longer — built of stage placeholder blocks (`STAGE`), so it wears each
stage's own stone and wood the way Flatbed, Cargo and the portal corridors do.

Idempotent and checkable:

    python3 scripts/carriages/stretch-standard-half.py           # write
    python3 scripts/carriages/stretch-standard-half.py --check   # CI / review
"""

from __future__ import annotations

import argparse
import gzip
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "portal"))
from nbt import INT, LIST, STRING, Tag, clone, get, put, read, write  # noqa: E402

TEMPLATES = Path(__file__).resolve().parents[2] / "src/main/resources/data/dungeontrain/templates"
SOURCE = TEMPLATES / "standard.nbt"
TARGET = TEMPLATES / "half" / "standard_half.nbt"


# standard's blocks → the stage placeholder standing in for each. Properties (facing, half, hinge…)
# carry over unchanged; every stage placeholder of a shape takes the same properties as its vanilla twin.
STAGE = {
    "minecraft:chiseled_copper": "dungeontrain:stage_stone_bricks",
    "minecraft:tuff_bricks": "dungeontrain:stage_stone_bricks",
    "minecraft:chiseled_tuff_bricks": "dungeontrain:stage_stone_feature",
    "minecraft:polished_tuff": "dungeontrain:stage_stone_polished",
    "minecraft:polished_tuff_stairs": "dungeontrain:stage_stone_polished_stairs",
    "minecraft:copper_block": "dungeontrain:stage_wood",
    "minecraft:cut_copper": "dungeontrain:stage_planks",
    "minecraft:cut_copper_stairs": "dungeontrain:stage_wood_stairs",
    "minecraft:oxidized_cut_copper_slab": "dungeontrain:stage_wood_slab",
    "minecraft:oxidized_copper_door": "dungeontrain:stage_door",
}


def to_stage(root: Tag) -> Tag:
    """`root` with every palette block swapped for its stage placeholder (in place)."""
    for entry in get(root, "palette").value[1]:
        name = get(entry, "Name").value
        if name.startswith("dungeontrain:stage_"):
            continue
        if name not in STAGE:
            raise SystemExit(f"standard.nbt uses {name}, which has no stage placeholder in STAGE — add one")
        put(entry, "Name", Tag(STRING, STAGE[name]))
    return root


def half_length(carriage_length: int) -> int:
    """`PortalCorridorSize.corridorDims(dims, LONG)`: the carriage plus its overrun, (L - 1) // 2."""
    return carriage_length + (carriage_length - 1) // 2


def stretch(root: Tag) -> tuple[Tag, int, int]:
    """`root` lengthened in place by repeating its middle column; returns it with the old and new lengths."""
    size = get(root, "size")
    length, height, width = (t.value for t in size.value[1])
    target = half_length(length)
    grow = target - length
    middle = length // 2

    blocks_id, blocks = get(root, "blocks").value
    column = [b for b in blocks if get(b, "pos").value[1][0].value == middle]
    for block in blocks:
        pos = get(block, "pos").value[1]
        if pos[0].value > middle:
            pos[0] = Tag(INT, pos[0].value + grow)
    for step in range(1, grow + 1):
        for block in column:
            copy = clone(block)
            get(copy, "pos").value[1][0] = Tag(INT, middle + step)
            blocks.append(copy)
    put(root, "blocks", Tag(LIST, (blocks_id, blocks)))
    put(root, "size", Tag(LIST, (size.value[0], [Tag(INT, target), Tag(INT, height), Tag(INT, width)])))
    return root, length, target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="report drift without writing")
    args = parser.parse_args()

    name, root = read(gzip.open(SOURCE, "rb").read())
    root, was, now = stretch(to_stage(root))
    data = write(name, root)
    fresh = TARGET.exists() and gzip.open(TARGET, "rb").read() == data
    print(f"standard {was} long -> half/standard_half {now} long — {'ok' if fresh else 'stale'}")
    if args.check:
        return 0 if fresh else 1
    if not fresh:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        with gzip.open(TARGET, "wb") as out:
            out.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
