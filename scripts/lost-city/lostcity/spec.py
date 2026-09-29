"""What an archetype declares about itself, and the manifest the Java test reads it back from."""

from dataclasses import asdict, dataclass, field
from typing import Callable

from .canvas import Canvas, Cells


@dataclass(frozen=True)
class ArchetypeSpec:
    name: str
    size: tuple[int, int, int]
    floor_period: int | None          # y-repeat lost_city_stretch should find, or None if not y-stretched
    bay_period_x: int | None          # x-repeat, or None
    bay_period_z: int | None          # z-repeat, or None
    margin: int                       # clear pad blocks between the building and the template edge
    seed: int                         # for the baked weathering; fixed so re-runs are byte-identical
    weight: int = 5                   # structure_set weight
    biomes: str = "#dungeontrain:lost_city_trackside"
    tags: tuple[str, ...] = field(default_factory=tuple)

    def manifest(self) -> dict:
        out = asdict(self)
        out["size"] = list(self.size)
        out["tags"] = list(self.tags)
        return out


@dataclass(frozen=True)
class Archetype:
    spec: ArchetypeSpec
    draw: Callable[[Canvas], None]

    def render(self) -> Cells:
        canvas = Canvas(self.spec.size)
        self.draw(canvas)
        return canvas.freeze()
