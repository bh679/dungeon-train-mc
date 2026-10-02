#!/usr/bin/env python3
"""Copy whole-group templates into the Group carriage pool as shells.

A whole group (`whole/group/<id>.nbt`) is a shell and its inside saved as one template. A Group
carriage template (`templates/group/<id>.nbt`) is the shell alone — its inside comes from the
contents pool (`cargocontents` and the like). So the copy keeps every block, block entity and entity
on the box's outer layer and its end walls one row in, drops everything inside, and keeps only the matching
`.variants.json` entries.

Idempotent and checkable: `--check` reports whether the shipped shells match their sources.

    python3 scripts/carriages/whole-to-shell.py           # write
    python3 scripts/carriages/whole-to-shell.py --check   # CI / review
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "portal"))
from nbt import LIST, Tag, get, put, read, write  # noqa: E402

DATA = Path(__file__).resolve().parents[2] / "src/main/resources/data/dungeontrain"
WHOLE_GROUP = DATA / "whole/group"
GROUP_POOL = DATA / "templates/group"

# whole-group id → Group carriage id. `flatbed` is a built-in carriage's name, so its Group copy
# takes another.
COPIES = {
    "cargo": "cargo",
    "stonecargo": "stonecargo",
    "flatbed": "flatbedgroup",
}


def size_of(root: Tag) -> tuple[int, int, int]:
    return tuple(t.value for t in get(root, "size").value[1])


def interior(pos: tuple[int, int, int], size: tuple[int, int, int]) -> bool:
    """True when (x, y, z) is inside the carriage's room — what the contents pool fills.

    A whole carriage's end walls stand one row in from each end (x = 1 and x = length - 2), so
    they stay with the shell; everything between them, inside the floor, roof and side walls, goes.
    """
    x, y, z = pos
    length, height, width = size
    return 1 < x < length - 2 and 0 < y < height - 1 and 0 < z < width - 1


def int_pos(entry: Tag, key: str) -> tuple[int, ...] | None:
    tag = get(entry, key)
    return tuple(t.value for t in tag.value[1]) if tag is not None else None


def shell(root: Tag) -> tuple[Tag, int]:
    """`root` with every block and entity strictly inside the box removed (in place)."""
    size = size_of(root)
    blocks_id, blocks = get(root, "blocks").value
    kept = [b for b in blocks if not interior(int_pos(b, "pos"), size)]
    put(root, "blocks", Tag(LIST, (blocks_id, kept)))
    ents = get(root, "entities")
    if ents is not None:
        ents_id, entities = ents.value
        put(root, "entities", Tag(LIST, (ents_id, [
            e for e in entities
            if int_pos(e, "blockPos") is None or not interior(int_pos(e, "blockPos"), size)])))
    return root, len(blocks) - len(kept)


def shell_variants(doc: dict, size: tuple[int, int, int]) -> dict:
    """The variants sidecar keeping only the shell's positions."""
    out = dict(doc)
    out["variants"] = {
        key: value for key, value in doc.get("variants", {}).items()
        if not interior(tuple(int(n) for n in key.split(",")), size)
    }
    return out


def dump_variants(doc: dict) -> str:
    """The sidecar in the game's own layout: top-level keys one per line, one variant entry per line."""
    def compact(v: object) -> str:
        return json.dumps(v, separators=(", ", ": "), ensure_ascii=False)
    lines = ["{"]
    keys = list(doc.keys())
    for i, key in enumerate(keys):
        tail = "," if i < len(keys) - 1 else ""
        if key == "variants":
            entries = list(doc[key].items())
            lines.append(f'  "variants": {{')
            for j, (pos, entry) in enumerate(entries):
                lines.append(f'    {json.dumps(pos)}: {compact(entry)}' + ("," if j < len(entries) - 1 else ""))
            lines.append("  }" + tail)
        else:
            lines.append(f"  {json.dumps(key)}: {compact(doc[key])}{tail}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def build(source_id: str) -> tuple[bytes, str | None, int, tuple[int, int, int]]:
    name, root = read(gzip.open(WHOLE_GROUP / f"{source_id}.nbt", "rb").read())
    root, dropped = shell(root)
    nbt_bytes = write(name, root)
    variants = None
    src_variants = WHOLE_GROUP / f"{source_id}.variants.json"
    if src_variants.exists():
        variants = dump_variants(shell_variants(json.loads(src_variants.read_text()), size_of(root)))
    return nbt_bytes, variants, dropped, size_of(root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="report drift without writing")
    args = parser.parse_args()

    stale = []
    for source_id, target_id in COPIES.items():
        nbt_bytes, variants, dropped, size = build(source_id)
        nbt_path = GROUP_POOL / f"{target_id}.nbt"
        var_path = GROUP_POOL / f"{target_id}.variants.json"
        same_nbt = nbt_path.exists() and gzip.open(nbt_path, "rb").read() == nbt_bytes
        same_var = (variants is None and not var_path.exists()) or \
            (var_path.exists() and var_path.read_text() == variants)
        status = "ok" if same_nbt and same_var else "stale"
        print(f"{source_id} -> group/{target_id}: {size[0]}x{size[1]}x{size[2]}, "
              f"{dropped} interior blocks dropped — {status}")
        if status == "stale":
            stale.append(target_id)
        if args.check or status == "ok":
            continue   # nothing to write — rewriting would only change the gzip timestamp
        GROUP_POOL.mkdir(parents=True, exist_ok=True)
        with gzip.open(nbt_path, "wb") as out:
            out.write(nbt_bytes)
        if variants is not None:
            var_path.write_text(variants)
    if args.check and stale:
        print(f"stale: {', '.join(stale)} — run scripts/carriages/whole-to-shell.py")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
