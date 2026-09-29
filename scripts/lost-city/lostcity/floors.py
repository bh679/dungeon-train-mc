"""Periodic floors and bays — the repeats `lost_city_stretch` looks for.

`findBand` (LostCityStretchProcessor) wants slice i to match slice i+period on full-block mass, for at
least a full period of consecutive slices, with every slice carrying ≥ 3 % of the heaviest slice's
mass. `tower()` guarantees that: every floor is the same plate layer plus the same wall layers, the
wall has a full-block pier at every bay boundary and full glass or spandrel blocks between, and the
pattern is keyed on the distance along the wall so it repeats along x and z alike.
"""

from dataclasses import dataclass

from .blocks import AIR, BlockState
from .canvas import Pos
from .shapes import box, perimeter


@dataclass(frozen=True)
class Facade:
    pier: BlockState          # full block at every bay boundary, every wall layer
    window: BlockState        # full block between piers on window layers (glass, so it has mass)
    spandrel: BlockState      # full block between piers on the spandrel layer(s) above the plate
    spandrel_layers: int = 1  # wall layers directly above the plate that are spandrel, not window
    sill: BlockState | None = None  # optional non-mass block (pane/slab) replacing the window on the top layer


def floor_layers(x0: int, z0: int, x1: int, z1: int, y: int, period: int, bay: int, plate: BlockState,
                 facade: Facade, hollow: bool = True) -> dict[Pos, BlockState]:
    """One floor: a plate at `y`, then `period - 1` wall layers above it. Interior is air when hollow."""
    cells = box(x0, y, z0, x1, y, z1, plate)
    for layer in range(1, period):
        for (x, _, z, along) in perimeter(x0, z0, x1, z1, y + layer):
            cells[(x, y + layer, z)] = _wall_block(along, layer, period, bay, facade)
        if hollow and x1 - x0 > 1 and z1 - z0 > 1:
            cells.update(box(x0 + 1, y + layer, z0 + 1, x1 - 1, y + layer, z1 - 1, AIR))
    return cells


def _wall_block(along: int, layer: int, period: int, bay: int, facade: Facade) -> BlockState:
    if along % bay == 0:
        return facade.pier
    if layer <= facade.spandrel_layers:
        return facade.spandrel
    if facade.sill is not None and layer == period - 1:
        return facade.sill
    return facade.window


def tower(x0: int, z0: int, x1: int, z1: int, y0: int, floors: int, period: int, bay: int, plate: BlockState,
          facade: Facade, hollow: bool = True) -> dict[Pos, BlockState]:
    """`floors` identical floors stacked from `y0`; the roof plate is the caller's."""
    cells: dict[Pos, BlockState] = {}
    for f in range(floors):
        cells.update(floor_layers(x0, z0, x1, z1, y0 + f * period, period, bay, plate, facade, hollow))
    return cells


def roof_plate(x0: int, z0: int, x1: int, z1: int, y: int, plate: BlockState) -> dict[Pos, BlockState]:
    return box(x0, y, z0, x1, y, z1, plate)


def bay_aligned(length: int, bay: int) -> int:
    """The largest wall length ≤ `length` whose two ends both fall on a pier: n·bay + 1."""
    return ((length - 1) // bay) * bay + 1
