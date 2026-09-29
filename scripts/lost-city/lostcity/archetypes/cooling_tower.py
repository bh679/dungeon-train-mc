"""Cooling tower: a hollow hyperboloid concrete shell on a ring of legs, a shallow basin inside."""

import math

from ..blocks import CONCRETE, CONCRETE_DARK, STONE_BRICKS, WATER
from ..canvas import Canvas
from ..shapes import disc, hyperboloid_radius, ring
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="cooling_tower", size=(41, 52, 41), floor_period=None, bay_period_x=None,
                     bay_period_z=None, margin=2, seed=0xC001, weight=3)

C = 20
HEIGHT = 51
R_BASE, R_WAIST = 17.5, 11.0
LEG_LAYERS = 5


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, 3, 3, 37, 37, apron=1)
    canvas.put_all(ring(C, C, 1, R_BASE - 1.5, STONE_BRICKS, thick=1.5))
    canvas.put_all(disc(C, C, 1, R_BASE - 3, WATER))
    for y in range(1, HEIGHT + 1):
        r = hyperboloid_radius(y, HEIGHT, R_BASE, R_WAIST, waist_at=0.78)
        shell = ring(C, C, y, r, CONCRETE, thick=1.4)
        if y <= LEG_LAYERS:
            shell = {pos: CONCRETE_DARK for pos in shell if _is_leg(pos)}
        canvas.put_all(shell)
    finish(canvas, SPEC, envelope=None, moss_chance=0.02, vine_chance=0.01)


def _is_leg(pos) -> bool:
    """Every third arc segment of the bottom ring is a leg; the rest is open, as the air intake."""
    angle = math.atan2(pos[2] - C, pos[0] - C)
    return int((angle + math.pi) / (2 * math.pi) * 24) % 3 == 0


ARCHETYPE = Archetype(SPEC, draw)
