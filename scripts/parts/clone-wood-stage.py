#!/usr/bin/env python3
"""Clone the `wood_oak` part set into a stage per wood type.

The `wood_oak` stage is seven parts — a floor, three walls, two roofs and a door — built from oak
blocks. Every other wood is the same seven parts with the oak ids swapped, which the Train Editor
can do one copy at a time (and names by truncating the source, `mudbricks_wood_oak_darkwo_spruce`).
This does the whole set per wood from the oak originals, so each wood gets a stage, its seven parts
and a manifest entry beside every oak entry, named `<family>_wood_<type>`.

Idempotent: a part, stage or manifest entry that already exists is left alone, so it can be re-run
after adding a wood to WOODS. `--check` reports what would be written and exits non-zero if anything
is missing or an output still carries an oak id.

Usage:
    python3 scripts/parts/clone-wood-stage.py [--check]
"""

import argparse
import gzip
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "portal"))
from nbt import COMPOUND, LIST, STRING, clone, get, read, write  # noqa: E402

DATA = ROOT / "src/main/resources/data/dungeontrain"
PARTS = DATA / "parts"
STAGES = DATA / "stages.json"
TEMPLATES = DATA / "templates"

SOURCE_STAGE = "wood_oak"
SOURCE_SUFFIX = "_wood_oak"
# The seven oak parts, by slot directory.
SOURCE_PARTS = {
    "floor": ["mudbricks_wood_oak"],
    "walls": ["mudbricks_wood_oak", "mudopen_wood_oak", "mudwindows_wood_oak"],
    "roof": ["mud_wood_oak", "mudopen_wood_oak"],
    "doors": ["mud_wood_oak"],
}
# Manifests that carry the oak entries. portal_short uses mud_wood_oak under `copper` — not a wood
# stage, so it is not on this list.
MANIFESTS = ["standard", "shared", "black", "cracked", "fancywood", "pen", "windowed"]
PHASES = ["OVERWORLD", "VOID", "UPSIDE_DOWN", "CHUNCKS"]

# The oak block ids the seven parts use, in NBT palettes and variant states.
OAK_KEYS = ["planks", "wood", "stripped_wood", "log", "stripped_log",
            "fence", "button", "slab", "stairs", "pressure_plate"]


def overworld(w: str) -> dict[str, str]:
    return {"planks": f"{w}_planks", "wood": f"{w}_wood", "stripped_wood": f"stripped_{w}_wood",
            "log": f"{w}_log", "stripped_log": f"stripped_{w}_log", "fence": f"{w}_fence",
            "button": f"{w}_button", "slab": f"{w}_slab", "stairs": f"{w}_stairs",
            "pressure_plate": f"{w}_pressure_plate"}


def nether(w: str) -> dict[str, str]:
    return {**overworld(w), "wood": f"{w}_hyphae", "stripped_wood": f"stripped_{w}_hyphae",
            "log": f"{w}_stem", "stripped_log": f"stripped_{w}_stem"}


BAMBOO = {**overworld("bamboo"), "wood": "bamboo_block", "stripped_wood": "stripped_bamboo_block",
          "log": "bamboo_block", "stripped_log": "stripped_bamboo_block"}
# Mosaic keeps the bamboo fittings; the pillar blocks are swapped for contrast against the mosaic.
BAMBOO_MOSAIC = {**BAMBOO, "planks": "bamboo_mosaic", "slab": "bamboo_mosaic_slab",
                 "stairs": "bamboo_mosaic_stairs", "wood": "stripped_bamboo_block",
                 "stripped_wood": "bamboo_block", "log": "stripped_bamboo_block",
                 "stripped_log": "bamboo_block"}

# Stage id → (block map, minLevel, maxLevel or None). Thirty-level bands continuing from darkwood
# (161–190); the existing spruce/acacia stages are re-banded to fit the sequence.
WOODS = {
    "spruce": (None, 191, 220),
    "acacia": (None, 221, 250),
    "birch": (overworld("birch"), 251, 280),
    "jungle": (overworld("jungle"), 281, 310),
    "mangrove": (overworld("mangrove"), 311, 340),
    "cherry": (overworld("cherry"), 341, 370),
    "bamboo": (BAMBOO, 371, 400),
    "bamboo_mosaic": (BAMBOO_MOSAIC, 401, 430),
    "crimson": (nether("crimson"), 431, 460),
    "warped": (nether("warped"), 461, None),
}

OAK_IDS = {f"minecraft:{overworld('oak')[k]}": k for k in OAK_KEYS}
ID_RE = re.compile(r"minecraft:[a-z_]+")


def part_name(source: str, wood: str) -> str:
    assert source.endswith(SOURCE_SUFFIX), source
    return source[: -len(SOURCE_SUFFIX)] + "_wood_" + wood


def map_id(block_id: str, blocks: dict[str, str]) -> str:
    """The wood's id for an oak block id; a non-oak id passes through unchanged."""
    key = OAK_IDS.get(block_id)
    return f"minecraft:{blocks[key]}" if key else block_id


def assert_no_oak(text: str, where: str) -> None:
    stray = sorted(i for i in set(ID_RE.findall(text)) if i in OAK_IDS)
    if stray:
        raise SystemExit(f"{where}: oak ids survived the swap: {stray}")


def clone_nbt(src: Path, dst: Path, blocks: dict[str, str]) -> None:
    name, root = read(gzip.decompress(src.read_bytes()))
    out = clone(root)
    palette = get(out, "palette")
    assert palette.id == LIST
    for entry in palette.value[1]:
        assert entry.id == COMPOUND
        tag = get(entry, "Name")
        assert tag.id == STRING
        tag.value = map_id(tag.value, blocks)
    assert_no_oak(" ".join(get(e, "Name").value for e in palette.value[1]), str(dst))
    dst.write_bytes(gzip.compress(write(name, out)))


def clone_variants(src: Path, dst: Path, blocks: dict[str, str]) -> None:
    text = ID_RE.sub(lambda m: map_id(m.group(0), blocks), src.read_text(encoding="utf-8"))
    json.loads(text)  # still valid JSON — only ids changed
    assert_no_oak(text, str(dst))
    dst.write_text(text, encoding="utf-8")


def clone_parts(check: bool) -> int:
    todo = []
    for wood, (blocks, _, _) in WOODS.items():
        if blocks is None:
            continue  # authored in the editor already
        for slot, sources in SOURCE_PARTS.items():
            for source in sources:
                target = part_name(source, wood)
                for ext in (".nbt", ".variants.json"):
                    src = PARTS / slot / (source + ext)
                    dst = PARTS / slot / (target + ext)
                    if not src.exists():
                        raise SystemExit(f"missing source part: {src}")
                    if dst.exists():
                        continue
                    todo.append((src, dst, blocks))
    for src, dst, blocks in todo:
        print(f"  part  {dst.relative_to(ROOT)}")
        if check:
            continue
        (clone_nbt if dst.suffix == ".nbt" else clone_variants)(src, dst, blocks)
    return len(todo)


def stage_json(min_level: int, max_level: int | None) -> dict:
    stage = {"name": None, "minLevel": min_level}
    if max_level is not None:
        stage["maxLevel"] = max_level
    stage["phases"] = list(PHASES)
    return stage


def update_stages(check: bool) -> int:
    stages = json.loads(STAGES.read_text(encoding="utf-8"))
    changed = 0
    for wood, (_, lo, hi) in WOODS.items():
        want = stage_json(lo, hi)
        want["name"] = stages.get(wood, {}).get("name", wood)
        if stages.get(wood) == want:
            continue
        print(f"  stage {wood}: {lo}–{hi if hi is not None else 'open'}")
        stages[wood] = want
        changed += 1
    if changed and not check:
        # Sorted keys, two-space indent, no trailing newline — the shape the store writes.
        STAGES.write_text(json.dumps(dict(sorted(stages.items())), indent=2), encoding="utf-8")
    return changed


def update_manifest(path: Path, check: bool) -> int:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    added = 0
    for slot, entries in manifest.items():
        if not isinstance(entries, list):
            continue
        present = {(e.get("name"), e.get("stage")) for e in entries if isinstance(e, dict)}
        oak = [e for e in entries if isinstance(e, dict) and e.get("stage") == SOURCE_STAGE]
        for wood, (blocks, _, _) in WOODS.items():
            if blocks is None:
                continue
            for e in oak:
                target = part_name(e["name"], wood)
                if (target, wood) in present:
                    continue
                entries.append({**e, "name": target, "stage": wood})
                present.add((target, wood))
                added += 1
    if added:
        print(f"  manifest {path.name}: +{added} entries")
        if not check:
            path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return added


def main() -> None:
    args = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    args.add_argument("--check", action="store_true", help="report what would change; write nothing")
    check = args.parse_args().check
    print("parts:")
    parts = clone_parts(check)
    print("stages:")
    stages = update_stages(check)
    print("manifests:")
    entries = sum(update_manifest(TEMPLATES / f"{m}.parts.json", check) for m in MANIFESTS)
    verb = "would write" if check else "wrote"
    print(f"{verb}: {parts} part files, {stages} stages, {entries} manifest entries")
    if check and (parts or stages or entries):
        sys.exit(1)


if __name__ == "__main__":
    main()
