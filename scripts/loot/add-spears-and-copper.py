#!/usr/bin/env python3
"""Put VanillaBackport 1.2's spears and copper gear into DT's bundled loot pools.

Edits every `data/dungeontrain/containers/*.contents.json` and `data/dungeontrain/prefabs/loot/*.json` pool:

  - Spears: every vanilla-tier sword entry (`minecraft:<tier>_sword`) is followed by a `minecraft:<tier>_spear`
    entry with the same count, weight and roll settings.
  - Copper tools: where a pool holds both the stone and the iron version of a sword, axe, pickaxe, shovel or hoe,
    a copper one goes in after the stone one, weighted at the mean of the two (a copper sword then gets its spear
    like any other sword).
  - Copper armour: where a pool holds both the leather and the chainmail piece for a slot, a copper piece goes in
    after the leather one, at the mean of the two. Horse armour the same way, between leather and iron.

Copper sits between stone and iron (tools) and between leather and chainmail (armour), as in vanilla.

Line-based, so a file keeps its one-entry-per-line layout. Idempotent: a sword already followed by its spear,
or a pool that already has the copper piece, is left alone. `--check` writes nothing and exits 1 if anything
would change.

Usage:
    python3 scripts/loot/add-spears-and-copper.py [--check]
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "src/main/resources/data/dungeontrain"
GLOBS = ("containers/*.contents.json", "prefabs/loot/*.json")

MC = "minecraft:"
SWORD_TIERS = ("wooden", "stone", "copper", "iron", "golden", "diamond", "netherite")
TOOLS = ("sword", "axe", "pickaxe", "shovel", "hoe")
ARMOUR = ("helmet", "chestplate", "leggings", "boots")

ENTRIES_OPEN = re.compile(r'^(\s*)"entries"\s*:\s*\[\s*$')
ENTRIES_CLOSE = re.compile(r"^\s*\](,?)\s*$")


def entry_line(obj: dict, indent: str) -> str:
    """One entry in the files' own style: `{ "id": "...", "count": 1, ... }`."""
    return indent + "{ " + ", ".join(f"{json.dumps(k)}: {json.dumps(v)}" for k, v in obj.items()) + " }"


def with_id(entry: dict, item: str, weight: int | None = None) -> dict:
    out = dict(entry)
    out["id"] = MC + item
    if weight is not None:
        out["weight"] = weight
    return out


def mean_weight(a: dict, b: dict) -> int:
    return max(1, round((a.get("weight", 1) + b.get("weight", 1)) / 2))


def add_copper(entries: list[dict]) -> list[dict]:
    """Copper tools after stone, copper armour after leather, when both neighbours are present."""
    by_id = {e.get("id"): e for e in entries}
    insert_after: dict[str, dict] = {}
    pairs = [(f"stone_{t}", f"iron_{t}", f"copper_{t}") for t in TOOLS]
    pairs += [(f"leather_{s}", f"chainmail_{s}", f"copper_{s}") for s in ARMOUR]
    pairs.append(("leather_horse_armor", "iron_horse_armor", "copper_horse_armor"))
    for low, high, copper in pairs:
        lo, hi = by_id.get(MC + low), by_id.get(MC + high)
        if lo is None or hi is None or MC + copper in by_id:
            continue
        insert_after[MC + low] = with_id(lo, copper, mean_weight(lo, hi))
    if not insert_after:
        return entries
    out = []
    for e in entries:
        out.append(e)
        if e.get("id") in insert_after:
            out.append(insert_after[e["id"]])
    return out


def add_spears(entries: list[dict]) -> list[dict]:
    out = []
    for i, e in enumerate(entries):
        out.append(e)
        m = re.fullmatch(r"minecraft:(\w+)_sword", e.get("id", ""))
        if not m or m.group(1) not in SWORD_TIERS:
            continue
        spear = f"{m.group(1)}_spear"
        if i + 1 < len(entries) and entries[i + 1].get("id") == MC + spear:
            continue
        out.append(with_id(e, spear))
    return out


def process(text: str, path: Path) -> tuple[str, int]:
    lines = text.split("\n")
    out, added, i = [], 0, 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        i += 1
        if not ENTRIES_OPEN.match(line):
            continue
        block = []
        while i < len(lines) and not ENTRIES_CLOSE.match(lines[i]):
            block.append(lines[i])
            i += 1
        if not block:
            continue
        indent = re.match(r"^(\s*)", block[0]).group(1)
        one_per_line = all(b.strip().startswith("{") and b.strip().rstrip(",").endswith("}") for b in block)
        try:
            entries = json.loads("[" + "\n".join(block) + "]")
        except json.JSONDecodeError as err:
            raise SystemExit(f"{path}: can't read an entries block ({err})")
        new = add_spears(add_copper(entries))
        if len(new) == len(entries):
            out.extend(block)  # unchanged pool: keep its bytes
            continue
        added += len(new) - len(entries)
        # Entries that were already there keep their exact text; only new ones are rendered.
        original = {id(e): b.rstrip().rstrip(",") for e, b in zip(entries, block)} if one_per_line else {}
        rendered = [original.get(id(e)) or (entry_line(e, indent) if one_per_line else multi_line(e, indent))
                    for e in new]
        out.extend(r + ("," if k < len(rendered) - 1 else "") for k, r in enumerate(rendered))
    return "\n".join(out), added


def multi_line(obj: dict, indent: str) -> str:
    """One entry in the pretty-printed style a few files use."""
    return "\n".join(indent + line for line in json.dumps(obj, indent=2).split("\n"))


def main() -> int:
    check = "--check" in sys.argv[1:]
    files = sorted(p for g in GLOBS for p in DATA.glob(g))
    changed = total = 0
    for path in files:
        text = path.read_text()
        new, added = process(text, path)
        if added == 0:
            continue
        json.loads(new)  # never write a file that no longer parses
        changed += 1
        total += added
        if not check:
            path.write_text(new)
    verb = "would add" if check else "added"
    print(f"{verb} {total} entries in {changed} of {len(files)} files")
    return 1 if check and total else 0


if __name__ == "__main__":
    sys.exit(main())
