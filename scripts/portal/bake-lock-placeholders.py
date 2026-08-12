#!/usr/bin/env python3
"""Bake the variant placeholder into every lock-group cell of the portal corridor template.

A template `.nbt` and its `.variants.json` sidecar both describe the same cells, and until the
corridor started applying its sidecar (PortalCarriageBuilder.applyCorridorVariants) only the `.nbt`
was ever read. The two had drifted: 33 of lock 1's 126 cells were baked as `spruce_planks` while the
other 93 were `stone`, so a lock group — whose whole promise is that every cell in it renders the
SAME block — shipped two materials, splitting partway along the corridor.

Rewriting those cells to the command-block placeholder makes `portal.variants.json` the single
source of truth: the placeholder is what the sidecar's apply path translates to air
(CarriageVariantBlocks.isEmptyPlaceholder), and every one of these cells is overwritten by the
sidecar at stamp time, so the placeholder itself is never what a player sees.

Note this deliberately diverges from the convention for other templates, which bake the first
candidate. The corridor is the one shell whose sidecar was dead, and a placeholder cannot silently
masquerade as a real authored block the way `stone` did.

Usage:
    python3 scripts/portal/bake-lock-placeholders.py [--check]

`--check` reports what would change without writing, which is what CI wants.
"""

import argparse
import gzip
import json
import sys
from pathlib import Path

from nbt import COMPOUND, INT, LIST, STRING, Tag, get, put, read, write

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "src/main/resources/data/dungeontrain/templates/portal.nbt"
SIDECAR = ROOT / "src/main/resources/data/dungeontrain/templates/portal.variants.json"

# Any of the three command blocks reads as the placeholder; this is the one the editor writes.
PLACEHOLDER_NAME = "minecraft:command_block"
PLACEHOLDER_PROPS = {"conditional": "false", "facing": "north"}


def locked_cells(sidecar_path):
    """Every (x, y, z) the sidecar assigns to a lock group, keyed by lock id."""
    variants = json.loads(sidecar_path.read_text(encoding="utf-8"))["variants"]
    cells = {}
    for pos, cell in variants.items():
        if not isinstance(cell, dict):
            continue  # bare-array form — an unlocked cell, left alone
        lock_id = cell.get("lockId", 0)
        if lock_id > 0:
            cells.setdefault(lock_id, set()).add(tuple(int(n) for n in pos.split(",")))
    return cells


def palette_index(root):
    """The palette slot holding the placeholder, appending one if the template has none."""
    palette = get(root, "palette")
    _, entries = palette.value
    for index, entry in enumerate(entries):
        try:
            name = get(entry, "Name").value
        except KeyError:
            continue
        if name != PLACEHOLDER_NAME:
            continue
        try:
            props = {n: c.value for n, c in get(entry, "Properties").value}
        except KeyError:
            props = {}
        if props == PLACEHOLDER_PROPS:
            return index, False
    entry = Tag(COMPOUND, [
        ("Name", Tag(STRING, PLACEHOLDER_NAME)),
        ("Properties", Tag(COMPOUND, [
            (key, Tag(STRING, value)) for key, value in sorted(PLACEHOLDER_PROPS.items())
        ])),
    ])
    entries.append(entry)
    return len(entries) - 1, True


def rebake(check):
    name, root = read(gzip.decompress(TEMPLATE.read_bytes()))
    groups = locked_cells(SIDECAR)
    targets = {pos: lock_id for lock_id, cells in groups.items() for pos in cells}

    index, appended = palette_index(root)
    _, palette_entries = get(root, "palette").value
    palette_names = [get(entry, "Name").value for entry in palette_entries]

    changed = {}
    seen = set()
    for block in get(root, "blocks").value[1]:
        pos = tuple(component.value for component in get(block, "pos").value[1])
        lock_id = targets.get(pos)
        if lock_id is None:
            continue
        seen.add(pos)
        state = get(block, "state")
        if state.value == index:
            continue
        changed.setdefault(lock_id, {}).setdefault(palette_names[state.value], 0)
        changed[lock_id][palette_names[state.value]] += 1
        if not check:
            put(block, "state", Tag(INT, index))

    missing = set(targets) - seen
    for lock_id in sorted(groups):
        was = changed.get(lock_id, {})
        summary = ", ".join(f"{count}x {block}" for block, count in sorted(was.items())) or "nothing"
        print(f"  lock {lock_id}: {len(groups[lock_id])} cells, rewriting {summary}")
    if missing:
        print(f"  WARNING: {len(missing)} locked cells have no block in the template: "
              f"{sorted(missing)[:5]}{' …' if len(missing) > 5 else ''}", file=sys.stderr)

    total = sum(sum(was.values()) for was in changed.values())
    if check:
        print(f"{total} cell(s) would change" + (" (palette entry would be added)" if appended else ""))
        return 1 if total or appended else 0

    if total or appended:
        TEMPLATE.write_bytes(gzip.compress(write(name, root)))
        print(f"wrote {TEMPLATE.relative_to(ROOT)} — {total} cell(s) rebaked")
    else:
        print("already baked; nothing to do")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="report what would change without writing")
    args = parser.parse_args()
    return rebake(args.check)


if __name__ == "__main__":
    sys.exit(main())
