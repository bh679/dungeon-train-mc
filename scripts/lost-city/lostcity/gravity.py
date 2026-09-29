"""Nothing in a template may fall when it is placed.

Vanilla updates every placed block's neighbours after a template stamp, so gravel, sand, concrete
powder or an anvil with nothing beneath drops out of the building on the first tick. The pass here
swaps any such block that lacks a solid cell directly under it for a stable stand-in of the same
colour, and is the last step of every archetype's finish.
"""

from .blocks import AIR, GRAVITY, GRAVITY_SUFFIX, BlockState, block
from .canvas import Canvas

STABLE = {
    "minecraft:gravel": block("cobblestone"),
    "minecraft:sand": block("sandstone"),
    "minecraft:red_sand": block("red_sandstone"),
    "minecraft:suspicious_sand": block("sandstone"),
    "minecraft:suspicious_gravel": block("cobblestone"),
    "minecraft:anvil": block("smithing_table"),
    "minecraft:chipped_anvil": block("smithing_table"),
    "minecraft:damaged_anvil": block("smithing_table"),
    "minecraft:scaffolding": block("oak_fence"),
}
UNSTABLE_BELOW = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "fence",
                  "trapdoor", "rail", "bush", "grass", "fern", "leaves", "azalea", "roots", "door", "rod", "flower_pot",
                  "torch", "button", "candle", "cobweb")


def falls(state: BlockState) -> bool:
    return state.name in GRAVITY or state.name.endswith(GRAVITY_SUFFIX)


def stable_for(state: BlockState) -> BlockState:
    if state.name.endswith(GRAVITY_SUFFIX):
        return block(state.name.removeprefix("minecraft:").removesuffix(GRAVITY_SUFFIX) + "_terracotta")
    return STABLE.get(state.name, block("cobblestone"))


def supported(canvas: Canvas, pos) -> bool:
    below = canvas.get((pos[0], pos[1] - 1, pos[2]))
    return below is not None and below != AIR and not any(p in below.name for p in UNSTABLE_BELOW) and not falls(below)


def settle(canvas: Canvas) -> None:
    """Replace every falling block above the pad that has no solid cell beneath it."""
    for pos, state in list(canvas.freeze().items()):
        if pos[1] >= 1 and falls(state) and not supported(canvas, pos):
            canvas.put(pos, stable_for(state))
