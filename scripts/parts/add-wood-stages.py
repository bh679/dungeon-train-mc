#!/usr/bin/env python3
"""Add a train stage for every modded wood DT depends on.

Each wood stage re-skins the one shared, placeholder-built `*_wood` part set through its palette's `wood`
family (`block/stage/StageWoodFamily.java`), so a new wood is just:
  1. a stage in `data/dungeontrain/stages.json` — its own 16-level band after `bamboo_mosaic`, the same
     phases as every wood stage, a palette naming the wood's blocks and its designed colours;
  2. a copy of `spruce`'s entries in every `templates/*.parts.json` manifest with `stage` swapped.

Every wood stage carries `"group": "wood"`, which folds them into one entry in the editor's Stages tab.

`spruce` stays the open-ended top band and moves to start after the last new wood.

WOODS mirrors the modded constants in StageWoodFamily (family id, naming style, leaves fill); keep the two
in step. Idempotent: a stage or manifest entry that already exists is left alone. `--check` writes nothing
and exits 1 if anything is missing. `--blocks FILE` (one `ns:id` per line, e.g. dumped from the mod jars'
blockstates) additionally fails on any palette block id the mods do not ship.

Usage:
    python3 scripts/parts/add-wood-stages.py [--check] [--blocks FILE]
"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "src/main/resources/data/dungeontrain"
STAGES = DATA / "stages.json"
TEMPLATES = DATA / "templates"

TEMPLATE_STAGE = "spruce"
GROUP = "wood"
TOP_STAGE = "spruce"
FIRST_LEVEL = 436
BAND = 16

BOP, BN, BE = "biomesoplenty", "betternether", "betterend"

# (stage id, namespace, wood name, style, terracotta[3], concrete[3]); order = level order.
# style: "vanilla" | "bcl" | "bcl_stem". Colours are dyes (primary, secondary, background).
WOODS = [
    ("pale_oak", "minecraft", "pale_oak", "vanilla", ("light_gray", "white", "gray"), ("white", "light_gray", "gray")),
    ("fir", BOP, "fir", "vanilla", ("green", "brown", "white"), ("green", "white", "brown")),
    ("pine", BOP, "pine", "vanilla", ("brown", "green", "black"), ("green", "brown", "black")),
    ("redwood", BOP, "redwood", "vanilla", ("red", "brown", "orange"), ("red", "brown", "black")),
    ("mahogany", BOP, "mahogany", "vanilla", ("red", "brown", "pink"), ("red", "brown", "white")),
    ("jacaranda", BOP, "jacaranda", "vanilla", ("purple", "magenta", "white"), ("purple", "magenta", "white")),
    ("palm", BOP, "palm", "vanilla", ("yellow", "orange", "white"), ("yellow", "light_blue", "white")),
    ("willow", BOP, "willow", "vanilla", ("lime", "green", "brown"), ("lime", "green", "gray")),
    ("dead", BOP, "dead", "vanilla", ("gray", "light_gray", "black"), ("gray", "light_gray", "black")),
    ("magic", BOP, "magic", "vanilla", ("blue", "light_blue", "cyan"), ("blue", "light_blue", "white")),
    ("maple", BOP, "maple", "vanilla", ("red", "orange", "yellow"), ("red", "orange", "brown")),
    ("umbran", BOP, "umbran", "vanilla", ("blue", "purple", "black"), ("blue", "purple", "black")),
    ("hellbark", BOP, "hellbark", "vanilla", ("red", "orange", "black"), ("red", "orange", "black")),
    ("empyreal", BOP, "empyreal", "vanilla", ("light_blue", "white", "yellow"), ("light_blue", "white", "yellow")),
    ("anchor_tree", BN, "anchor_tree", "bcl", ("cyan", "green", "black"), ("cyan", "green", "black")),
    ("gloomwood", BN, "gloomwood", "bcl", ("gray", "cyan", "black"), ("gray", "cyan", "black")),
    ("gloomwood_dark", BN, "gloomwood_dark", "bcl", ("black", "gray", "purple"), ("black", "gray", "purple")),
    ("mushroom_fir", BN, "mushroom_fir", "bcl", ("brown", "light_gray", "red"), ("brown", "red", "white")),
    ("nether_mushroom", BN, "nether_mushroom", "bcl_stem", ("red", "white", "brown"), ("red", "white", "brown")),
    ("nether_reed", BN, "nether_reed", "bcl_stem", ("cyan", "lime", "black"), ("cyan", "lime", "gray")),
    ("nether_sakura", BN, "nether_sakura", "bcl", ("pink", "magenta", "white"), ("pink", "magenta", "white")),
    ("rubeus", BN, "rubeus", "bcl", ("red", "orange", "black"), ("red", "orange", "gray")),
    ("stalagnate", BN, "stalagnate", "bcl", ("lime", "yellow", "brown"), ("lime", "yellow", "brown")),
    ("wart", BN, "wart", "bcl", ("red", "magenta", "black"), ("red", "black", "gray")),
    ("nether_willow", BN, "willow", "bcl", ("red", "brown", "black"), ("brown", "red", "black")),
    ("dragon_tree", BE, "dragon_tree", "bcl", ("purple", "magenta", "black"), ("purple", "magenta", "black")),
    ("helix_tree", BE, "helix_tree", "bcl", ("orange", "yellow", "brown"), ("orange", "yellow", "white")),
    ("lacugrove", BE, "lacugrove", "bcl", ("green", "lime", "cyan"), ("green", "lime", "cyan")),
    ("lucernia", BE, "lucernia", "bcl", ("orange", "red", "black"), ("orange", "red", "gray")),
    ("pythadendron", BE, "pythadendron", "bcl", ("purple", "pink", "white"), ("purple", "pink", "white")),
    ("tenanea", BE, "tenanea", "bcl", ("pink", "magenta", "purple"), ("pink", "magenta", "white")),
    ("end_lotus", BE, "end_lotus", "bcl", ("light_blue", "cyan", "white"), ("light_blue", "cyan", "white")),
    ("jellyshroom", BE, "jellyshroom", "bcl", ("purple", "light_blue", "black"), ("purple", "light_blue", "black")),
    ("mossy_glowshroom", BE, "mossy_glowshroom", "bcl", ("light_blue", "green", "gray"), ("light_blue", "green", "gray")),
    ("umbrella_tree", BE, "umbrella_tree", "bcl", ("green", "light_blue", "black"), ("green", "light_blue", "gray")),
]


def family_id(ns: str, name: str) -> str:
    """StageWoodFamily#id(): bare for minecraft woods, namespaced otherwise."""
    return name if ns == "minecraft" else f"{ns}:{name}"


def wood_blocks(ns: str, w: str, style: str) -> dict[str, str]:
    """The ids the palette lists: pillars, planks, stairs, slab (StageWoodFamily#overworld / #bcl)."""
    p = f"{ns}:"
    if style == "vanilla":
        pillars = {"wood": f"{w}_wood", "stripped_wood": f"stripped_{w}_wood",
                   "log": f"{w}_log", "stripped_log": f"stripped_{w}_log"}
    elif style == "bcl":
        pillars = {"wood": f"{w}_bark", "stripped_wood": f"{w}_stripped_bark",
                   "log": f"{w}_log", "stripped_log": f"{w}_stripped_log"}
    else:
        pillars = dict.fromkeys(("wood", "stripped_wood", "log", "stripped_log"), f"{w}_stem")
    ids = {"planks": f"{w}_planks", **pillars, "stairs": f"{w}_stairs", "slab": f"{w}_slab"}
    return {k: p + v for k, v in ids.items()}


def terracotta(dye: str) -> str:
    return f"minecraft:{dye}_terracotta" if dye else "minecraft:terracotta"


def palette(ns: str, w: str, style: str, terra, concrete) -> dict:
    b = wood_blocks(ns, w, style)
    solid = [b["planks"], b["wood"], b["stripped_wood"], b["log"], b["stripped_log"]]
    return {
        "solid": solid,
        "stairs": [b["stairs"], b["stairs"]],
        "slabs": [b["slab"], b["slab"]],
        "button": "minecraft:stone_button",
        "pressurePlate": "minecraft:stone_pressure_plate",
        "wood": family_id(ns, w),
        "stone": "stone",
        "terracotta": [terracotta(d) for d in terra],
        "concrete": [f"minecraft:{d}_concrete" for d in concrete],
        "woodLocked": True,
    }


def with_group(stage: dict) -> dict:
    """A copy of the stage in the wood group, `group` placed right after `name` as Stage.toJson writes it."""
    out = {}
    for key, value in stage.items():
        if key != "group":
            out[key] = value
        if key == "name":
            out["group"] = GROUP
    return out


def update_stages(stages: dict) -> list[str]:
    """Adds missing wood stages and moves the top band; returns what changed."""
    changes = []
    phases = stages[TEMPLATE_STAGE]["phases"]
    for i, (sid, ns, w, style, terra, conc) in enumerate(WOODS):
        if sid in stages:
            continue
        lo = FIRST_LEVEL + i * BAND
        stages[sid] = {"name": sid, "group": GROUP, "minLevel": lo, "maxLevel": lo + BAND - 1,
                       "phases": list(phases), "palette": palette(ns, w, style, terra, conc)}
        changes.append(f"stage {sid} {lo}-{lo + BAND - 1}")
    for sid in [TEMPLATE_STAGE] + [w[0] for w in WOODS]:
        if stages[sid].get("group") != GROUP:
            stages[sid] = with_group(stages[sid])
            changes.append(f"stage {sid} group -> {GROUP}")
    top = FIRST_LEVEL + len(WOODS) * BAND
    if stages[TOP_STAGE].get("minLevel") != top:
        stages[TOP_STAGE]["minLevel"] = top
        changes.append(f"stage {TOP_STAGE} minLevel -> {top}")
    return changes


def update_manifest(manifest: dict) -> list[str]:
    """After the template stage's last entry in each slot, adds a copy per missing wood stage."""
    changes = []
    for slot, entries in manifest.items():
        if not isinstance(entries, list):
            continue
        template = [e for e in entries if e.get("stage") == TEMPLATE_STAGE]
        if not template:
            continue
        insert_at = max(i for i, e in enumerate(entries) if e.get("stage") == TEMPLATE_STAGE) + 1
        added = []
        for sid, *_ in WOODS:
            for t in template:
                if any(e.get("stage") == sid and e.get("name") == t["name"] for e in entries):
                    continue
                added.append({**t, "stage": sid})
                changes.append(f"{slot}/{t['name']} @ {sid}")
        entries[insert_at:insert_at] = added
    return changes


def missing_blocks(stages: dict, known: set[str]) -> list[str]:
    """Wood block ids in the new palettes that no mod ships (every one is mod-shipped, pale oak included)."""
    missing = set()
    for sid, *_ in WOODS:
        pal = stages[sid]["palette"]
        missing.update(f"{sid}: {b}" for b in pal["solid"] + pal["stairs"] + pal["slabs"] if b not in known)
    return sorted(missing)


def dump(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="report only; exit 1 if anything is missing")
    ap.add_argument("--blocks", type=Path, help="file of known ns:id block ids to validate palettes against")
    args = ap.parse_args()

    stages = json.loads(STAGES.read_text(encoding="utf-8"))
    pending = update_stages(stages)
    stages = dict(sorted(stages.items()))
    manifests = {}
    for path in sorted(TEMPLATES.glob("*.parts.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        changes = update_manifest(manifest)
        if changes:
            manifests[path] = manifest
            pending.append(f"{path.name}: {len(changes)} entries")

    problems = missing_blocks(stages, set(args.blocks.read_text().split())) if args.blocks else []
    for p in problems:
        print(f"UNKNOWN BLOCK {p}")
    for c in pending:
        print(("WOULD " if args.check else "") + c)

    if args.check:
        return 1 if pending or problems else 0
    if problems:
        return 1
    if pending:
        dump(STAGES, stages)
        for path, manifest in manifests.items():
            dump(path, manifest)
    print(f"{len(pending)} change(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
