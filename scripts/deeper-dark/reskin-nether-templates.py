#!/usr/bin/env python3
"""Re-skin the Nether band's track, tunnel and mob-room templates into the Deeper Dark's sculk ones.

The Deeper Dark train phase (TrainPhase.DEEPER_DARK) gets the same track tile and tunnel section/portal
geometry as the Nether's (nethertracks / darktunnel / darkportal), so the crossfade dither lines up block for
block — only the palette changes, to Deeper Darker's sculk stone, echo wood and gloomslate. Kept for the record
of how the sculk templates were derived; re-run it after editing a Nether template to refresh its twin.

    python3 scripts/deeper-dark/reskin-nether-templates.py
"""

import gzip
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "portal"))
import nbt  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "data", "dungeontrain")

# Nether block -> (Deeper Dark block, keep the block-state properties?)
REMAP = {
    "minecraft:blackstone": ("deeperdarker:cobbled_sculk_stone", True),
    "minecraft:blackstone_slab": ("deeperdarker:cobbled_sculk_stone_slab", True),
    "minecraft:blackstone_stairs": ("deeperdarker:cobbled_sculk_stone_stairs", True),
    "minecraft:crimson_trapdoor": ("deeperdarker:echo_trapdoor", True),
    "minecraft:polished_basalt": ("deeperdarker:polished_gloomslate", False),
    "minecraft:warped_hyphae": ("deeperdarker:echo_wood", True),
    "minecraft:crimson_hyphae": ("deeperdarker:stripped_echo_wood", True),
    "minecraft:polished_blackstone_bricks": ("deeperdarker:sculk_stone_bricks", True),
    "minecraft:polished_blackstone_brick_slab": ("deeperdarker:sculk_stone_brick_slab", True),
    "minecraft:polished_blackstone_brick_stairs": ("deeperdarker:sculk_stone_brick_stairs", True),
    "minecraft:polished_blackstone_brick_wall": ("deeperdarker:sculk_stone_brick_wall", True),
    "minecraft:sea_lantern": ("deeperdarker:sculk_gleam", False),
    "minecraft:polished_blackstone": ("deeperdarker:polished_sculk_stone", True),
    "minecraft:polished_blackstone_slab": ("deeperdarker:polished_sculk_stone_slab", True),
    "minecraft:chiseled_polished_blackstone": ("deeperdarker:chiseled_sculk_stone", True),
    "minecraft:cracked_polished_blackstone_bricks": ("deeperdarker:sculk_stone_tiles", True),
}

# Nether mob -> Deeper Darker mob, for the carriage-contents twin (piglings -> sculkmobs)
ENTITIES = {
    "minecraft:piglin": "deeperdarker:sculk_snapper",
    "minecraft:piglin_brute": "deeperdarker:stalker",
    "minecraft:hoglin": "deeperdarker:sculk_centipede",
    "betternether:flying_pig": "deeperdarker:shattered",
}

# (source, destination) relative to data/dungeontrain, each with an optional .variants.json twin
PAIRS = [
    ("tracks/nethertracks", "tracks/sculktracks"),
    ("tunnels/section/darktunnel", "tunnels/section/sculktunnel"),
    ("tunnels/portal/darkportal", "tunnels/portal/sculkportal"),
    ("contents/piglings", "contents/sculkmobs"),
]


def remap_nbt(src, dst):
    with gzip.open(src) as f:
        name, root = nbt.read(f.read())
    for entry in nbt.get(root, "palette").value[1]:
        block = nbt.get(entry, "Name")
        target = REMAP.get(block.value)
        if target is None:
            continue
        block.value, keep = target
        if not keep:
            entry.value = [(k, v) for k, v in entry.value if k != "Properties"]
    with gzip.open(dst, "wb") as f:
        f.write(nbt.write(name, root))


def remap_state(state):
    block, bracket, props = state.partition("[")
    target = REMAP.get(block)
    if target is None:
        return state
    new_block, keep = target
    return new_block + (bracket + props if keep else "")


def remap_json(node):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == "state" and isinstance(v, str):
                out[k] = remap_state(v)
            elif k == "entity" and isinstance(v, str):
                out[k] = ENTITIES.get(v, v)
            else:
                out[k] = remap_json(v)
        return out
    if isinstance(node, list):
        return [remap_state(v) if isinstance(v, str) else remap_json(v) for v in node]
    return node


def main():
    for src, dst in PAIRS:
        remap_nbt(os.path.join(ROOT, src + ".nbt"), os.path.join(ROOT, dst + ".nbt"))
        variants = os.path.join(ROOT, src + ".variants.json")
        if os.path.exists(variants):
            with open(variants) as f:
                data = json.load(f)
            with open(os.path.join(ROOT, dst + ".variants.json"), "w") as f:
                json.dump(remap_json(data), f, indent=2)
                f.write("\n")
        print(f"{src} -> {dst}")


if __name__ == "__main__":
    main()
