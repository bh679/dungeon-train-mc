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
import sys
from pathlib import Path

from nbt import INT, LIST, Tag, clone, get, put, read, write

TEMPLATE = (Path(__file__).resolve().parents[2]
            / "src/main/resources/data/dungeontrain/templates/portal.nbt")

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
