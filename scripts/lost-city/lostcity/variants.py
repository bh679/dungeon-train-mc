"""The designs each DT building is rolled into at placement: our own recipes for our own templates.

A design is a named list of processors (see worldgen/LostCity*Processor.java for each type's fields).
`datagen` orders them stretch → swap → bite → facade → truncate, since the stretch keys on the
template's own blocks and the others dress what it leaves. Stretch periods come from the archetype's
declared floor / bay period, so the manifest, these recipes and LostCityTemplatesTest agree.

Recolours are family swaps: a template's concrete is a `materials` mix of several blocks, so a look
maps every member of that mix onto a member of the target mix (`fam`), never one block alone.
"""

from dataclasses import dataclass, field
from itertools import cycle
from typing import Any

from . import blocks as B
from .archetypes import BY_NAME
from .materials import family

Proc = dict[str, Any]


@dataclass(frozen=True)
class Design:
    name: str
    processors: tuple[Proc, ...] = field(default_factory=tuple)
    weight: int = 2


def mc(name: str) -> str:
    return name if ":" in name else "minecraft:" + name


def swap(pairs) -> Proc:
    """(from, to[, chance]) — a `to` of air removes the block; block state properties carry over."""
    return {"processor_type": "dungeontrain:lost_city_swap",
            "swaps": [{"from": mc(p[0]), "to": mc(p[1]), **({"chance": p[2]} if len(p) > 2 else {})} for p in pairs]}


def fam(*families: tuple[B.BlockState, list[str]]) -> Proc:
    """A recolour: each canonical family's every mix member → the target blocks, cycled."""
    pairs = []
    for canonical, targets in families:
        for member, target in zip(family(canonical), cycle(targets)):
            pairs.append((member.name, target))
    return swap(pairs)


def bite(rubble, bites=(1, 2), radius=(0.3, 0.5), pile_scale=0.8, pile_max=10) -> Proc:
    return {"processor_type": "dungeontrain:lost_city_bite", "bites_min": bites[0], "bites_max": bites[1],
            "radius_min": radius[0], "radius_max": radius[1], "rubble": [mc(r) for r in rubble],
            "pile_scale": pile_scale, "pile_max": pile_max}


def stretch(count, period, axis="y", similarity=0.45, floor_layers=0, min_layer=None, max_layer=None) -> Proc:
    out = {"processor_type": "dungeontrain:lost_city_stretch", "min_floors": count[0], "max_floors": count[1],
           "period_min": period, "period_max": period, "min_similarity": similarity, "axis": axis}
    if floor_layers:
        out["floor_layers"] = floor_layers
    if min_layer is not None:
        out["min_layer"] = min_layer
    if max_layer is not None:
        out["max_layer"] = max_layer
    return out


def facade(ledge=None, pilaster=None, every=6, min_layer=2) -> Proc:
    out = {"processor_type": "dungeontrain:lost_city_facade", "pilaster_every": every, "min_layer": min_layer}
    if ledge:
        out["ledge"] = mc(ledge)
    if pilaster:
        out["pilaster"] = mc(pilaster)
    return out


def truncate(lo, hi, rubble, jagged=3) -> Proc:
    return {"processor_type": "dungeontrain:lost_city_truncate", "min_fraction": lo, "max_fraction": hi,
            "jagged": jagged, "rubble": [mc(r) for r in rubble]}


def floors(name: str, count) -> Proc:
    return stretch(count, BY_NAME[name].spec.floor_period, "y")


def bays(name: str, count, axis="x", **limits) -> Proc:
    spec = BY_NAME[name].spec
    return stretch((count, count), spec.bay_period_x if axis == "x" else spec.bay_period_z, axis, **limits)


def ceilings(name: str, layers: int) -> Proc:
    return stretch((0, 0), BY_NAME[name].spec.floor_period, "y", floor_layers=layers)


# The baked overgrowth stripped for a dry, sun-bleached look.
DRY = swap([("moss_carpet", "air"), ("vine", "air"), ("moss_block", "coarse_dirt"), ("oak_leaves", "air", 0.7),
            ("azalea", "dead_bush"), ("flowering_azalea", "dead_bush"), ("short_grass", "dead_bush", 0.5), ("short_grass", "air", 0.5),
            ("tall_grass", "dead_bush"), ("fern", "dead_bush"), ("hanging_roots", "air"), ("grass_block", "coarse_dirt"),
            ("mossy_cobblestone", "cobblestone", 0.7), ("mossy_stone_bricks", "cracked_stone_bricks", 0.7)])

# Rubble the processors heap at placement: no falling blocks, no concrete.
CONCRETE_RUBBLE = ["cobblestone", "cobbled_deepslate", "tuff", "andesite", "cracked_stone_bricks", "mossy_cobblestone"]
BRICK_RUBBLE = ["bricks", "cobblestone", "cracked_stone_bricks", "mud_bricks", "andesite", "mossy_cobblestone"]
STONE_RUBBLE = ["cracked_stone_bricks", "cobblestone", "mossy_cobblestone", "stone", "tuff", "andesite"]

# Target palettes: every list is muted; the family members of the source mix map onto them in turn.
BRONZE = ["brown_terracotta", "terracotta", "brown_concrete", "waxed_exposed_copper", "mud_bricks", "waxed_weathered_copper"]
PALE = ["light_gray_terracotta", "polished_diorite", "smooth_stone", "calcite", "light_gray_concrete_powder", "cut_sandstone"]
DARK = ["black_concrete", "deepslate_tiles", "polished_deepslate", "deepslate_bricks", "polished_basalt", "gray_concrete"]
GREEN = ["gray_terracotta", "cyan_terracotta", "green_terracotta", "tuff", "dark_prismarine", "gray_concrete"]
SAND = ["smooth_sandstone", "cut_sandstone", "sandstone", "chiseled_sandstone", "smooth_red_sandstone", "cut_sandstone"]
RUST = ["waxed_oxidized_copper", "waxed_weathered_copper", "waxed_exposed_copper", "waxed_oxidized_cut_copper",
        "waxed_weathered_cut_copper", "waxed_exposed_cut_copper"]
TERRACOTTA_WARM = ["terracotta", "brown_terracotta", "orange_terracotta", "red_terracotta", "packed_mud", "mud_bricks"]
GLASS_TINT_DARK = ["black_stained_glass", "gray_stained_glass", "black_stained_glass"]
GLASS_TINT_BROWN = ["brown_stained_glass", "brown_stained_glass", "orange_stained_glass"]
GLASS_TINT_PALE = ["light_gray_stained_glass", "white_stained_glass", "light_gray_stained_glass"]

OFFICE_DARK = fam((B.CONCRETE, DARK), (B.CONCRETE_WHITE, ["gray_concrete", "polished_deepslate", "tuff"]), (B.GLASS, GLASS_TINT_DARK))
OFFICE_BRONZE = fam((B.CONCRETE, BRONZE), (B.CONCRETE_WHITE, ["waxed_exposed_copper", "brown_terracotta", "mud_bricks"]),
                    (B.GLASS, GLASS_TINT_BROWN), (B.CONCRETE_DARK, ["waxed_oxidized_copper", "brown_concrete"]))
OFFICE_PALE = fam((B.CONCRETE, PALE), (B.CONCRETE_WHITE, ["calcite", "polished_diorite", "smooth_stone"]), (B.GLASS, GLASS_TINT_PALE))
OFFICE_GREEN = fam((B.CONCRETE, GREEN), (B.GLASS, ["green_stained_glass", "gray_stained_glass", "cyan_stained_glass"]))
OFFICE_BITE = bite(CONCRETE_RUBBLE)
OFFICE_TOP = truncate(0.4, 0.7, CONCRETE_RUBBLE + ["air"])

APT_PALE = fam((B.BRICK, PALE), (B.TERRACOTTA_WHITE, ["light_gray_concrete", "smooth_stone", "polished_diorite"]))
APT_DARK = fam((B.BRICK, DARK), (B.TERRACOTTA_WHITE, ["gray_terracotta", "polished_deepslate", "tuff"]))
APT_WARM = fam((B.BRICK, TERRACOTTA_WARM), (B.TERRACOTTA_WHITE, ["orange_terracotta", "terracotta", "cut_sandstone"]))
APT_BITE = bite(BRICK_RUBBLE)
APT_TOP = truncate(0.45, 0.75, BRICK_RUBBLE + ["air"])

HOTEL_TEAL = fam((B.TERRACOTTA_ORANGE, GREEN), (B.CONCRETE_WHITE, PALE))
HOTEL_SAND = fam((B.CONCRETE_WHITE, SAND), (B.TERRACOTTA_ORANGE, ["red_terracotta", "brown_terracotta", "terracotta"]),
                 (B.GLASS, GLASS_TINT_BROWN))
HOTEL_DARK = fam((B.CONCRETE_WHITE, DARK), (B.TERRACOTTA_ORANGE, ["gray_terracotta", "black_terracotta", "brown_terracotta"]))
HOSP_GREY = fam((B.CONCRETE_WHITE, ["light_gray_concrete", "gray_concrete", "andesite", "tuff"]), (B.CONCRETE, DARK))
HOSP_CREAM = fam((B.CONCRETE_WHITE, SAND), (B.CONCRETE, ["cut_sandstone", "smooth_sandstone", "sandstone"]))
HALL_SAND = fam((B.STONE_BRICKS, SAND), (B.QUARTZ, ["smooth_sandstone", "cut_sandstone"]), (B.QUARTZ_PILLAR, ["chiseled_sandstone"]))
HALL_DARK = fam((B.STONE_BRICKS, DARK), (B.QUARTZ, ["polished_deepslate", "deepslate_tiles"]), (B.QUARTZ_PILLAR, ["deepslate_tiles"]),
                (B.CONCRETE_DARK, ["black_concrete"]))
STRIP_PALE = fam((B.BRICK, PALE), (B.CONCRETE_DARK, ["light_gray_concrete", "gray_concrete"]))
STRIP_DARK = fam((B.BRICK, DARK), (B.SIGN_BOARD, ["gray_concrete", "light_gray_concrete"]))
STATION_DARK = fam((B.BRICK, DARK), (B.COPPER, ["waxed_weathered_copper", "waxed_exposed_copper"]))
STATION_STONE = fam((B.BRICK, ["stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "andesite"]),
                    (B.CONCRETE_DARK, ["polished_andesite", "andesite"]))
PETROL_RUSTED = fam((B.STEEL_DARK, RUST), (B.RED_PAINT, ["brown_terracotta", "red_terracotta"]), (B.CONCRETE_WHITE, PALE))
PETROL_FADED = fam((B.RED_PAINT, ["light_gray_terracotta", "gray_terracotta"]), (B.CONCRETE, PALE))
MAST_RUSTED = fam((B.STEEL, RUST), (B.STEEL_DARK, ["waxed_weathered_copper", "waxed_oxidized_copper"]))
MAST_DARK = fam((B.STEEL, DARK), (B.STEEL_DARK, ["black_concrete", "polished_basalt"]))
TANK_RUSTED = fam((B.CONCRETE_WHITE, RUST), (B.STEEL_DARK, ["waxed_oxidized_copper", "waxed_weathered_copper"]))
TANK_DRAINED = swap([("water", "air")])
TANK_DARK = fam((B.CONCRETE_WHITE, DARK))
ROAD_GREY = fam((B.ASPHALT, ["gray_concrete", "light_gray_concrete", "andesite"]), (B.CONCRETE_DARK, ["light_gray_concrete", "andesite"]))
COOL_DARK = fam((B.CONCRETE, DARK))
COOL_SAND = fam((B.CONCRETE, SAND))
SILO_RUSTED = fam((B.CONCRETE_WHITE, RUST), (B.CONCRETE, ["waxed_oxidized_copper", "waxed_weathered_copper"]))
SILO_DARK = fam((B.CONCRETE_WHITE, DARK), (B.CONCRETE, ["gray_concrete", "tuff"]))

LEDGE_STONE = facade(ledge="smooth_stone_slab")
LEDGE_DEEPSLATE = facade(ledge="deepslate_tile_slab")
PILASTER_DEEPSLATE = facade(pilaster="polished_deepslate", every=4)
PILASTER_SAND = facade(pilaster="chiseled_sandstone", every=4)


def designs() -> dict[str, list[Design]]:
    o, a = "office_tower", "apartment_block"
    return {
        o: [Design("shipped"), Design("dark_tall", (floors(o, (2, 4)), OFFICE_DARK, LEDGE_DEEPSLATE), 3),
            Design("bronze_setback", (bays(o, -1, min_layer=36), OFFICE_BRONZE, LEDGE_STONE)),
            Design("pale_podium", (bays(o, 2, max_layer=15), bays(o, 2, "z", max_layer=15), OFFICE_PALE)),
            Design("green_block", (bays(o, 2), bays(o, 2, "z"), floors(o, (-4, -2)), OFFICE_GREEN, LEDGE_STONE), 3),
            Design("lofty", (ceilings(o, 2), OFFICE_DARK, PILASTER_DEEPSLATE)),
            Design("stump", (floors(o, (-6, -4)), OFFICE_BITE)), Design("bitten", (OFFICE_BITE,)),
            Design("topped", (OFFICE_TOP,), 1), Design("dry", (DRY,), 1)],
        a: [Design("shipped"), Design("longer", (bays(a, 3, "z"),)), Design("taller", (floors(a, (2, 4)),)),
            Design("shorter_pale", (floors(a, (-4, -2)), APT_PALE)), Design("dark_long", (bays(a, 4, "z"), APT_DARK)),
            Design("warm", (APT_WARM, LEDGE_STONE)), Design("bitten", (APT_BITE,)), Design("topped", (APT_TOP,), 1),
            Design("dry", (DRY,), 1)],
        "hotel": [Design("shipped"), Design("taller", (floors("hotel", (2, 5)),)),
                  Design("teal_short", (floors("hotel", (-4, -2)), HOTEL_TEAL)), Design("sandstone", (HOTEL_SAND, PILASTER_SAND)),
                  Design("dark", (HOTEL_DARK,)), Design("bitten", (bite(CONCRETE_RUBBLE),)),
                  Design("topped", (truncate(0.5, 0.8, CONCRETE_RUBBLE + ["air"]),), 1), Design("dry", (DRY,), 1)],
        "hospital": [Design("shipped"), Design("taller", (floors("hospital", (1, 2)),)),
                     Design("longer", (bays("hospital", 2, "z"),)), Design("grey", (HOSP_GREY,)), Design("cream", (HOSP_CREAM, LEDGE_STONE)),
                     Design("bitten", (bite(CONCRETE_RUBBLE, bites=(2, 3), radius=(0.2, 0.35)),)),
                     Design("collapsed", (truncate(0.4, 0.7, CONCRETE_RUBBLE + ["air"]),), 1), Design("dry", (DRY,), 1)],
        "civic_hall": [Design("shipped"), Design("wider", (bays("civic_hall", 2),)), Design("narrower", (bays("civic_hall", -1),), 1),
                       Design("sandstone", (HALL_SAND,)), Design("dark", (HALL_DARK,)), Design("bitten", (bite(STONE_RUBBLE),)),
                       Design("dry", (DRY,), 1)],
        "shopping_strip": [Design("shipped"), Design("longer", (bays("shopping_strip", 3),)), Design("shorter", (bays("shopping_strip", -3),)),
                           Design("pale", (STRIP_PALE,)), Design("dark", (STRIP_DARK,)),
                           Design("bitten", (bite(BRICK_RUBBLE, radius=(0.15, 0.25), pile_max=4),)), Design("dry", (DRY,), 1)],
        "railway_station": [Design("shipped"), Design("longer", (bays("railway_station", 2, "z"),)), Design("dark", (STATION_DARK,)),
                            Design("stone", (STATION_STONE,)), Design("bitten", (bite(BRICK_RUBBLE, radius=(0.15, 0.25), pile_max=4),)),
                            Design("dry", (DRY,), 1)],
        "petrol_station": [Design("shipped", weight=3), Design("rusted", (PETROL_RUSTED,)), Design("faded", (PETROL_FADED,)),
                           Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.15, 0.25), pile_max=3),), 1), Design("dry", (DRY,), 1)],
        "radio_mast": [Design("shipped", weight=3), Design("taller", (floors("radio_mast", (2, 5)),)),
                       Design("shorter", (floors("radio_mast", (-6, -3)),)), Design("rusted", (MAST_RUSTED,)), Design("dark", (MAST_DARK,), 1)],
        "water_tower": [Design("shipped", weight=3), Design("rusted", (TANK_RUSTED,)), Design("drained", (TANK_DRAINED,)),
                        Design("dark", (TANK_DARK,), 1)],
        "overpass": [Design("shipped", weight=3), Design("grey", (ROAD_GREY,), 1),
                     Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3), pile_max=4),)), Design("dry", (DRY,), 1)],
        "cooling_tower": [Design("shipped", weight=3), Design("dark", (COOL_DARK,)), Design("sand", (COOL_SAND,), 1),
                          Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.12, 0.2), pile_max=5),)), Design("dry", (DRY,), 1)],
        "silos": [Design("shipped"), Design("five", (bays("silos", 1),)), Design("three", (bays("silos", -1),)),
                  Design("rusted", (SILO_RUSTED,)), Design("dark", (SILO_DARK,), 1), Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3)),)),
                  Design("dry", (DRY,), 1)],
        "leaning_tower": [Design("shipped", weight=3), Design("dark", (OFFICE_DARK,)), Design("pale", (OFFICE_PALE,)),
                          Design("bronze", (OFFICE_BRONZE,), 1), Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3)),)),
                          Design("dry", (DRY,), 1)],
        "fallen_block": [Design("shipped", weight=3), Design("longer", (bays("fallen_block", 2, "z"),)), Design("dark", (APT_DARK,)),
                         Design("pale", (APT_PALE,)), Design("warm", (APT_WARM,), 1),
                         Design("bitten", (bite(BRICK_RUBBLE, radius=(0.15, 0.25), pile_max=4),)), Design("dry", (DRY,), 1)],
        "snapped_tower": [Design("shipped", weight=3), Design("dark", (OFFICE_DARK,)), Design("pale", (OFFICE_PALE,)),
                          Design("green", (OFFICE_GREEN,), 1), Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3)),)),
                          Design("dry", (DRY,), 1)],
    }
