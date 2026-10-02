"""Processor lists, template pools, structures and structure-set entries for DT's own buildings.

Every pool element places a `dungeontrain:lost_city/<name>` template — never a Big Lost City one.
`LostCityStructuresTest.originalPoolsAreSound` pins that in Java; `render()` refuses anything else here.
"""

import json
from typing import Iterable

from .spec import Archetype
from .variants import Design

IGNORE = {"processor_type": "minecraft:block_ignore", "blocks": [{"Name": "minecraft:structure_block"}]}
VARIANTS = "dungeontrain:lost_city_variants"
# A building's variant cells are tagged before anything else touches them ("mark") and rolled once the
# stretches have copied them and the swaps have run ("roll"), so bite, facade and truncate see the picks.
ORDER = {VARIANTS + "/mark": 0, "dungeontrain:lost_city_stretch": 1, "dungeontrain:lost_city_swap": 2,
         VARIANTS + "/roll": 3, "dungeontrain:lost_city_bite": 4, "dungeontrain:lost_city_facade": 5,
         "dungeontrain:lost_city_truncate": 6}
DATA = "src/main/resources/data/dungeontrain"
SET_PATH = f"{DATA}/worldgen/structure_set/lost_city.json"

# The one slot that places players' new buildings (building/BuildingWorldgen.java): its pool holds a single
# placeholder that StructureTemplatePoolBuildingMixin swaps for a weighted pick of the world's new buildings.
PLAYER_BUILDING = "player_building"
PLAYER_BUILDING_SLOT = "dungeontrain:building_slot"
PLAYER_BUILDING_WEIGHT = 6
TRACKSIDE_BIOMES = "#dungeontrain:lost_city_trackside"


def dumps(obj) -> bytes:
    return (json.dumps(obj, indent=2) + "\n").encode()


def _slot(processor: dict) -> int:
    kind = processor["processor_type"]
    return ORDER[kind + "/" + processor["phase"] if kind == VARIANTS else kind]


def variant_phases(template: str) -> tuple[dict, dict]:
    return tuple({"processor_type": VARIANTS, "template": template, "phase": phase} for phase in ("mark", "roll"))


def processor_list(design: Design, template: str | None = None) -> dict:
    """`template` is the building's id when it has a variants document; its two phases join the list."""
    processors = (*design.processors, *(variant_phases(template) if template else ()))
    return {"processors": [IGNORE, *sorted(processors, key=_slot)]}


def pool(archetype: Archetype, designs: Iterable[Design]) -> dict:
    name = archetype.spec.name
    template = f"dungeontrain:lost_city/{name}"
    elements = [{"weight": d.weight, "element": {
        "element_type": "minecraft:single_pool_element", "location": template, "projection": "rigid",
        "processors": f"dungeontrain:lost_city/{name}_{d.name}"}} for d in designs]
    return {"name": template, "fallback": "minecraft:empty", "elements": elements}


def structure(archetype: Archetype) -> dict:
    return jigsaw(archetype.spec.name, archetype.spec.biomes)


def jigsaw(name: str, biomes: str) -> dict:
    return {
        "type": "minecraft:jigsaw", "start_pool": f"dungeontrain:lost_city/{name}", "size": 1,
        "max_distance_from_center": 64, "spawn_overrides": {}, "step": "surface_structures",
        "terrain_adaptation": "beard_thin", "start_height": {"absolute": 0},
        "project_start_to_heightmap": "WORLD_SURFACE_WG", "biomes": biomes, "use_expansion_hack": False,
    }


def player_building_pool() -> dict:
    return {"name": f"dungeontrain:lost_city/{PLAYER_BUILDING}", "fallback": "minecraft:empty", "elements": [
        {"weight": 1, "element": {"element_type": "minecraft:single_pool_element", "location": PLAYER_BUILDING_SLOT,
                                  "projection": "rigid", "processors": "minecraft:empty"}}]}


def structure_set(existing: dict, archetypes: Iterable[Archetype]) -> dict:
    """The set with an entry per DT building appended (and its weight refreshed), everything else kept."""
    names = {a.spec.name for a in archetypes} | {PLAYER_BUILDING}
    structures = [s for s in existing["structures"]
                  if not (s["structure"].startswith("dungeontrain:lost_city/") and s["structure"].split("/")[-1] in names)]
    for a in archetypes:
        structures.append({"structure": f"dungeontrain:lost_city/{a.spec.name}", "weight": a.spec.weight})
    structures.append({"structure": f"dungeontrain:lost_city/{PLAYER_BUILDING}", "weight": PLAYER_BUILDING_WEIGHT})
    return {**existing, "structures": structures}


def render(archetypes: Iterable[Archetype], designs: dict[str, list[Design]], existing_set: dict,
           with_variants: frozenset[str] = frozenset()) -> dict[str, bytes]:
    """`with_variants` names the buildings that have a variants document beside their template."""
    archetypes = tuple(archetypes)
    files: dict[str, bytes] = {}
    for a in archetypes:
        name = a.spec.name
        template = f"dungeontrain:lost_city/{name}" if name in with_variants else None
        for d in designs[name]:
            files[f"{DATA}/worldgen/processor_list/lost_city/{name}_{d.name}.json"] = dumps(processor_list(d, template))
        files[f"{DATA}/worldgen/template_pool/lost_city/{name}.json"] = dumps(pool(a, designs[name]))
        files[f"{DATA}/worldgen/structure/lost_city/{name}.json"] = dumps(structure(a))
    files[f"{DATA}/worldgen/template_pool/lost_city/{PLAYER_BUILDING}.json"] = dumps(player_building_pool())
    files[f"{DATA}/worldgen/structure/lost_city/{PLAYER_BUILDING}.json"] = dumps(jigsaw(PLAYER_BUILDING, TRACKSIDE_BIOMES))
    files[SET_PATH] = dumps(structure_set(existing_set, archetypes))
    for path, data in files.items():
        if b"big_lost_city:" in data and path != SET_PATH:
            raise SystemExit(f"{path} names a Big Lost City template — DT processors must never touch one")
    return files
