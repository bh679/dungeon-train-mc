"""Steps every archetype shares: the pad under its footprint and the finishing weathering pass."""

from ..blocks import (AIR, CONCRETE, CONCRETE_POWDER, CRACKED_STONE_BRICKS, GLASS, GLASS_CLEAR, STONE_BRICKS,
                      BlockState)
from ..canvas import Canvas, envelope_air
from ..pad import apron_around, pad
from ..spec import ArchetypeSpec
from ..weather import weather

CRACKED_DEFAULT: dict[BlockState, BlockState] = {
    STONE_BRICKS: CRACKED_STONE_BRICKS,
    CONCRETE: CONCRETE_POWDER,
    GLASS: AIR,
    GLASS_CLEAR: AIR,
}


def footprint_pad(canvas: Canvas, spec: ArchetypeSpec, x0: int, z0: int, x1: int, z1: int, apron: int = 3,
                  paving=None) -> None:
    """Natural ground across the template, paving `apron` blocks out from the footprint (x0..x1, z0..z1)."""
    rect = apron_around(x0, z0, x1, z1, apron)
    sx, _, sz = spec.size
    clipped = (max(0, rect[0]), max(0, rect[1]), min(sx - 1, rect[2]), min(sz - 1, rect[3]))
    kwargs = {"paving": paving} if paving else {}
    canvas.put_all(pad(sx, sz, clipped, spec.seed, **kwargs))


def finish(canvas: Canvas, spec: ArchetypeSpec, envelope: tuple[int, int, int, int, int, int] | None = None,
           cracked: dict[BlockState, BlockState] | None = None, **weather_kwargs) -> None:
    """Explicit air inside the building envelope (x0, y0, z0, x1, y1, z1), then the weathering pass."""
    if envelope is not None:
        envelope_air(canvas, *envelope)
    weather(canvas, spec.seed, cracked if cracked is not None else CRACKED_DEFAULT, margin=spec.margin, **weather_kwargs)
