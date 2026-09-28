#!/usr/bin/env python3
"""Generate DT's Lost City variant data: processor lists, template pools, structures and the water tag.

    python3 scripts/lost-city/generate-variants.py .

The output under src/main/resources/data/dungeontrain/ is committed; edit the recipes here and re-run
to retune. Every pool element places one of the Big Lost City mod's own templates (never a copy — the
mod is all-rights-reserved) through a DT processor list: `dungeontrain:lost_city_swap` recolours,
decays or strips overgrowth while keeping block states; `dungeontrain:lost_city_truncate` lops the top
off. See worldgen/LostCitySwapProcessor and LostCityTruncateProcessor.
"""
import json
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else '.'
D = os.path.join(ROOT, 'src/main/resources/data/dungeontrain')
PL = os.path.join(D, 'worldgen/processor_list/lost_city')
TP = os.path.join(D, 'worldgen/template_pool/lost_city')
ST = os.path.join(D, 'worldgen/structure/lost_city')
for p in (PL, TP, ST):
    os.makedirs(p, exist_ok=True)

IGNORE = {"processor_type": "minecraft:block_ignore", "blocks": [{"Name": "minecraft:structure_block"}]}


def mc(b):
    return b if ':' in b else 'minecraft:' + b


def swap(pairs):
    """pairs: (from, to[, chance]) — a `to` of air removes the block."""
    return {"processor_type": "dungeontrain:lost_city_swap",
            "swaps": [{"from": mc(p[0]), "to": mc(p[1]), **({"chance": p[2]} if len(p) > 2 else {})} for p in pairs]}


def bite(rubble, bites=(1, 2), radius=(0.3, 0.5), pile_scale=0.8, pile_max=10):
    """Huge chunks bitten out of the shell, the mass heaped as rubble on the floor/ground beneath."""
    return {"processor_type": "dungeontrain:lost_city_bite", "bites_min": bites[0], "bites_max": bites[1],
            "radius_min": radius[0], "radius_max": radius[1], "rubble": [mc(r) for r in rubble],
            "pile_scale": pile_scale, "pile_max": pile_max}


def truncate(lo, hi, rubble, jagged=3):
    return {"processor_type": "dungeontrain:lost_city_truncate", "min_fraction": lo, "max_fraction": hi,
            "jagged": jagged, "rubble": [mc(r) for r in rubble]}


# --- shared overgrowth: dry / dead -----------------------------------------------------------------
DRY = swap([
    ("vine", "air", 0.9), ("cave_vines", "air"), ("cave_vines_plant", "air"), ("hanging_roots", "air", 0.8),
    ("moss_block", "coarse_dirt"), ("moss_carpet", "air", 0.9),
    ("short_grass", "dead_bush", 0.5), ("short_grass", "air", 0.5), ("fern", "dead_bush", 0.5), ("fern", "air", 0.5),
    ("tall_grass", "air"), ("large_fern", "air"),
    ("azalea_leaves", "air", 0.6), ("flowering_azalea_leaves", "air", 0.6),
    ("oak_leaves", "air", 0.5), ("birch_leaves", "air", 0.5), ("spruce_leaves", "air", 0.5),
    ("dark_oak_leaves", "air", 0.5), ("acacia_leaves", "air", 0.5),
    ("small_dripleaf", "air"), ("big_dripleaf", "air"), ("big_dripleaf_stem", "air"),
    ("seagrass", "air"), ("tall_seagrass", "air"), ("lily_pad", "air"),
    ("azalea", "dead_bush"), ("flowering_azalea", "dead_bush"), ("cornflower", "air"), ("bamboo", "air"),
    ("mossy_cobblestone", "cobblestone", 0.7), ("mossy_stone_bricks", "cracked_stone_bricks", 0.7),
    ("mossy_cobblestone_wall", "cobblestone_wall", 0.7), ("mossy_stone_brick_wall", "stone_brick_wall", 0.7),
    ("mossy_cobblestone_slab", "cobblestone_slab", 0.7), ("mossy_cobblestone_stairs", "cobblestone_stairs", 0.7),
])

# --- per-building recipes (muted ruins palette) ---------------------------------------------------
BLACK_GREY = swap([
    ("smooth_basalt", "gray_concrete"), ("deepslate", "tuff"), ("deepslate_tiles", "polished_andesite"),
    ("cracked_deepslate_tiles", "andesite"), ("polished_deepslate", "light_gray_concrete"),
    ("polished_blackstone", "light_gray_concrete"), ("polished_blackstone_slab", "polished_andesite_slab"),
    ("polished_blackstone_stairs", "polished_andesite_stairs"), ("deepslate_bricks", "stone_bricks"),
    ("cracked_deepslate_bricks", "cracked_stone_bricks"), ("black_stained_glass_pane", "gray_stained_glass_pane"),
    ("black_stained_glass", "gray_stained_glass"), ("crimson_stairs", "spruce_stairs"),
])
BLACK_WHITE = swap([
    ("smooth_basalt", "calcite"), ("deepslate", "diorite"), ("deepslate_tiles", "smooth_quartz"),
    ("cracked_deepslate_tiles", "quartz_bricks"), ("polished_deepslate", "white_concrete"),
    ("polished_blackstone", "white_concrete"), ("polished_blackstone_slab", "smooth_quartz_slab"),
    ("polished_blackstone_stairs", "smooth_quartz_stairs"), ("deepslate_bricks", "polished_diorite"),
    ("cracked_deepslate_bricks", "diorite"), ("black_stained_glass_pane", "light_gray_stained_glass_pane"),
    ("black_stained_glass", "light_gray_stained_glass"), ("crimson_stairs", "birch_stairs"),
])
BLACK_TOP = truncate(0.45, 0.7, ["cracked_deepslate_tiles", "cobbled_deepslate", "deepslate_tiles", "air"])

RED_SAND = swap([
    ("granite", "sandstone"), ("polished_granite", "cut_sandstone"), ("bricks", "smooth_sandstone"),
    ("brick_slab", "smooth_sandstone_slab"), ("brick_stairs", "smooth_sandstone_stairs"),
    ("terracotta", "white_terracotta"), ("waxed_copper_block", "waxed_exposed_copper"),
    ("dripstone_block", "sandstone"), ("stripped_acacia_wood", "stripped_birch_wood"),
    ("black_stained_glass_pane", "brown_stained_glass_pane"),
])
RED_OXID = swap([
    ("waxed_copper_block", "waxed_oxidized_copper"), ("bricks", "stone_bricks"), ("brick_slab", "stone_brick_slab"),
    ("brick_stairs", "stone_brick_stairs"), ("granite", "andesite"), ("polished_granite", "polished_andesite"),
    ("terracotta", "cyan_terracotta"), ("dripstone_block", "tuff"), ("stripped_acacia_wood", "stripped_spruce_wood"),
])
RED_TOP = truncate(0.45, 0.7, ["cracked_deepslate_tiles", "bricks", "granite", "air"])

TALL_CLEAR = swap([("green_stained_glass", "light_gray_stained_glass"),
                   ("green_stained_glass_pane", "light_gray_stained_glass_pane"), ("mossy_cobblestone", "cobblestone", 0.7)])
TALL_DARK = swap([("green_stained_glass", "gray_stained_glass"), ("green_stained_glass_pane", "gray_stained_glass_pane"),
                  ("stone", "deepslate"), ("deepslate_tiles", "polished_deepslate"), ("smooth_stone", "polished_deepslate"),
                  ("smooth_stone_slab", "polished_deepslate_slab"), ("mossy_cobblestone", "cobbled_deepslate")])
TALL_BLOWN = swap([("green_stained_glass", "air", 0.6), ("green_stained_glass_pane", "air", 0.6), ("cobweb", "air"),
                   ("chain", "air", 0.5), ("deepslate_tiles", "cracked_deepslate_tiles", 0.4)])
TALL_TOP = truncate(0.35, 0.6, ["mossy_cobblestone", "cracked_deepslate_tiles", "cobblestone", "air"])

RUIN_DEEP = swap([("stone", "deepslate"), ("andesite", "tuff"), ("diorite", "tuff"), ("stone_bricks", "deepslate_bricks"),
                  ("cracked_stone_bricks", "cracked_deepslate_bricks"), ("mossy_stone_bricks", "deepslate_bricks"),
                  ("stone_brick_slab", "deepslate_brick_slab"), ("stone_brick_stairs", "deepslate_brick_stairs"),
                  ("stone_brick_wall", "deepslate_brick_wall"), ("mossy_cobblestone", "cobbled_deepslate")])
RUIN_MUD = swap([("stone_bricks", "mud_bricks"), ("cracked_stone_bricks", "packed_mud"), ("mossy_stone_bricks", "mud_bricks"),
                 ("andesite", "packed_mud"), ("stone_brick_slab", "mud_brick_slab"), ("stone_brick_stairs", "mud_brick_stairs"),
                 ("stone_brick_wall", "mud_brick_wall"), ("diorite", "packed_mud")])
RUIN_COLLAPSE = swap([("stone_bricks", "air", 0.35), ("cracked_stone_bricks", "air", 0.35), ("mossy_stone_bricks", "air", 0.35),
                      ("andesite", "air", 0.2), ("gray_wool", "air", 0.5), ("light_gray_wool", "air", 0.5), ("white_wool", "air", 0.5)])

PLANT_CRUMBLE = swap([("stone_bricks", "air", 0.3), ("cracked_stone_bricks", "air", 0.3), ("cobblestone", "air", 0.3),
                      ("mossy_stone_brick_wall", "air", 0.5), ("mossy_cobblestone_wall", "air", 0.5),
                      ("cobblestone_wall", "air", 0.5), ("stone_brick_wall", "air", 0.5),
                      ("smooth_stone", "cracked_stone_bricks", 0.3)])

GARAGE_LIGHT = swap([("gray_concrete", "light_gray_concrete"), ("black_terracotta", "gray_terracotta"),
                     ("red_terracotta", "brown_terracotta"), ("blue_terracotta", "light_gray_terracotta"),
                     ("black_stained_glass", "gray_stained_glass"), ("coal_block", "gray_concrete")])
GARAGE_TOP = truncate(0.45, 0.75, ["gray_concrete_powder", "cobblestone", "gravel", "gray_concrete", "air"], jagged=2)

SHIP_RUST = swap([("waxed_exposed_copper", "waxed_weathered_copper", 0.6), ("waxed_exposed_copper", "waxed_oxidized_copper", 0.4),
                  ("waxed_weathered_copper", "waxed_oxidized_copper"), ("cyan_terracotta", "gray_terracotta"),
                  ("waxed_weathered_cut_copper_slab", "waxed_oxidized_cut_copper_slab")])
SHIP_GREY = swap([("waxed_exposed_copper", "light_gray_concrete"), ("waxed_weathered_copper", "gray_concrete"),
                  ("oxidized_copper", "gray_concrete"), ("weathered_copper", "gray_concrete"),
                  ("cyan_terracotta", "black_concrete"), ("red_concrete", "gray_concrete"),
                  ("waxed_weathered_cut_copper_slab", "stone_slab")])

WARE_GREY = swap([("bricks", "stone_bricks"), ("brick_slab", "stone_brick_slab"), ("brick_stairs", "stone_brick_stairs"),
                  ("calcite", "light_gray_concrete"), ("diorite", "andesite"), ("diorite_stairs", "andesite_stairs"),
                  ("granite", "tuff"), ("cyan_terracotta", "gray_terracotta"), ("blue_terracotta", "light_gray_terracotta")])

DEEP_RUBBLE = ["cracked_deepslate_tiles", "cobbled_deepslate", "deepslate_tiles", "cobblestone", "gravel"]
BLACK_BITE = bite(DEEP_RUBBLE)
BLACK_BITE_GREY = [BLACK_GREY, bite(["andesite", "cobblestone", "gravel", "gray_concrete_powder", "polished_andesite"])]
RED_BITE = bite(["bricks", "granite", "cobblestone", "gravel", "cracked_deepslate_tiles"])
TALL_BITE = bite(["mossy_cobblestone", "cobblestone", "cracked_deepslate_tiles", "gravel", "stone"], radius=(0.4, 0.7))
RUIN_BITE = bite(["cracked_stone_bricks", "cobblestone", "stone", "gravel", "andesite"])
PLANT_BITE = bite(["cracked_stone_bricks", "cobblestone", "mossy_cobblestone", "gravel", "stone"], bites=(2, 3), radius=(0.25, 0.4))
GARAGE_BITE = bite(["gray_concrete", "gray_concrete_powder", "cobblestone", "gravel", "andesite"], bites=(2, 3), radius=(0.18, 0.3), pile_max=7)

# building -> (mod template, [(variant, processors, weight)]); "shipped"/"dry" use the shared lists
BUILDINGS = {
    "blackskyscraper": ("black_skyscraperlt", [("shipped", [], 3), ("grey", [BLACK_GREY], 2), ("white", [BLACK_WHITE], 2), ("topped", [BLACK_TOP], 2), ("bitten", [BLACK_BITE], 3), ("bitten_grey", BLACK_BITE_GREY, 2), ("dry", [], 1)]),
    "ruinedblackskyscraper": ("ruined_black_skyscraperlt", [("shipped", [], 3), ("grey", [BLACK_GREY], 2), ("white", [BLACK_WHITE], 2), ("topped", [BLACK_TOP], 2), ("bitten", [BLACK_BITE], 3), ("dry", [], 1)]),
    "redskyscraper": ("red_skyscraperlt", [("shipped", [], 3), ("sandstone", [RED_SAND], 2), ("oxidised", [RED_OXID], 2), ("topped", [RED_TOP], 2), ("bitten", [RED_BITE], 3), ("dry", [], 1)]),
    "ruindedredskyscraper": ("ruinded_red_skyscraperlt", [("shipped", [], 3), ("sandstone", [RED_SAND], 2), ("oxidised", [RED_OXID], 2), ("topped", [RED_TOP], 2), ("bitten", [RED_BITE], 3), ("dry", [], 1)]),
    "tallskyscraper": ("tall_skyscraperlt", [("shipped", [], 3), ("clear", [TALL_CLEAR], 2), ("dark", [TALL_DARK], 2), ("blown", [TALL_BLOWN], 2), ("topped", [TALL_TOP], 2), ("bitten", [TALL_BITE], 3), ("dry", [], 1)]),
    "ruinedskyscraper": ("ruined_skyscraperlt", [("shipped", [], 3), ("deepslate", [RUIN_DEEP], 2), ("mud", [RUIN_MUD], 2), ("collapsed", [RUIN_COLLAPSE], 2), ("bitten", [RUIN_BITE], 3), ("dry", [], 1)]),
    "powerplant": ("power_plantlt", [("shipped", [], 3), ("mud", [RUIN_MUD], 2), ("deepslate", [RUIN_DEEP], 2), ("crumbling", [PLANT_CRUMBLE], 2), ("bitten", [PLANT_BITE], 2), ("dry", [], 1)]),
    "parking_garage": ("parking_garagelt", [("shipped", [], 3), ("light", [GARAGE_LIGHT], 2), ("collapsed", [GARAGE_TOP], 2), ("bitten", [GARAGE_BITE], 2), ("dry", [], 1)]),
    "warship": ("warshiplt", [("shipped", [], 3), ("rusted", [SHIP_RUST], 2), ("grey", [SHIP_GREY], 2)]),
    "warehouse": ("warehouselt", [("shipped", [], 3), ("grey", [WARE_GREY], 2), ("dry", [], 1)]),
}

SHARED = {"shipped": "dungeontrain:lost_city/shipped", "dry": "dungeontrain:lost_city/dry"}


def dump(path, obj):
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def structure(pool, biomes):
    return {
        "neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "big_lost_city"}],
        "type": "minecraft:jigsaw", "start_pool": pool, "size": 1, "max_distance_from_center": 64,
        "spawn_overrides": {}, "step": "surface_structures", "terrain_adaptation": "beard_thin",
        "start_height": {"absolute": 0}, "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "biomes": biomes, "use_expansion_hack": False,
    }


dump(os.path.join(PL, 'shipped.json'), {"processors": [IGNORE]})
dump(os.path.join(PL, 'dry.json'), {"processors": [IGNORE, DRY]})

for name, (template, variants) in BUILDINGS.items():
    elements = []
    for vname, procs, weight in variants:
        if vname in SHARED:
            pid = SHARED[vname]
        else:
            pid = f"dungeontrain:lost_city/{name}_{vname}"
            dump(os.path.join(PL, f"{name}_{vname}.json"), {"processors": [IGNORE] + procs})
        elements.append({"weight": weight, "element": {
            "element_type": "minecraft:single_pool_element",
            "location": f"big_lost_city:{template}", "projection": "rigid", "processors": pid}})
    dump(os.path.join(TP, f"{name}.json"),
         {"name": f"dungeontrain:lost_city/{name}", "fallback": "minecraft:empty", "elements": elements})
    biomes = "#dungeontrain:lost_city_water" if name == "warship" else "#dungeontrain:lost_city_trackside"
    dump(os.path.join(ST, f"{name}.json"), structure(f"dungeontrain:lost_city/{name}", biomes))

dump(os.path.join(D, 'tags/worldgen/biome/lost_city_water.json'),
     {"replace": False, "values": ["#minecraft:is_ocean", "#minecraft:is_deep_ocean", "#minecraft:is_river"]})

ss_path = os.path.join(D, 'worldgen/structure_set/lost_city.json')
with open(ss_path) as f:
    ss = json.load(f)
have = {s["structure"] for s in ss["structures"]}
for sid, w in (("dungeontrain:lost_city/parking_garage", 4), ("dungeontrain:lost_city/warship", 3)):
    if sid not in have:
        ss["structures"].append({"structure": sid, "weight": w})
dump(ss_path, ss)
print("generated", len(BUILDINGS), "buildings")
