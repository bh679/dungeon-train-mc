"""Block states and the material palettes the archetypes build from.

A `BlockState` is a plain, hashable value: the namespaced block name plus a sorted tuple of
properties. Palettes are module constants so every archetype draws from the same vocabulary and a
typo is caught in one place (see `test_blocks.py`, which checks every name against `KNOWN`).
"""

from typing import NamedTuple


class BlockState(NamedTuple):
    name: str
    props: tuple[tuple[str, str], ...] = ()

    def with_props(self, **props: str) -> "BlockState":
        merged = dict(self.props)
        merged.update({k: str(v) for k, v in props.items()})
        return BlockState(self.name, tuple(sorted(merged.items())))


def block(name: str, **props: str) -> BlockState:
    """`block("stone_bricks")`, `block("oak_stairs", facing="north", half="top")`."""
    full = name if ":" in name else "minecraft:" + name
    return BlockState(full, tuple(sorted((k, str(v)) for k, v in props.items())))


AIR = block("air")

# Pad-layer blocks LostCityGroundProcessor.BASE reads as natural ground: they yield to the world's own
# terrain where it already stands. Keep this list in step with the Java set.
PAD_NATURAL = frozenset(
    "minecraft:" + n
    for n in (
        "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud", "moss_block",
        "stone", "andesite", "granite", "diorite", "tuff", "deepslate", "dripstone_block", "gravel",
        "sand", "red_sand", "clay", "dirt_path", "farmland", "snow_block", "water",
    )
)

# Paving deliberately outside PAD_NATURAL, so an apron survives the world's ground and bite rubble
# has a floor to land on.
PAVEMENT = (block("cobblestone"), block("stone_bricks"), block("cracked_stone_bricks"), block("polished_andesite"))
GROUND = (block("grass_block"), block("grass_block"), block("grass_block"), block("dirt"), block("coarse_dirt"), block("gravel"))

# Material families. Each archetype picks a few; the variant recipes recolour them at placement.
CONCRETE = block("light_gray_concrete")
CONCRETE_DARK = block("gray_concrete")
CONCRETE_WHITE = block("white_concrete")
CONCRETE_POWDER = block("light_gray_concrete_powder")
GLASS = block("light_blue_stained_glass")
GLASS_PANE = block("light_blue_stained_glass_pane")
GLASS_CLEAR = block("glass")
GLASS_CLEAR_PANE = block("glass_pane")
GLASS_DARK = block("gray_stained_glass")
BRICK = block("bricks")
BRICK_SLAB = block("brick_slab")
TERRACOTTA = block("terracotta")
TERRACOTTA_WHITE = block("white_terracotta")
TERRACOTTA_ORANGE = block("orange_terracotta")
STEEL = block("iron_block")
STEEL_BARS = block("iron_bars")
STEEL_CHAIN = block("chain")
STEEL_WALL = block("andesite_wall")
STEEL_DARK = block("polished_deepslate")
SMOOTH_STONE = block("smooth_stone")
SMOOTH_STONE_SLAB = block("smooth_stone_slab")
STONE_BRICKS = block("stone_bricks")
CRACKED_STONE_BRICKS = block("cracked_stone_bricks")
STONE_BRICK_SLAB = block("stone_brick_slab")
STONE_BRICK_STAIRS = block("stone_brick_stairs")
STONE_BRICK_WALL = block("stone_brick_wall")
QUARTZ = block("smooth_quartz")
QUARTZ_PILLAR = block("quartz_pillar")
QUARTZ_SLAB = block("smooth_quartz_slab")
QUARTZ_STAIRS = block("smooth_quartz_stairs")
COPPER = block("waxed_oxidized_copper")
COPPER_CUT = block("waxed_oxidized_cut_copper")
COPPER_EXPOSED = block("waxed_exposed_copper")
COPPER_SLAB = block("waxed_oxidized_cut_copper_slab")
COPPER_STAIRS = block("waxed_oxidized_cut_copper_stairs")
ASPHALT = block("black_concrete")
ROAD_LINE = block("white_concrete")
ROAD_LINE_YELLOW = block("yellow_concrete")
RED_PAINT = block("red_concrete")
RED_LIGHT = block("redstone_lamp")
SIGN_BOARD = block("white_concrete")
MOSS_CARPET = block("moss_carpet")
VINE = block("vine")
COBWEB = block("cobweb")
LADDER = block("ladder")
LANTERN = block("lantern")
WATER = block("water")
DEAD_BUSH = block("dead_bush")
SHORT_GRASS = block("short_grass")
OAK_LEAVES = block("oak_leaves", persistent="true")
AZALEA = block("azalea")
POLISHED_BLACKSTONE = block("polished_blackstone")
POLISHED_BLACKSTONE_BUTTON = block("polished_blackstone_button")
DARK_OAK_PLANKS = block("dark_oak_planks")
SPRUCE_PLANKS = block("spruce_planks")
SPRUCE_STAIRS = block("spruce_stairs")
SPRUCE_SLAB = block("spruce_slab")
SPRUCE_TRAPDOOR = block("spruce_trapdoor")
BELL = block("bell")
TARGET = block("target")
STRIPPED_SPRUCE_LOG = block("stripped_spruce_log")
BARREL = block("barrel")
CAULDRON = block("cauldron")
HOPPER = block("hopper")
RAIL = block("rail")
GRAVEL = block("gravel")
COBBLESTONE = block("cobblestone")
COBBLESTONE_WALL = block("cobblestone_wall")
ANDESITE = block("andesite")
POLISHED_ANDESITE = block("polished_andesite")
POLISHED_ANDESITE_SLAB = block("polished_andesite_slab")
DEEPSLATE_TILES = block("deepslate_tiles")

# Every block name used above; test_blocks.py asserts each palette entry is in here so a typo cannot
# silently load as air in-game (NbtUtils.readBlockState falls back to air with only a log line).
KNOWN = frozenset(
    v.name for v in list(globals().values()) if isinstance(v, BlockState)
) | frozenset(b.name for b in PAVEMENT + GROUND)
