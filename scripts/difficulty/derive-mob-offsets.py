#!/usr/bin/env python3
"""Derive MobEffectOffsets' table from the bundled carriage contents.

Each hostile's offset is the lowest Diff-Level at which any carriage room can spawn it:
  * a room's level is its top-level gate (weights.json minLevel, or the lowest minLevel of its
    linked stages), raised to a group member's own gate (minLevel, or its stages' lowest);
  * a sidecar `entity` candidate counts at max(room level, its cell's difficulty.min);
  * a baked .nbt entity (and its Passengers) counts at the room's level;
  * a cell that rolls an ancient_vase pot counts for the pot mobs GloomslatePotMobEvents spawns.

Phases and bands are ignored (they are world positions, not levels), as are portal rooms (their
cells skip difficulty bands) and the onboarding slime/magma substitutes. Only ids labelled
`hostile` in scripts/advancements/mob-photos/categories.json are kept.

  derive-mob-offsets.py           print the derived table with the room each value comes from
  derive-mob-offsets.py --write   rewrite the table in MobEffectOffsets.java
  derive-mob-offsets.py --check   exit 1 if MobEffectOffsets.java differs from the derivation
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "src/main/resources/data/dungeontrain"
CONTENTS = DATA / "contents"
STAGES = DATA / "stages.json"
CATEGORIES = ROOT / "scripts/advancements/mob-photos/categories.json"
JAVA = ROOT / "src/main/java/games/brennan/dungeontrain/difficulty/MobEffectOffsets.java"

# Kept in step with GloomslatePotMobEvents.POT_MOBS.
POT_MOBS = ("deeperdarker:anger_pot", "deeperdarker:fear_pot", "deeperdarker:sorrow_pot")
VASE_PREFAB = "ancient_vase"

TABLE_START = "private static final Map<String, Integer> OFFSETS = Map.ofEntries("
TABLE_RE = re.compile(re.escape(TABLE_START) + r"(.*?)\n(\s*)\);", re.S)
ENTRY_RE = re.compile(r'Map\.entry\("([^"]+)",\s*(\d+)\)')


def _load_read_nbt():
    path = ROOT / "scripts/advancements/generate-mob-photo-collections.py"
    spec = importlib.util.spec_from_file_location("mob_photo_collections", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.read_nbt


def _weight(entry) -> float:
    return entry if isinstance(entry, (int, float)) else entry.get("weight", 1)


def gate_min(entry, stages: dict) -> int:
    """Lowest level a weights.json entry or group member is eligible at. Stage links replace the inline gate."""
    if not isinstance(entry, dict):
        return 0
    linked = entry.get("stages") or ([entry["stage"]] if "stage" in entry else [])
    if linked:
        return min(stages[name].get("minLevel", 0) for name in linked)
    return entry.get("minLevel", 0)


def _band_min(candidate: dict) -> int:
    value = (candidate.get("difficulty") or {}).get("min", 0)
    return value if isinstance(value, int) else 0


def _baked_ids(entities):
    for entity in entities or []:
        tag = entity.get("nbt", entity)
        if "id" in tag:
            yield tag["id"]
        yield from _baked_ids(tag.get("Passengers"))


class Derivation:
    def __init__(self, contents: Path, stages: dict, read_nbt):
        self.contents = contents
        self.stages = stages
        self.read_nbt = read_nbt
        self.level: dict[str, int] = {}
        self.source: dict[str, str] = {}

    def _note(self, entity: str, level: int, source: str) -> None:
        if entity not in self.level or level < self.level[entity]:
            self.level[entity] = level
            self.source[entity] = source

    def _scan_room(self, room: str, level: int, source: str) -> None:
        sidecar = self.contents / f"{room}.variants.json"
        if sidecar.exists():
            cells = json.loads(sidecar.read_text()).get("variants", {})
            for cell, candidates in cells.items():
                for candidate in candidates:
                    if not isinstance(candidate, dict) or candidate.get("weight", 1) <= 0:
                        continue
                    at = max(level, _band_min(candidate))
                    if "entity" in candidate:
                        self._note(candidate["entity"], at, f"{source} cell {cell}")
                    if VASE_PREFAB in json.dumps(candidate):
                        for pot in POT_MOBS:
                            self._note(pot, at, f"{source} vase {cell}")
        template = self.contents / f"{room}.nbt"
        if template.exists():
            for entity in _baked_ids(self.read_nbt(template).get("entities")):
                self._note(entity, level, f"{source} baked")

    def run(self) -> "Derivation":
        weights = json.loads((self.contents / "weights.json").read_text())
        groups = {p.name[: -len(".group.json")]: json.loads(p.read_text())
                  for p in self.contents.glob("*.group.json")}
        children = {m["id"] for g in groups.values() for m in g.get("variants", [])}
        for room, entry in sorted(weights.items()):
            if room in children or _weight(entry) <= 0:
                continue
            base = gate_min(entry, self.stages)
            group = groups.get(room)
            if group is None:
                self._scan_room(room, base, room)
                continue
            if group.get("selfWeight", 1) > 0:
                self._scan_room(room, base, room)
            for member in group.get("variants", []):
                if member.get("weight", 1) > 0:
                    level = max(base, gate_min(member, self.stages))
                    self._scan_room(member["id"], level, f"{room}/{member['id']}")
        return self


def derive(categories: dict[str, str], derivation: Derivation) -> dict[str, int]:
    """Hostile ids with a non-zero first level; ids missing from the table read as 0."""
    return {e: lvl for e, lvl in derivation.level.items() if categories.get(e) == "hostile" and lvl > 0}


def render(table: dict[str, int], indent: str) -> str:
    rows = sorted(table.items(), key=lambda kv: (kv[1], kv[0]))
    return ",\n".join(f'{indent}Map.entry("{e}", {lvl})' for e, lvl in rows)


def read_java_table(text: str) -> dict[str, int]:
    match = TABLE_RE.search(text)
    if not match:
        raise SystemExit(f"error: no OFFSETS table found in {JAVA.relative_to(ROOT)}")
    return {e: int(v) for e, v in ENTRY_RE.findall(match.group(1))}


def write_java_table(text: str, table: dict[str, int]) -> str:
    match = TABLE_RE.search(text)
    if not match:
        raise SystemExit(f"error: no OFFSETS table found in {JAVA.relative_to(ROOT)}")
    closing_indent = match.group(2)
    body = render(table, closing_indent + "        ")
    return text[: match.start()] + f"{TABLE_START}\n{body}\n{closing_indent});" + text[match.end():]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="rewrite the table in MobEffectOffsets.java")
    mode.add_argument("--check", action="store_true", help="fail if MobEffectOffsets.java is stale")
    args = parser.parse_args()

    categories = json.loads(CATEGORIES.read_text())["categories"]
    stages = json.loads(STAGES.read_text())
    derivation = Derivation(CONTENTS, stages, _load_read_nbt()).run()
    table = derive(categories, derivation)

    if args.write or args.check:
        text = JAVA.read_text()
        current = read_java_table(text)
        if args.write:
            JAVA.write_text(write_java_table(text, table))
            print(f"wrote {len(table)} offsets to {JAVA.relative_to(ROOT)}")
            return 0
        if current == table:
            print(f"MobEffectOffsets is up to date ({len(table)} offsets)")
            return 0
        print(f"error: {JAVA.relative_to(ROOT)} is stale against the carriage contents:", file=sys.stderr)
        for entity in sorted(set(current) | set(table)):
            have, want = current.get(entity, 0), table.get(entity, 0)
            if have != want:
                where = derivation.source.get(entity, "no longer spawns past level 0")
                print(f"  {entity}: table {have}, contents {want}  ({where})", file=sys.stderr)
        print("Run: python3 scripts/difficulty/derive-mob-offsets.py --write", file=sys.stderr)
        return 1

    for entity, level in sorted(table.items(), key=lambda kv: (kv[1], kv[0])):
        print(f"{level:4d}  {entity:40s} {derivation.source[entity]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
