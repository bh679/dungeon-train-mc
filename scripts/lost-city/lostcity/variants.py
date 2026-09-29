"""The designs each DT building is rolled into at placement: our own recipes for our own templates.

A design is a named list of processors (see worldgen/LostCity*Processor.java for each type's fields).
`datagen` orders them stretch → swap → bite → facade → truncate, since the stretch keys on the
template's own blocks and the others dress what it leaves. Stretch periods come from the archetype's
declared floor / bay period, so the manifest, these recipes and LostCityTemplatesTest agree.
"""

from dataclasses import dataclass, field
from typing import Any

from .archetypes import BY_NAME

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


# The weathering the generator bakes in (moss carpets, vines, cracked blocks) stripped for a dry look.
DRY = swap([("moss_carpet", "air"), ("vine", "air"), ("light_gray_concrete_powder", "light_gray_concrete", 0.7),
            ("cracked_stone_bricks", "stone_bricks", 0.5)])

CONCRETE_RUBBLE = ["light_gray_concrete_powder", "gray_concrete", "cobblestone", "gravel", "andesite"]
BRICK_RUBBLE = ["bricks", "cobblestone", "gravel", "cracked_stone_bricks", "andesite"]
STONE_RUBBLE = ["cracked_stone_bricks", "cobblestone", "stone", "gravel", "andesite"]

OFFICE_TINTED = swap([("light_blue_stained_glass", "gray_stained_glass"), ("light_blue_stained_glass_pane", "gray_stained_glass_pane"),
                      ("white_concrete", "light_gray_concrete")])
OFFICE_BRONZE = swap([("light_gray_concrete", "brown_terracotta"), ("white_concrete", "waxed_exposed_copper"),
                      ("light_blue_stained_glass", "brown_stained_glass"), ("gray_concrete", "waxed_oxidized_copper")])
OFFICE_WHITE = swap([("light_gray_concrete", "white_concrete"), ("light_blue_stained_glass", "light_gray_stained_glass"),
                     ("white_concrete", "smooth_quartz"), ("gray_concrete", "light_gray_concrete")])
OFFICE_BLACK = swap([("light_gray_concrete", "black_concrete"), ("light_blue_stained_glass", "black_stained_glass"),
                     ("white_concrete", "gray_concrete"), ("polished_deepslate", "deepslate_tiles")])
OFFICE_BITE = bite(CONCRETE_RUBBLE)
OFFICE_TOP = truncate(0.4, 0.7, CONCRETE_RUBBLE + ["air"])

APT_WHITE = swap([("bricks", "white_concrete"), ("white_terracotta", "light_gray_concrete"), ("brick_slab", "smooth_stone_slab")])
APT_DARK = swap([("bricks", "deepslate_bricks"), ("white_terracotta", "gray_terracotta"), ("brick_slab", "deepslate_brick_slab")])
APT_WARM = swap([("bricks", "terracotta"), ("white_terracotta", "orange_terracotta")])
APT_BITE = bite(BRICK_RUBBLE)
APT_TOP = truncate(0.45, 0.75, BRICK_RUBBLE + ["air"])

HOTEL_TEAL = swap([("orange_terracotta", "cyan_terracotta"), ("white_concrete", "light_gray_concrete")])
HOTEL_SAND = swap([("white_concrete", "smooth_sandstone"), ("orange_terracotta", "red_terracotta"),
                   ("light_blue_stained_glass", "brown_stained_glass")])
HOSP_GREY = swap([("white_concrete", "light_gray_concrete"), ("light_gray_concrete", "gray_concrete")])
HOSP_CREAM = swap([("white_concrete", "smooth_sandstone"), ("light_gray_concrete", "cut_sandstone")])
HALL_SAND = swap([("stone_bricks", "sandstone"), ("cracked_stone_bricks", "cut_sandstone"), ("smooth_quartz", "smooth_sandstone"),
                  ("quartz_pillar", "chiseled_sandstone"), ("smooth_quartz_slab", "smooth_sandstone_slab")])
HALL_DARK = swap([("stone_bricks", "deepslate_bricks"), ("cracked_stone_bricks", "cracked_deepslate_bricks"),
                  ("smooth_quartz", "polished_deepslate"), ("quartz_pillar", "deepslate_tiles"), ("gray_concrete", "black_concrete")])
STRIP_PAINTED = swap([("bricks", "white_concrete"), ("gray_concrete", "light_gray_concrete")])
STRIP_DARK = swap([("bricks", "deepslate_bricks"), ("white_concrete", "light_gray_concrete")])
STATION_DARK = swap([("bricks", "deepslate_bricks"), ("waxed_oxidized_copper", "waxed_weathered_copper")])
STATION_STONE = swap([("bricks", "stone_bricks"), ("gray_concrete", "polished_andesite")])
PETROL_RUSTED = swap([("polished_deepslate", "waxed_oxidized_copper"), ("red_concrete", "brown_terracotta"),
                      ("white_concrete", "light_gray_concrete")])
PETROL_FADED = swap([("red_concrete", "light_gray_concrete"), ("light_gray_concrete", "white_concrete")])
MAST_RUSTED = swap([("iron_block", "waxed_oxidized_copper"), ("polished_deepslate", "waxed_weathered_copper"),
                    ("iron_bars", "chain", 0.4)])
TANK_RUSTED = swap([("white_concrete", "waxed_exposed_copper"), ("polished_deepslate", "waxed_oxidized_copper")])
TANK_DRAINED = swap([("water", "air")])
TANK_DARK = swap([("white_concrete", "gray_concrete")])
ROAD_GREY = swap([("black_concrete", "gray_concrete"), ("gray_concrete", "light_gray_concrete")])
COOL_DARK = swap([("light_gray_concrete", "gray_concrete")])
COOL_CRACKED = swap([("light_gray_concrete", "light_gray_concrete_powder", 0.15)])
SILO_RUSTED = swap([("white_concrete", "waxed_exposed_copper"), ("light_gray_concrete", "waxed_oxidized_copper")])
SILO_GREY = swap([("white_concrete", "light_gray_concrete"), ("light_gray_concrete", "gray_concrete")])

LEDGE_STONE = facade(ledge="smooth_stone_slab")
PILASTER_DEEPSLATE = facade(pilaster="polished_deepslate", every=4)
PILASTER_SAND = facade(pilaster="chiseled_sandstone", every=4)


def designs() -> dict[str, list[Design]]:
    o, a = "office_tower", "apartment_block"
    return {
        o: [Design("shipped"), Design("tinted_tall", (floors(o, (2, 4)), OFFICE_TINTED), 3),
            Design("bronze_setback", (bays(o, -1, min_layer=36), OFFICE_BRONZE, LEDGE_STONE)),
            Design("white_podium", (bays(o, 2, max_layer=15), bays(o, 2, "z", max_layer=15), OFFICE_WHITE)),
            Design("black_block", (bays(o, 2), bays(o, 2, "z"), floors(o, (-4, -2)), OFFICE_BLACK, LEDGE_STONE), 3),
            Design("lofty", (ceilings(o, 2), OFFICE_TINTED, PILASTER_DEEPSLATE)),
            Design("stump", (floors(o, (-6, -4)), OFFICE_BITE)), Design("bitten", (OFFICE_BITE,)),
            Design("topped", (OFFICE_TOP,), 1), Design("dry", (DRY,), 1)],
        a: [Design("shipped"), Design("longer", (bays(a, 3, "z"),)), Design("taller", (floors(a, (2, 4)),)),
            Design("shorter_white", (floors(a, (-4, -2)), APT_WHITE)), Design("dark_long", (bays(a, 4, "z"), APT_DARK)),
            Design("warm", (APT_WARM, LEDGE_STONE)), Design("bitten", (APT_BITE,)), Design("topped", (APT_TOP,), 1),
            Design("dry", (DRY,), 1)],
        "hotel": [Design("shipped"), Design("taller", (floors("hotel", (2, 5)),)),
                  Design("teal_short", (floors("hotel", (-4, -2)), HOTEL_TEAL)), Design("sandstone", (HOTEL_SAND, PILASTER_SAND)),
                  Design("bitten", (bite(CONCRETE_RUBBLE),)), Design("topped", (truncate(0.5, 0.8, CONCRETE_RUBBLE + ["air"]),), 1),
                  Design("dry", (DRY,), 1)],
        "hospital": [Design("shipped"), Design("taller", (floors("hospital", (1, 2)),)),
                     Design("longer", (bays("hospital", 2, "z"),)), Design("grey", (HOSP_GREY,)), Design("cream", (HOSP_CREAM, LEDGE_STONE)),
                     Design("bitten", (bite(CONCRETE_RUBBLE, bites=(2, 3), radius=(0.2, 0.35)),)),
                     Design("collapsed", (truncate(0.4, 0.7, CONCRETE_RUBBLE + ["air"]),), 1), Design("dry", (DRY,), 1)],
        "civic_hall": [Design("shipped"), Design("wider", (bays("civic_hall", 2),)), Design("narrower", (bays("civic_hall", -1),), 1),
                       Design("sandstone", (HALL_SAND,)), Design("dark", (HALL_DARK,)), Design("bitten", (bite(STONE_RUBBLE),)),
                       Design("dry", (DRY,), 1)],
        "shopping_strip": [Design("shipped"), Design("longer", (bays("shopping_strip", 3),)), Design("shorter", (bays("shopping_strip", -3),)),
                           Design("painted", (STRIP_PAINTED,)), Design("dark", (STRIP_DARK,)),
                           Design("bitten", (bite(BRICK_RUBBLE, radius=(0.15, 0.25), pile_max=4),)), Design("dry", (DRY,), 1)],
        "railway_station": [Design("shipped"), Design("longer", (bays("railway_station", 2, "z"),)), Design("dark", (STATION_DARK,)),
                            Design("stone", (STATION_STONE,)), Design("bitten", (bite(BRICK_RUBBLE, radius=(0.15, 0.25), pile_max=4),)),
                            Design("dry", (DRY,), 1)],
        "petrol_station": [Design("shipped", weight=3), Design("rusted", (PETROL_RUSTED,)), Design("faded", (PETROL_FADED,)),
                           Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.15, 0.25), pile_max=3),), 1), Design("dry", (DRY,), 1)],
        "radio_mast": [Design("shipped", weight=3), Design("taller", (floors("radio_mast", (2, 5)),)),
                       Design("shorter", (floors("radio_mast", (-6, -3)),)), Design("rusted", (MAST_RUSTED,))],
        "water_tower": [Design("shipped", weight=3), Design("rusted", (TANK_RUSTED,)), Design("drained", (TANK_DRAINED,)),
                        Design("dark", (TANK_DARK,), 1)],
        "overpass": [Design("shipped", weight=3), Design("longer", (bays("overpass", 1, "z"),)), Design("grey", (ROAD_GREY,), 1),
                     Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3), pile_max=4),)), Design("dry", (DRY,), 1)],
        "cooling_tower": [Design("shipped", weight=3), Design("dark", (COOL_DARK,)),
                          Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.12, 0.2), pile_max=5),)),
                          Design("cracked", (COOL_CRACKED,), 1), Design("dry", (DRY,), 1)],
        "silos": [Design("shipped"), Design("five", (bays("silos", 1),)), Design("three", (bays("silos", -1),)),
                  Design("rusted", (SILO_RUSTED,)), Design("grey", (SILO_GREY,), 1), Design("bitten", (bite(CONCRETE_RUBBLE, radius=(0.2, 0.3)),)),
                  Design("dry", (DRY,), 1)],
    }
