"""A building's block-variant document: which cells hold a pool instead of one fixed block.

`<name>.variants.json` sits beside the template and is rolled per placement by the
`dungeontrain:lost_city_variants` processor, in the schema every DT variants sidecar uses (the one the
editor's block-variant menu writes), so a document can later be refined by hand.

The pools are seeded from the finished cells by `RULES`: each rule names a block and what may stand in
its place. Only small, self-supporting details are varied — props, plants, lamps, vine ends — never
walls or floors, so a building keeps its shape and its declared repeats. A building listed in
`authored-variants.json` is hand-kept: the generator leaves its document alone and only validates it.
"""

import json
import zlib
from typing import Callable, NamedTuple

from .blocks import AIR, BlockState
from .canvas import Cells, Pos
from .spec import ArchetypeSpec

SCHEMA_VERSION = 10
EMPTY = "dungeontrain:variant_placeholder"   # "nothing here": air once placed
CAP = 400                                    # cells per building — keeps the documents small
MIN_STATES = 2                               # a pool of one is not a pool; the loader drops it
LAMP_CIRCUITS = 3                            # lamps go dark together, a circuit at a time


class Entry(NamedTuple):
    state: str
    weight: int = 1
    growth: str | None = None

    def json(self):
        if self.weight == 1 and self.growth is None:
            return self.state
        out: dict = {"state": self.state}
        if self.weight != 1:
            out["weight"] = self.weight
        if self.growth is not None:
            out["growth"] = self.growth
        return out


class Cell(NamedTuple):
    entries: tuple[Entry, ...]
    lock_id: int = 0

    def json(self):
        states = [e.json() for e in self.entries]
        return {"lockId": self.lock_id, "states": states} if self.lock_id else states


class Rule(NamedTuple):
    block: str
    pool: Callable[[BlockState], tuple[Entry, ...]]
    where: Callable[[Cells, Pos], bool] = lambda cells, pos: True
    lock: Callable[[int], int] = lambda rank: 0


def state_string(state: BlockState) -> str:
    if not state.props:
        return state.name
    return state.name + "[" + ",".join(f"{k}={v}" for k, v in state.props) + "]"


def _as(state: BlockState, name: str) -> str:
    """Another block wearing this one's properties (a hanging lantern stays hanging)."""
    return state_string(BlockState("minecraft:" + name, state.props))


def _keep_or(*others: tuple[str, int], keep: int = 2, empty: int = 1) -> Callable[[BlockState], tuple[Entry, ...]]:
    def pool(state: BlockState) -> tuple[Entry, ...]:
        entries = [Entry(state_string(state), keep), *(Entry("minecraft:" + name, weight) for name, weight in others)]
        if empty:
            entries.append(Entry(EMPTY, empty))
        return tuple(entries)
    return pool


def _lamp(state: BlockState) -> tuple[Entry, ...]:
    return Entry(state_string(state), 3), Entry(_as(state, "soul_lantern"), 1), Entry(EMPTY, 2)


def _vine_end(state: BlockState) -> tuple[Entry, ...]:
    here = state_string(state)
    return Entry(here, 2), Entry(here, 2, "down 1-4"), Entry(EMPTY, 1)


def _hangs_free(cells: Cells, pos: Pos) -> bool:
    """The last block of a vine run, with room under it to grow into."""
    x, y, z = pos
    return y >= 3 and cells.get((x, y - 1, z), AIR) == AIR


def _carpet(*others: str) -> Callable[[BlockState], tuple[Entry, ...]]:
    return _keep_or(*((name, 1) for name in others), ("moss_carpet", 1))


PLANTS = (("fern", 1), ("short_grass", 1), ("dead_bush", 1))

RULES: tuple[Rule, ...] = (
    Rule("minecraft:lantern", _lamp, lock=lambda rank: 1 + rank % LAMP_CIRCUITS),
    Rule("minecraft:flower_pot", _keep_or(("potted_fern", 1), ("potted_dead_bush", 1), empty=2)),
    Rule("minecraft:crafting_table", _keep_or(("smithing_table", 1), ("cartography_table", 1), ("loom", 1))),
    Rule("minecraft:cauldron", _keep_or(("water_cauldron[level=1]", 1), ("water_cauldron[level=3]", 1))),
    Rule("minecraft:note_block", _keep_or(("loom", 1))),
    Rule("minecraft:bookshelf", _keep_or(("oak_planks", 1), ("spruce_planks", 1), keep=3, empty=0)),
    Rule("minecraft:red_carpet", _carpet("gray_carpet")),
    Rule("minecraft:gray_carpet", _carpet("light_gray_carpet")),
    Rule("minecraft:white_carpet", _carpet("light_gray_carpet")),
    Rule("minecraft:light_gray_carpet", _carpet("white_carpet")),
    Rule("minecraft:cobweb", _keep_or(keep=1)),
    Rule("minecraft:hanging_roots", _keep_or(keep=1)),
    Rule("minecraft:azalea", _keep_or(("flowering_azalea", 1), *PLANTS[:1])),
    Rule("minecraft:flowering_azalea", _keep_or(("azalea", 1))),
    Rule("minecraft:fern", _keep_or(*(p for p in PLANTS if p[0] != "fern"))),
    Rule("minecraft:dead_bush", _keep_or(*(p for p in PLANTS if p[0] != "dead_bush"))),
    Rule("minecraft:vine", _vine_end, where=_hangs_free),
)


def _rank(seed: int, pos: Pos) -> int:
    return zlib.crc32(f"{seed}:{pos[0]},{pos[1]},{pos[2]}".encode())


def seed(cells: Cells, spec: ArchetypeSpec, cap: int = CAP) -> dict[Pos, Cell]:
    """The building's pools: up to `cap` cells, shared out across the rules so no one block takes them all."""
    by_rule: list[list[tuple[Pos, Cell]]] = []
    for rule in RULES:
        found = sorted((pos for pos, state in cells.items()
                        if state.name == rule.block and pos[1] >= 1 and not state.nbt and rule.where(cells, pos)),
                       key=lambda pos: (_rank(spec.seed, pos), pos))
        by_rule.append([(pos, Cell(rule.pool(cells[pos]), rule.lock(_rank(spec.seed, pos)))) for pos in found])
    picked: dict[Pos, Cell] = {}
    turn = 0
    while len(picked) < cap and any(turn < len(found) for found in by_rule):
        for found in by_rule:
            if turn < len(found) and len(picked) < cap:
                picked[found[turn][0]] = found[turn][1]
        turn += 1
    return picked


def dumps(doc: dict[Pos, Cell]) -> bytes:
    """One cell per line, sorted as the template is (y, z, x), so a re-run is byte-identical."""
    ordered = sorted(doc.items(), key=lambda item: (item[0][1], item[0][2], item[0][0]))
    lines = [f'    "{x},{y},{z}": {json.dumps(cell.json())}' for (x, y, z), cell in ordered]
    return ('{\n  "schemaVersion": %d,\n  "variants": {\n%s\n  }\n}\n' % (SCHEMA_VERSION, ",\n".join(lines))).encode()


def loads(data: bytes) -> dict[Pos, list]:
    """A document's cells and their candidate lists, as written (for validating a hand-kept one)."""
    raw = json.loads(data)["variants"]
    out: dict[Pos, list] = {}
    for key, value in raw.items():
        x, y, z = (int(part) for part in key.split(","))
        out[(x, y, z)] = value["states"] if isinstance(value, dict) else value
    return out


def validate(doc: dict[Pos, list], cells: Cells, spec: ArchetypeSpec, cap: int = CAP) -> list[str]:
    """What the placement processor relies on: a real cell above the pad, a real pool, nothing it cannot place."""
    errors: list[str] = []
    if len(doc) > cap:
        errors.append(f"{len(doc)} variant cells, over the cap of {cap}")
    for pos, states in doc.items():
        if pos[1] < 1:
            errors.append(f"variant cell {pos} is on the pad layer")
        elif pos not in cells:
            errors.append(f"variant cell {pos} is not a cell of the template")
        if len(states) < MIN_STATES:
            errors.append(f"variant cell {pos} has fewer than {MIN_STATES} candidates")
        for state in states:
            text = state if isinstance(state, str) else state.get("state", state.get("entity", ""))
            if text.startswith("dungeontrain:stage_"):
                errors.append(f"variant cell {pos} uses a stage placeholder, which only a carriage placement resolves")
            if isinstance(state, dict) and "entity" in state:
                errors.append(f"variant cell {pos} has a mob entry, which worldgen cannot spawn")
            if isinstance(state, dict) and state.get("connect"):
                errors.append(f"variant cell {pos} uses a connect mode, which needs a live level")
    return [f"{spec.name}: {e}" for e in errors]
