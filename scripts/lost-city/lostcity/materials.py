"""Material mixes: every canonical block an archetype draws with becomes a seeded blend of related blocks.

This is how the Lost City's originals read as weathered rather than flat: a wall is never one
block but three to six — the base, a darker sibling, a cracked or mossy variant, a stone that
reads as patching. Archetypes draw with the canonical block (`blocks.CONCRETE`, `blocks.BRICK`, …)
and `texturize()` resolves each cell through its family's mix, in patches and streaks from value
noise so the result is byte-identical per run. Muted and textured throughout: concrete is a trace,
nothing here falls, and nothing is worth mining.
"""

from .blocks import (AIR, ANDESITE, ASPHALT, BRICK, COBBLESTONE, CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, COPPER, COPPER_CUT,
                     COPPER_EXPOSED, CRACKED_STONE_BRICKS, DEEPSLATE_TILES, GLASS, GLASS_CLEAR, GLASS_DARK, POLISHED_ANDESITE,
                     QUARTZ, QUARTZ_PILLAR, RED_PAINT, SIGN_BOARD, SMOOTH_STONE, STEEL, STEEL_DARK, STONE_BRICKS, TERRACOTTA,
                     TERRACOTTA_ORANGE, TERRACOTTA_WHITE, BlockState, block)
from .canvas import Canvas


class Mix:
    """Weighted blocks; the first member is the family's base."""

    def __init__(self, *members: tuple[BlockState, int]):
        self.members = tuple(members)
        self.blocks = tuple(m for m, _ in members)


MIXES: dict[BlockState, Mix] = {
    # rendered walls: stones and tuff, a trace of concrete
    CONCRETE: Mix((ANDESITE, 24), (block("tuff"), 20), (POLISHED_ANDESITE, 14), (block("polished_tuff"), 10),
                  (block("tuff_bricks"), 10), (SMOOTH_STONE, 8), (block("mud_bricks"), 6), (CONCRETE, 8)),
    CONCRETE_DARK: Mix((block("cobbled_deepslate"), 26), (block("deepslate_bricks"), 20), (block("polished_basalt"), 14),
                       (block("tuff"), 12), (DEEPSLATE_TILES, 10), (block("polished_deepslate"), 9),
                       (block("cracked_deepslate_bricks"), 5), (CONCRETE_DARK, 4)),
    # "white" is demoted to pale stones — the muted palette has no white walls
    CONCRETE_WHITE: Mix((block("polished_diorite"), 24), (block("diorite"), 14), (SMOOTH_STONE, 15), (block("light_gray_terracotta"), 15),
                        (block("calcite"), 12), (block("cut_sandstone"), 10), (block("end_stone_bricks"), 5), (CONCRETE, 5)),
    GLASS: Mix((block("light_gray_stained_glass"), 38), (block("gray_stained_glass"), 22), (block("cyan_stained_glass"), 6), (AIR, 34)),
    GLASS_CLEAR: Mix((GLASS_CLEAR, 45), (block("light_gray_stained_glass"), 15), (AIR, 40)),
    GLASS_DARK: Mix((GLASS_DARK, 50), (block("black_stained_glass"), 20), (AIR, 30)),
    BRICK: Mix((BRICK, 45), (block("mud_bricks"), 14), (TERRACOTTA, 10), (block("brown_terracotta"), 8), (block("packed_mud"), 8),
               (block("mossy_stone_bricks"), 7), (CRACKED_STONE_BRICKS, 8)),
    TERRACOTTA_WHITE: Mix((block("light_gray_terracotta"), 40), (SMOOTH_STONE, 18), (block("cut_sandstone"), 12), (block("mud_bricks"), 10),
                          (block("polished_diorite"), 12), (block("white_terracotta"), 8)),
    TERRACOTTA_ORANGE: Mix((TERRACOTTA, 40), (block("brown_terracotta"), 25), (block("orange_terracotta"), 15),
                           (block("red_terracotta"), 12), (block("packed_mud"), 8)),
    STONE_BRICKS: Mix((STONE_BRICKS, 42), (CRACKED_STONE_BRICKS, 20), (block("mossy_stone_bricks"), 15), (ANDESITE, 10),
                      (COBBLESTONE, 8), (block("chiseled_stone_bricks"), 5)),
    QUARTZ: Mix((block("smooth_sandstone"), 40), (block("cut_sandstone"), 20), (block("polished_diorite"), 20), (block("calcite"), 10),
                (SMOOTH_STONE, 10)),
    QUARTZ_PILLAR: Mix((block("chiseled_sandstone"), 40), (QUARTZ_PILLAR, 30), (block("polished_diorite"), 20),
                       (block("chiseled_stone_bricks"), 10)),
    STEEL: Mix((STEEL, 40), (DEEPSLATE_TILES, 20), (block("waxed_exposed_cut_copper"), 15), (STEEL_DARK, 15),
               (block("cobbled_deepslate"), 10)),
    STEEL_DARK: Mix((STEEL_DARK, 45), (DEEPSLATE_TILES, 20), (STEEL, 15), (block("deepslate_bricks"), 10),
                    (block("waxed_oxidized_copper"), 10)),
    COPPER: Mix((COPPER, 50), (block("waxed_weathered_copper"), 25), (COPPER_EXPOSED, 10), (COPPER_CUT, 15)),
    # tarmac above the pad (decks, ramps): the same textured dark stone the pad-level TARMAC tuple uses
    ASPHALT: Mix((block("cobbled_deepslate"), 35), (block("blackstone"), 20), (block("deepslate_bricks"), 12),
                 (block("cracked_deepslate_bricks"), 10), (block("basalt"), 10), (block("polished_basalt"), 8), (ASPHALT, 5)),
    SMOOTH_STONE: Mix((SMOOTH_STONE, 50), (POLISHED_ANDESITE, 20), (block("stone"), 12), (CRACKED_STONE_BRICKS, 10), (ANDESITE, 8)),
    RED_PAINT: Mix((block("red_terracotta"), 55), (block("brown_terracotta"), 25), (block("nether_bricks"), 10), (RED_PAINT, 10)),
    SIGN_BOARD: Mix((block("light_gray_terracotta"), 40), (SMOOTH_STONE, 25), (block("polished_diorite"), 15), (block("cut_sandstone"), 10),
                    (CONCRETE, 10)),
}

MIX_BLOCKS = frozenset(b.name for mix in MIXES.values() for b in mix.blocks)


def _lattice(seed: int, ix: int, iy: int, iz: int) -> float:
    h = (seed * 0x9E3779B1) ^ (ix * 0x85EBCA6B) ^ (iy * 0xC2B2AE35) ^ (iz * 0x27D4EB2F)
    h = (h ^ (h >> 15)) * 0x2C1B3C6D & 0xFFFFFFFF
    h = (h ^ (h >> 12)) * 0x297A2D39 & 0xFFFFFFFF
    return ((h ^ (h >> 15)) & 0xFFFF) / 0xFFFF


def noise(seed: int, x: float, y: float, z: float) -> float:
    """Smooth value noise in [0, 1]: trilinear blend of a hashed lattice."""
    ix, iy, iz = int(x // 1), int(y // 1), int(z // 1)
    fx, fy, fz = x - ix, y - iy, z - iz
    fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)
    total = 0.0
    for dx, wx in ((0, 1 - fx), (1, fx)):
        for dy, wy in ((0, 1 - fy), (1, fy)):
            for dz, wz in ((0, 1 - fz), (1, fz)):
                total += wx * wy * wz * _lattice(seed, ix + dx, iy + dy, iz + dz)
    return total


def _wear_boost(y: int, top: int) -> float:
    """More variants low down (splash, damp, rubble) and along the roofline (exposed); cleanest mid-height."""
    if top <= 1:
        return 0.0
    t = y / top
    return 0.55 * max(0.0, 1 - t / 0.25) + 0.35 * max(0.0, (t - 0.8) / 0.2)


def _pick_variant(mix: Mix, n: float) -> BlockState:
    variants = mix.members[1:]
    vtotal = sum(w for _, w in variants)
    acc = 0.0
    for block_, w in variants:
        acc += w / vtotal
        if n <= acc:
            return block_
    return variants[-1][0]


def texturize(canvas: Canvas, seed: int, mixes: dict[BlockState, Mix] = MIXES) -> None:
    """Resolve every canonical block above the pad through its family's mix — in patches, not per block.

    Two noise fields decide: the first, stretched vertically (rain streaks), whether the cell keeps the
    family's base block or takes a variant; the second, coarser, which variant, so a variant runs
    across a few neighbouring blocks as a stain, a patch of repair or a run of missing windows.
    """
    cells = list(canvas.freeze().items())
    top = max((p[1] for p, _ in cells), default=1)
    for pos, state in cells:
        if pos[1] == 0:
            continue
        mix = mixes.get(state)
        if mix is None:
            continue
        x, y, z = pos
        base, base_w = mix.members[0]
        total = sum(w for _, w in mix.members)
        variant_share = (1 - base_w / total) * (1 + _wear_boost(y, top))
        n_wear = noise(seed, x / 2.5, y / 7.0, z / 2.5) * 0.7 + _lattice(seed ^ 0xA5, x, y, z) * 0.3
        picked = base if n_wear > variant_share else _pick_variant(mix, noise(seed ^ 0x5EED, x / 4.0, y / 4.0, z / 4.0))
        canvas.put(pos, picked.with_props(**dict(state.props)) if state.props and picked != AIR else picked)


def family(canonical: BlockState) -> tuple[BlockState, ...]:
    """Every block a canonical block can become — what a recolour swap must cover."""
    mix = MIXES.get(canonical)
    return tuple(b for b in (mix.blocks if mix else (canonical,)) if b != AIR)
