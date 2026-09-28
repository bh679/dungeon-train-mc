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


def stretch(floors, period=(4, 16), similarity=0.45, axis="y", floor_layers=0, min_layer=None, max_layer=None):
    """Taller / shorter (axis y), or wider / narrower (axis x or z, in template terms), by repeating or
    removing the building's repeating band: a floor, or a window bay. floor_layers != 0 instead raises or
    lowers every ceiling by that many layers; min_layer / max_layer confine a bay stretch to a height range
    (a podium when the lower layers widen, a setback when the upper ones narrow)."""
    out = {"processor_type": "dungeontrain:lost_city_stretch", "min_floors": floors[0], "max_floors": floors[1],
           "period_min": period[0], "period_max": period[1], "min_similarity": similarity, "axis": axis}
    if floor_layers:
        out["floor_layers"] = floor_layers
    if min_layer is not None:
        out["min_layer"] = min_layer
    if max_layer is not None:
        out["max_layer"] = max_layer
    return out


def facade(ledge=None, pilaster=None, every=6, min_layer=2):
    """A cornice block outside every floor layer and / or a pilaster block every `every` along the walls."""
    out = {"processor_type": "dungeontrain:lost_city_facade", "pilaster_every": every, "min_layer": min_layer}
    if ledge:
        out["ledge"] = mc(ledge)
    if pilaster:
        out["pilaster"] = mc(pilaster)
    return out


def ceilings(n):
    return stretch((0, 0), period=(4, 16), floor_layers=n)


def bays(n, axis="x", period=(4, 8), min_layer=None, max_layer=None):
    return stretch((n, n), period=period, axis=axis, min_layer=min_layer, max_layer=max_layer)


def floors(n):
    return stretch((n, n))


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
BLACK_TUFF = swap([
    ("smooth_basalt", "tuff_bricks"), ("deepslate", "tuff"), ("deepslate_tiles", "polished_tuff"),
    ("cracked_deepslate_tiles", "chiseled_tuff"), ("polished_deepslate", "polished_tuff"),
    ("polished_blackstone", "tuff_bricks"), ("polished_blackstone_slab", "tuff_brick_slab"),
    ("polished_blackstone_stairs", "tuff_brick_stairs"), ("deepslate_bricks", "tuff_bricks"),
    ("cracked_deepslate_bricks", "tuff"), ("black_stained_glass_pane", "brown_stained_glass_pane"),
    ("black_stained_glass", "brown_stained_glass"), ("crimson_stairs", "dark_oak_stairs"),
])
BLACK_COPPER = swap([
    ("smooth_basalt", "waxed_weathered_copper"), ("deepslate", "waxed_oxidized_copper"),
    ("deepslate_tiles", "waxed_weathered_cut_copper"), ("cracked_deepslate_tiles", "waxed_oxidized_cut_copper"),
    ("polished_deepslate", "waxed_exposed_cut_copper"), ("polished_blackstone", "waxed_exposed_copper"),
    ("polished_blackstone_slab", "waxed_weathered_cut_copper_slab"), ("polished_blackstone_stairs", "waxed_weathered_cut_copper_stairs"),
    ("deepslate_bricks", "waxed_oxidized_copper"), ("cracked_deepslate_bricks", "waxed_oxidized_copper"),
    ("black_stained_glass_pane", "cyan_stained_glass_pane"), ("black_stained_glass", "cyan_stained_glass"),
])
# the towers' existing ledges are slabs and stairs on the facade: stripping them bares the frame
STRIP = swap([("polished_blackstone_slab", "air"), ("spruce_slab", "air"), ("smooth_stone_slab", "air"),
              ("polished_andesite_slab", "air"), ("stone_slab", "air"), ("crimson_stairs", "air"), ("dark_oak_slab", "air"),
              ("polished_blackstone_button", "air"), ("cobblestone_slab", "air"), ("mossy_cobblestone_slab", "air")])

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
RED_DARK = swap([
    ("bricks", "deepslate_bricks"), ("brick_slab", "deepslate_brick_slab"), ("brick_stairs", "deepslate_brick_stairs"),
    ("granite", "deepslate"), ("polished_granite", "polished_deepslate"), ("terracotta", "black_terracotta"),
    ("waxed_copper_block", "waxed_exposed_copper"), ("dripstone_block", "cobbled_deepslate"),
    ("stripped_acacia_wood", "stripped_dark_oak_wood"),
])
RED_WHITE = swap([
    ("bricks", "quartz_bricks"), ("brick_slab", "quartz_slab"), ("brick_stairs", "quartz_stairs"),
    ("granite", "calcite"), ("polished_granite", "smooth_quartz"), ("terracotta", "light_gray_terracotta"),
    ("waxed_copper_block", "waxed_weathered_copper"), ("dripstone_block", "diorite"),
    ("stripped_acacia_wood", "stripped_birch_wood"), ("black_stained_glass_pane", "light_gray_stained_glass_pane"),
])

TALL_CLEAR = swap([("green_stained_glass", "light_gray_stained_glass"),
                   ("green_stained_glass_pane", "light_gray_stained_glass_pane"), ("mossy_cobblestone", "cobblestone", 0.7)])
TALL_DARK = swap([("green_stained_glass", "gray_stained_glass"), ("green_stained_glass_pane", "gray_stained_glass_pane"),
                  ("stone", "deepslate"), ("deepslate_tiles", "polished_deepslate"), ("smooth_stone", "polished_deepslate"),
                  ("smooth_stone_slab", "polished_deepslate_slab"), ("mossy_cobblestone", "cobbled_deepslate")])
TALL_BLOWN = swap([("green_stained_glass", "air", 0.6), ("green_stained_glass_pane", "air", 0.6), ("cobweb", "air"),
                   ("chain", "air", 0.5), ("deepslate_tiles", "cracked_deepslate_tiles", 0.4)])
TALL_TOP = truncate(0.35, 0.6, ["mossy_cobblestone", "cracked_deepslate_tiles", "cobblestone", "air"])
TALL_BRONZE = swap([("green_stained_glass", "brown_stained_glass"), ("green_stained_glass_pane", "brown_stained_glass_pane"),
                    ("mossy_cobblestone", "tuff_bricks"), ("stone", "tuff"), ("deepslate_tiles", "polished_tuff"),
                    ("smooth_stone", "polished_tuff"), ("smooth_stone_slab", "polished_tuff_slab")])
TALL_ICE = swap([("green_stained_glass", "light_blue_stained_glass"), ("green_stained_glass_pane", "light_blue_stained_glass_pane"),
                 ("mossy_cobblestone", "diorite"), ("stone", "calcite"), ("deepslate_tiles", "smooth_quartz"),
                 ("smooth_stone", "quartz_bricks"), ("smooth_stone_slab", "quartz_slab")])

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
GARAGE_DARK = swap([("gray_concrete", "polished_deepslate"), ("black_terracotta", "black_concrete"),
                    ("red_terracotta", "gray_terracotta"), ("blue_terracotta", "cyan_terracotta"), ("white_wool", "light_gray_wool")])

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

TALLER = stretch((2, 6))
SHORTER = stretch((-4, -1))
TALL_TALLER = stretch((1, 3))            # already 159 tall; a floor or three more
TALL_SHORTER = stretch((-8, -3))
# Designs. Each pool entry composes footprint, height, ceilings, palette, facade and decay so it reads as
# its own building rather than a recolour of the one beside it. "shipped"/"dry" use the shared lists.
# The towers' facades are uniform along x and z in full-block terms, so a bay is simply 4 blocks (3 for
# the garage's parking bays); floors are 7 layers in the black/red towers and 6 in the tall one.
LEDGE_ANDESITE = facade(ledge="polished_andesite_slab")
LEDGE_TUFF = facade(ledge="tuff_brick_slab")
LEDGE_SAND = facade(ledge="smooth_sandstone_slab")
LEDGE_COPPER = facade(ledge="waxed_oxidized_cut_copper_slab")
LEDGE_STONE = facade(ledge="smooth_stone_slab")
PILASTER_QUARTZ = facade(pilaster="quartz_bricks", every=5)
PILASTER_DEEPSLATE = facade(pilaster="polished_deepslate", every=4)
PILASTER_TUFF = facade(pilaster="chiseled_tuff", every=6)
PILASTER_MUD = facade(pilaster="mud_bricks", every=5)

BLACK_DESIGNS = [
    ("shipped", [], 2),
    ("grey_block", [bays(3), bays(2, "z"), floors(-3), BLACK_GREY, LEDGE_ANDESITE], 3),
    ("calcite_spire", [bays(-2), bays(-2, "z"), floors(4), ceilings(2), BLACK_WHITE, PILASTER_QUARTZ], 3),
    ("brutalist", [bays(2), floors(-2), ceilings(-1), BLACK_GREY, STRIP], 2),
    ("tuff_podium", [bays(3, max_layer=30), BLACK_TUFF, LEDGE_TUFF], 2),
    ("copper_setback", [bays(-2, min_layer=58), floors(2), BLACK_COPPER], 2),
    ("lofty", [bays(-1), ceilings(3), PILASTER_DEEPSLATE], 2),
    ("stump", [floors(-4), BLACK_BITE, BLACK_TUFF], 2),
    ("bitten", [BLACK_BITE], 2),
    ("topped", [BLACK_TOP], 1),
    ("dry", [], 1),
]
RED_DESIGNS = [
    ("shipped", [], 2),
    ("sandstone_block", [bays(3), bays(2, "z"), floors(-3), RED_SAND, LEDGE_SAND], 3),
    ("dark_spire", [bays(-2), bays(-2, "z"), floors(4), ceilings(2), RED_DARK, PILASTER_DEEPSLATE], 3),
    ("oxidised_podium", [bays(3, max_layer=30), RED_OXID, LEDGE_COPPER], 2),
    ("white_setback", [bays(-2, min_layer=58), floors(2), RED_WHITE], 2),
    ("brutalist", [bays(2), floors(-2), ceilings(-1), RED_DARK, STRIP], 2),
    ("lofty", [ceilings(3), RED_SAND, PILASTER_TUFF], 2),
    ("stump", [floors(-4), RED_BITE, RED_DARK], 2),
    ("bitten", [RED_BITE], 2),
    ("topped", [RED_TOP], 1),
    ("dry", [], 1),
]
RUINED_BLACK_DESIGNS = [("shipped", [], 2), ("grey_block", [bays(3), bays(2, "z"), floors(-3), BLACK_GREY, LEDGE_ANDESITE], 2),
                        ("white_spire", [bays(-2), floors(3), ceilings(2), BLACK_WHITE, PILASTER_QUARTZ], 2),
                        ("tuff_podium", [bays(3, max_layer=30), BLACK_TUFF, LEDGE_TUFF], 2), ("copper_lofty", [ceilings(2), BLACK_COPPER], 2),
                        ("stump", [floors(-4), BLACK_BITE], 2), ("bitten", [BLACK_BITE], 2), ("topped", [BLACK_TOP], 1), ("dry", [], 1)]
RUINED_RED_DESIGNS = [("shipped", [], 2), ("sandstone_block", [bays(3), bays(2, "z"), floors(-3), RED_SAND, LEDGE_SAND], 2),
                      ("dark_spire", [bays(-2), floors(3), ceilings(2), RED_DARK, PILASTER_DEEPSLATE], 2),
                      ("oxidised_podium", [bays(3, max_layer=30), RED_OXID, LEDGE_COPPER], 2), ("white_lofty", [ceilings(2), RED_WHITE], 2),
                      ("stump", [floors(-4), RED_BITE], 2), ("bitten", [RED_BITE], 2), ("topped", [RED_TOP], 1), ("dry", [], 1)]
TALL_DESIGNS = [
    ("shipped", [], 2),
    ("bronze_block", [bays(3), stretch((-7, -5)), TALL_BRONZE, LEDGE_TUFF], 3),
    ("ice_needle", [bays(-2), stretch((2, 3)), ceilings(1), TALL_ICE, STRIP], 3),
    ("dark_podium", [bays(3, max_layer=40), TALL_DARK, PILASTER_DEEPSLATE], 2),
    ("clear_setback", [bays(-2, min_layer=80), TALL_CLEAR], 2),
    ("blown_stump", [stretch((-9, -7)), TALL_BLOWN, TALL_BITE], 2),
    ("lofty", [stretch((-4, -3)), ceilings(3), TALL_CLEAR, LEDGE_STONE], 2),
    ("bitten", [TALL_BITE], 2),
    ("topped", [TALL_TOP], 1),
    ("wide_dry", [bays(2), stretch((-5, -4)), DRY], 1),
]
RUIN_DESIGNS = [("shipped", [], 2), ("deepslate", [RUIN_DEEP], 2), ("mud_pilasters", [RUIN_MUD, PILASTER_MUD], 2),
                ("collapsed", [RUIN_COLLAPSE], 2), ("bitten", [RUIN_BITE], 3), ("deepslate_ledges", [RUIN_DEEP, facade(ledge="deepslate_tile_slab")], 2), ("dry", [], 1)]
PLANT_DESIGNS = [("shipped", [], 3), ("mud", [RUIN_MUD], 2), ("deepslate", [RUIN_DEEP], 2), ("crumbling", [PLANT_CRUMBLE], 2), ("bitten", [PLANT_BITE], 2), ("dry", [], 1)]
GARAGE_DESIGNS = [
    ("shipped", [], 2),
    ("light_sprawl", [bays(5, period=(3, 3)), bays(3, "z", period=(3, 3)), GARAGE_LIGHT, LEDGE_STONE], 3),
    ("dark_compact", [bays(-8, period=(3, 3)), bays(-10, "z", period=(3, 3)), GARAGE_DARK], 3),
    ("collapsed", [GARAGE_TOP], 2),
    ("bitten", [GARAGE_BITE], 2),
    ("small_dry", [bays(-6, "z", period=(3, 3)), DRY], 1),
]
SHIP_DESIGNS = [("shipped", [], 3), ("rusted", [SHIP_RUST], 2), ("grey", [SHIP_GREY], 2)]
WARE_DESIGNS = [("shipped", [], 2), ("grey_long", [bays(3, "z", period=(3, 6)), WARE_GREY], 2), ("wide_ledges", [bays(2, period=(3, 6)), LEDGE_STONE], 2),
                ("long_pilasters", [bays(4, "z", period=(3, 6)), WARE_GREY, facade(pilaster="stone_bricks", every=4)], 2), ("dry", [], 1)]

BUILDINGS = {
    "blackskyscraper": ("black_skyscraperlt", BLACK_DESIGNS),
    "ruinedblackskyscraper": ("ruined_black_skyscraperlt", RUINED_BLACK_DESIGNS),
    "redskyscraper": ("red_skyscraperlt", RED_DESIGNS),
    "ruindedredskyscraper": ("ruinded_red_skyscraperlt", RUINED_RED_DESIGNS),
    "tallskyscraper": ("tall_skyscraperlt", TALL_DESIGNS),
    "ruinedskyscraper": ("ruined_skyscraperlt", RUIN_DESIGNS),
    "powerplant": ("power_plantlt", PLANT_DESIGNS),
    "parking_garage": ("parking_garagelt", GARAGE_DESIGNS),
    "warship": ("warshiplt", SHIP_DESIGNS),
    "warehouse": ("warehouselt", WARE_DESIGNS),
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
