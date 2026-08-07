#!/usr/bin/env python3
"""Seed the portal contents template from the portal corridor's own interior.

The `portal` contents type describes what stands *inside* a portal corridor. Contents templates are
interior-only by convention — the box minus its shell, `(length-2, height-2, width-2)` — so for the
13-long corridor that is 11x5x5.

Starting it from an empty box would mean authoring against nothing: the corridor's walkway, its
lantern floor and its baffles are all part of the interior, and you want them in front of you while
you build. So the seed is a straight crop of `templates/portal.nbt`'s interior, re-based to the
contents origin.

**This is a bootstrap, not a build step.** Once the file exists it is authored content — re-running
leaves it alone, because overwriting it would silently discard whatever was built in the editor.
Pass `--force` to deliberately re-seed from the corridor again.

Usage:
    python3 scripts/portal/seed-portal-contents.py [--check] [--force]
"""

import argparse
import gzip
import sys
from pathlib import Path

from nbt import COMPOUND, INT, LIST, Tag, clone, get, put, read, write

ROOT = Path(__file__).resolve().parents[2] / "src/main/resources/data/dungeontrain"
CARRIAGE = ROOT / "templates/portal.nbt"
CONTENTS = ROOT / "contents/portal.nbt"

# The shell is one block thick on every face; the interior is what is left.
SHELL = 1


def interior_size(size):
    """CarriageContentsPlacer.interiorSize — the box minus its shell, floored at zero."""
    return tuple(max(0, n - 2 * SHELL) for n in size)


def crop_interior(root):
    """A new structure holding `root`'s interior, re-based so the corner cell is (0, 0, 0)."""
    length, height, width = (t.value for t in get(root, "size").value[1])
    inner = interior_size((length, height, width))
    if min(inner) <= 0:
        raise SystemExit(f"carriage template {length}x{height}x{width} has no interior volume")

    blocks_id, blocks = get(root, "blocks").value
    kept = []
    for block in blocks:
        x, y, z = (t.value for t in get(block, "pos").value[1])
        if not (SHELL <= x < length - SHELL
                and SHELL <= y < height - SHELL
                and SHELL <= z < width - SHELL):
            continue
        copy = clone(block)
        get(copy, "pos").value[1][:] = [
            Tag(INT, x - SHELL), Tag(INT, y - SHELL), Tag(INT, z - SHELL)]
        kept.append(copy)

    out = clone(root)
    put(out, "blocks", Tag(LIST, (blocks_id, kept)))
    put(out, "size", Tag(LIST, (get(root, "size").value[0],
                                [Tag(INT, n) for n in inner])))
    # The palette carries over whole. Entries the crop orphaned are harmless — placement reads a
    # palette index per block, and never enumerates the palette.
    return out, inner


def existing_size():
    """The size the contents template already on disk records, or None if there is no file."""
    if not CONTENTS.is_file():
        return None
    _, root = read(gzip.open(CONTENTS, "rb").read())
    return tuple(t.value for t in get(root, "size").value[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report without writing")
    parser.add_argument("--force", action="store_true",
                        help="re-seed from the corridor even if the contents template exists, "
                             "DISCARDING anything authored in it")
    args = parser.parse_args()

    if not CARRIAGE.is_file():
        raise SystemExit(f"no carriage template at {CARRIAGE}")

    name, carriage = read(gzip.open(CARRIAGE, "rb").read())
    cropped, inner = crop_interior(carriage)
    inner_str = "x".join(str(n) for n in inner)

    on_disk = existing_size()
    if on_disk is not None and not args.force:
        print(f"contents/portal.nbt already exists ({'x'.join(str(n) for n in on_disk)})"
              f" — leaving it alone; pass --force to re-seed from the corridor")
        return 0

    print(f"contents/portal.nbt <- interior of templates/portal.nbt: {inner_str}"
          f" ({len(get(cropped, 'blocks').value[1])} blocks)")
    if args.check:
        return 0

    CONTENTS.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(CONTENTS, "wb") as out:
        out.write(write(name, cropped))
    print(f"wrote {CONTENTS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
