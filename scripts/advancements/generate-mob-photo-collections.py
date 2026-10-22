#!/usr/bin/env python3
"""Keep the mob photo collections in step with what the train can spawn.

Most Wanted (every hostile mob) and Nature Documentary (every animal) are one photo_subject
criterion per entity type. Rather than hand-maintain them, this reads every mob the bundled
content can put on the train and adds any that are missing:

  * mob candidates ({"entity": "<id>"}) in contents/, portals/room/ and prefabs/block_variants/
    sidecars
  * entities saved inside the contents/ and portals/room/ template .nbt files
  * the spawns decided in Java, listed under extra_spawns in mob-photos/categories.json

Each id is sorted into a collection by mob-photos/categories.json (hostile / animal / skip). An id
with no category stops the build: add one line there. Existing criteria are never dropped (banked
progress and the album keep their keys) unless their id is set to skip; new ones are appended in
id order, vanilla first.

Run with --check to fail instead of writing (CI). Stdlib only.
"""
from __future__ import annotations

import argparse
import gzip
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "src/main/resources/data/dungeontrain"
ADVANCEMENTS = DATA / "advancement/enchiridion"
CATEGORIES = Path(__file__).resolve().parent / "mob-photos/categories.json"

SIDECAR_DIRS = ("contents", "portals/room", "prefabs/block_variants")
NBT_DIRS = ("contents", "portals/room")
COLLECTIONS = {"hostile": "most_wanted", "animal": "nature_documentary"}
CATEGORY_VALUES = {"hostile", "animal", "skip"}
TRIGGER = "dungeontrain:photo_subject"
SUBJECT_PREFIX = "entity:"


# ---- NBT (just enough to read a structure file's entities[].nbt.id) ----

class _Reader:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0

    def take(self, n: int) -> bytes:
        out = self.data[self.pos:self.pos + n]
        if len(out) != n:
            raise ValueError("truncated NBT")
        self.pos += n
        return out

    def unpack(self, fmt: str):
        s = _STRUCTS[fmt]
        value = s.unpack_from(self.data, self.pos)[0]
        self.pos += s.size
        return value

    def string(self) -> str:
        return self.take(self.unpack("H")).decode("utf-8", "replace")


_STRUCTS = {f: struct.Struct(">" + f) for f in ("b", "h", "H", "i", "q", "f", "d")}


_FIXED = {1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}
_ARRAYS = {7: 1, 11: 4, 12: 8}


def _payload(r: _Reader, tag: int):
    if tag in _FIXED:
        return r.unpack(_FIXED[tag])
    if tag in _ARRAYS:
        r.take(r.unpack("i") * _ARRAYS[tag])
        return None
    if tag == 8:
        return r.string()
    if tag == 9:
        inner, count = r.unpack("b"), r.unpack("i")
        if inner in _FIXED:  # block positions and the like: read in one go
            return list(struct.unpack(f">{count}{_FIXED[inner]}",
                                      r.take(count * struct.calcsize(_FIXED[inner]))))
        return [_payload(r, inner) for _ in range(count)]
    if tag == 10:
        out = {}
        while (child := r.unpack("b")) != 0:
            name = r.string()
            out[name] = _payload(r, child)
        return out
    raise ValueError(f"unknown NBT tag {tag}")


def read_nbt(path: Path) -> dict:
    raw = path.read_bytes()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = _Reader(raw)
    if r.unpack("b") != 10:
        raise ValueError(f"{path}: root is not a compound")
    r.string()
    return _payload(r, 10)


# ---- what the train spawns ----

def _sidecar_entities(node, out: set[str]) -> None:
    if isinstance(node, dict):
        entity = node.get("entity")
        if isinstance(entity, str) and ":" in entity:
            out.add(entity)
        for value in node.values():
            _sidecar_entities(value, out)
    elif isinstance(node, list):
        for value in node:
            _sidecar_entities(value, out)


def spawnable(categories_doc: dict) -> dict[str, str]:
    """Every entity id the bundled content can spawn -> the first file it was seen in."""
    seen: dict[str, str] = {}

    def note(ids, source: str) -> None:
        for entity in sorted(ids):
            seen.setdefault(entity, source)

    for sub in SIDECAR_DIRS:
        for path in sorted((DATA / sub).glob("*.json")):
            found: set[str] = set()
            _sidecar_entities(json.loads(path.read_text(encoding="utf-8")), found)
            note(found, str(path.relative_to(ROOT)))
    for sub in NBT_DIRS:
        for path in sorted((DATA / sub).glob("*.nbt")):
            root = read_nbt(path)
            found = {e["nbt"]["id"] for e in root.get("entities") or []
                     if isinstance(e, dict) and isinstance(e.get("nbt"), dict) and "id" in e["nbt"]}
            note(found, str(path.relative_to(ROOT)))
    for source, ids in categories_doc.get("extra_spawns", {}).items():
        if not source.startswith("_"):
            note(ids, f"{source}.java (extra_spawns)")
    return seen


# ---- the collections ----

def _id_order(entity: str):
    namespace, _, path = entity.partition(":")
    return (namespace != "minecraft", namespace, path)


def _criterion(entity: str) -> dict:
    return {"trigger": TRIGGER, "conditions": {"subject": SUBJECT_PREFIX + entity}}


def build(advancement: dict, wanted: set[str], skipped: set[str]) -> dict:
    """advancement with every wanted id present; existing order kept, skipped ids dropped."""
    existing = [k for k in advancement["criteria"] if k not in skipped]
    additions = sorted(wanted - set(existing), key=_id_order)
    criteria = {k: advancement["criteria"][k] for k in existing}
    criteria.update({k: _criterion(k) for k in additions})
    out = {k: v for k, v in advancement.items() if k not in ("criteria", "requirements")}
    out["criteria"] = criteria
    out["requirements"] = [[k] for k in criteria]
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if a collection is out of date")
    args = parser.parse_args()

    doc = json.loads(CATEGORIES.read_text(encoding="utf-8"))
    categories: dict[str, str] = doc["categories"]
    bad = {k: v for k, v in categories.items() if v not in CATEGORY_VALUES}
    if bad:
        print(f"error: unknown categories in {CATEGORIES.relative_to(ROOT)}: {bad}", file=sys.stderr)
        return 1

    seen = spawnable(doc)
    missing = {e: src for e, src in seen.items() if e not in categories}
    if missing:
        print("error: the train can spawn mobs with no photo category. Add each to "
              f"{CATEGORIES.relative_to(ROOT)} as hostile, animal or skip:", file=sys.stderr)
        for entity, source in sorted(missing.items()):
            print(f"  {entity}  (from {source})", file=sys.stderr)
        return 1

    skipped = {e for e, c in categories.items() if c == "skip"}
    stale = []
    for category, name in COLLECTIONS.items():
        path = ADVANCEMENTS / f"{name}.json"
        text = path.read_text(encoding="utf-8")
        current = json.loads(text)
        wanted = {e for e in seen if categories[e] == category}
        # An id moved to the other collection leaves this one too.
        moved = {e for e in current["criteria"] if categories.get(e) not in (None, category)}
        updated = build(current, wanted, skipped | moved)
        rendered = json.dumps(updated, indent=2, ensure_ascii=False) + "\n"
        if rendered == text:
            continue
        added = [k for k in updated["criteria"] if k not in current["criteria"]]
        removed = [k for k in current["criteria"] if k not in updated["criteria"]]
        stale.append(f"{name}: +{added} -{removed}")
        if not args.check:
            path.write_text(rendered, encoding="utf-8")
            print(f"updated {path.relative_to(ROOT)}: {len(updated['criteria'])} criteria"
                  f" (+{len(added)} -{len(removed)})")

    if stale and args.check:
        print("error: mob photo collections are out of date; run "
              "python3 scripts/advancements/generate-mob-photo-collections.py", file=sys.stderr)
        for line in stale:
            print(f"  {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
