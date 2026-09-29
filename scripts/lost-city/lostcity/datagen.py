"""Processor lists, template pools, structures and structure-set entries for DT's own buildings.

Every pool element places a `dungeontrain:lost_city/<name>` template — never a Big Lost City one.
`LostCityStructuresTest.originalPoolsAreSound` pins that in Java; `render()` refuses anything else here.
"""

import json
from typing import Iterable

from .spec import Archetype
from .variants import Design

IGNORE = {"processor_type": "minecraft:block_ignore", "blocks": [{"Name": "minecraft:structure_block"}]}
ORDER = {"dungeontrain:lost_city_stretch": 0, "dungeontrain:lost_city_swap": 1, "dungeontrain:lost_city_bite": 2,
         "dungeontrain:lost_city_facade": 3, "dungeontrain:lost_city_truncate": 4}
DATA = "src/main/resources/data/dungeontrain"
SET_PATH = f"{DATA}/worldgen/structure_set/lost_city.json"


def dumps(obj) -> bytes:
    return (json.dumps(obj, indent=2) + "\n").encode()


def processor_list(design: Design) -> dict:
    ordered = sorted(design.processors, key=lambda p: ORDER[p["processor_type"]])
    return {"processors": [IGNORE, *ordered]}


def pool(archetype: Archetype, designs: Iterable[Design]) -> dict:
    name = archetype.spec.name
    template = f"dungeontrain:lost_city/{name}"
    elements = [{"weight": d.weight, "element": {
        "element_type": "minecraft:single_pool_element", "location": template, "projection": "rigid",
        "processors": f"dungeontrain:lost_city/{name}_{d.name}"}} for d in designs]
    return {"name": template, "fallback": "minecraft:empty", "elements": elements}


def structure(archetype: Archetype) -> dict:
    name = archetype.spec.name
    return {
        "type": "minecraft:jigsaw", "start_pool": f"dungeontrain:lost_city/{name}", "size": 1,
        "max_distance_from_center": 64, "spawn_overrides": {}, "step": "surface_structures",
        "terrain_adaptation": "beard_thin", "start_height": {"absolute": 0},
        "project_start_to_heightmap": "WORLD_SURFACE_WG", "biomes": archetype.spec.biomes, "use_expansion_hack": False,
    }


def structure_set(existing: dict, archetypes: Iterable[Archetype]) -> dict:
    """The set with an entry per DT building appended (and its weight refreshed), everything else kept."""
    names = {a.spec.name for a in archetypes}
    structures = [s for s in existing["structures"]
                  if not (s["structure"].startswith("dungeontrain:lost_city/") and s["structure"].split("/")[-1] in names)]
    for a in archetypes:
        structures.append({"structure": f"dungeontrain:lost_city/{a.spec.name}", "weight": a.spec.weight})
    return {**existing, "structures": structures}


def render(archetypes: Iterable[Archetype], designs: dict[str, list[Design]], existing_set: dict) -> dict[str, bytes]:
    archetypes = tuple(archetypes)
    files: dict[str, bytes] = {}
    for a in archetypes:
        name = a.spec.name
        for d in designs[name]:
            files[f"{DATA}/worldgen/processor_list/lost_city/{name}_{d.name}.json"] = dumps(processor_list(d))
        files[f"{DATA}/worldgen/template_pool/lost_city/{name}.json"] = dumps(pool(a, designs[name]))
        files[f"{DATA}/worldgen/structure/lost_city/{name}.json"] = dumps(structure(a))
    files[SET_PATH] = dumps(structure_set(existing_set, archetypes))
    for path, data in files.items():
        if b"big_lost_city:" in data and path != SET_PATH:
            raise SystemExit(f"{path} names a Big Lost City template — DT processors must never touch one")
    return files
