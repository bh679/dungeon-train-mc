#!/usr/bin/env python3
"""Fold the per-wood clones of the `*_wood_<type>` part set into one shared, placeholder-built set.

Every wood stage carries its own copy of the same seven parts — `floor/mudbricks_wood_*`,
`walls/{mudbricks,mudopen,mudwindows}_wood_*`, `roof/{mud,mudopen}_wood_*`, `doors/mud_wood_*` —
differing only in wood block ids (`clone-wood-stage.py` made most of them). With the stage
placeholder blocks (v0.864.0) one set built from `dungeontrain:stage_planks` / `stage_log` / …
resolves to each stage's own wood when the carriage spawns, so the clones are redundant.

What this does, idempotently:
  1. builds the seven shared parts (`<family>_wood`) from the `wood_oak` originals, mapping the
     oak ids to the wood placeholders (NBT palette + `.variants.json`);
  2. proves each per-wood clone is exactly what the shared set will stamp for its stage (the oak
     original with its ids resolved through that stage's wood family: same blocks, same states,
     same sidecar) — a clone that diverges (an editor-authored copy that was then edited) is left
     alone and reported;
  3. rewrites every `templates/*.parts.json` entry naming a replaced clone to the shared name
     (stage, weight, gate and modes untouched), regenerates the slot manifests, deletes the clones;
  4. locks the wood family on every stage that linked a clone (`"woodLocked": true` in
     stages.json) — with the real wood gone from its parts an unlocked re-bake would fall back to
     spruce.

`--check` reports every verdict and writes nothing (exit 1 on any divergent clone).

Usage:
    python3 scripts/parts/share-wood-stage.py [--check]
"""

import argparse
import gzip
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "portal"))
from nbt import COMPOUND, LIST, STRING, clone, get, read, write  # noqa: E402

DATA = ROOT / "src/main/resources/data/dungeontrain"
PARTS = DATA / "parts"
STAGES = DATA / "stages.json"
TEMPLATES = DATA / "templates"

SOURCE_PARTS = {
    "floor": ["mudbricks_wood_oak"],
    "walls": ["mudbricks_wood_oak", "mudopen_wood_oak", "mudwindows_wood_oak"],
    "roof": ["mud_wood_oak", "mudopen_wood_oak"],
    "doors": ["mud_wood_oak"],
}
SLOTS = list(SOURCE_PARTS)

# Oak id → placeholder id. Mirrors StageWoodFamily.WoodKind.
OAK_TO_PLACEHOLDER = {
    "minecraft:oak_planks": "dungeontrain:stage_planks",
    "minecraft:oak_wood": "dungeontrain:stage_wood",
    "minecraft:stripped_oak_wood": "dungeontrain:stage_stripped_wood",
    "minecraft:oak_log": "dungeontrain:stage_log",
    "minecraft:stripped_oak_log": "dungeontrain:stage_stripped_log",
    "minecraft:oak_fence": "dungeontrain:stage_fence",
    "minecraft:oak_button": "dungeontrain:stage_wood_button",
    "minecraft:oak_slab": "dungeontrain:stage_wood_slab",
    "minecraft:oak_stairs": "dungeontrain:stage_wood_stairs",
    "minecraft:oak_pressure_plate": "dungeontrain:stage_wood_pressure_plate",
    "minecraft:oak_fence_gate": "dungeontrain:stage_fence_gate",
    "minecraft:oak_door": "dungeontrain:stage_door",
    "minecraft:oak_trapdoor": "dungeontrain:stage_trapdoor",
}
KINDS = ["planks", "wood", "stripped_wood", "log", "stripped_log", "fence", "button", "slab",
         "stairs", "pressure_plate", "fence_gate", "door", "trapdoor"]
PLACEHOLDER_BY_KIND = dict(zip(KINDS, OAK_TO_PLACEHOLDER.values()))


def overworld(w: str) -> dict[str, str]:
    return {"planks": f"{w}_planks", "wood": f"{w}_wood", "stripped_wood": f"stripped_{w}_wood",
            "log": f"{w}_log", "stripped_log": f"stripped_{w}_log", "fence": f"{w}_fence",
            "button": f"{w}_button", "slab": f"{w}_slab", "stairs": f"{w}_stairs",
            "pressure_plate": f"{w}_pressure_plate", "fence_gate": f"{w}_fence_gate",
            "door": f"{w}_door", "trapdoor": f"{w}_trapdoor"}


def nether(w: str) -> dict[str, str]:
    return {**overworld(w), "wood": f"{w}_hyphae", "stripped_wood": f"stripped_{w}_hyphae",
            "log": f"{w}_stem", "stripped_log": f"stripped_{w}_stem"}


BAMBOO = {**overworld("bamboo"), "wood": "bamboo_block", "stripped_wood": "stripped_bamboo_block",
          "log": "bamboo_block", "stripped_log": "stripped_bamboo_block"}
BAMBOO_MOSAIC = {**BAMBOO, "planks": "bamboo_mosaic", "slab": "bamboo_mosaic_slab",
                 "stairs": "bamboo_mosaic_stairs", "wood": "stripped_bamboo_block",
                 "stripped_wood": "bamboo_block", "log": "stripped_bamboo_block",
                 "stripped_log": "bamboo_block"}

# Stage id → its wood family's block table (same as StageWoodFamily).
STAGE_WOOD = {
    "wood_oak": overworld("oak"), "birch": overworld("birch"), "acacia": overworld("acacia"),
    "jungle": overworld("jungle"), "mangrove": overworld("mangrove"), "cherry": overworld("cherry"),
    "crimson": nether("crimson"), "warped": nether("warped"), "bamboo": BAMBOO,
    "darkwood": overworld("dark_oak"), "bamboo_mosaic": BAMBOO_MOSAIC, "spruce": overworld("spruce"),
    # Not a wood stage, but portal_short links mud_wood_oak under it.
    "copper": overworld("oak"),
}

ID_RE = re.compile(r"minecraft:[a-z_]+")


OAK = overworld("oak")


def oak_to_stage_map(blocks: dict[str, str]) -> dict[str, str]:
    """`minecraft:oak_<kind>` → `minecraft:<this wood's block>` — what the placeholder set resolves
    to for that stage, so a clone is compared in the space the player actually sees."""
    return {f"minecraft:{OAK[kind]}": f"minecraft:{blocks[kind]}" for kind in KINDS}


def map_text(text: str, mapping: dict[str, str]) -> str:
    return ID_RE.sub(lambda m: mapping.get(m.group(0), m.group(0)), text)


# ── NBT canonical form ───────────────────────────────────────────────────────

def to_py(tag):
    if tag.id == COMPOUND:
        return {name: to_py(child) for name, child in tag.value}
    if tag.id == LIST:
        return [to_py(t) for t in tag.value[1]]
    return tag.value


def canonical(root, mapping: dict[str, str]):
    """Equality of this means 'same template': size, blocks as (pos, mapped state, nbt), entities —
    palette order and stray metadata don't matter."""
    py = to_py(root)
    palette = py.get("palette") or (py.get("palettes") or [[]])[0]
    states = []
    for entry in palette:
        name = mapping.get(entry["Name"], entry["Name"])
        props = tuple(sorted((entry.get("Properties") or {}).items()))
        states.append((name, props))
    blocks = set()
    for b in py.get("blocks", []):
        blocks.add((tuple(b["pos"]), states[b["state"]], json.dumps(b.get("nbt"), sort_keys=True, default=str)))
    return {"size": tuple(py.get("size", [])), "blocks": blocks,
            "entities": json.dumps(py.get("entities", []), sort_keys=True, default=str)}


def sidecar(path: Path, mapping: dict[str, str]):
    """The variants sidecar with ids mapped, minus its `schemaVersion` (an editor re-save bumps it)."""
    if not path.exists():
        return None
    data = json.loads(map_text(path.read_text(encoding="utf-8"), mapping))
    if isinstance(data, dict):
        data.pop("schemaVersion", None)
    return normalise(data)


def normalise(node):
    """Drop `"half": "bottom"` — the v7+ editor writes it explicitly, older sidecars leave it out,
    and `RotationApplier.applyHalf` treats absent (NONE) exactly as BOTTOM."""
    if isinstance(node, dict):
        return {k: normalise(v) for k, v in node.items() if not (k == "half" and v == "bottom")}
    if isinstance(node, list):
        return [normalise(v) for v in node]
    return node


def read_nbt(path: Path):
    return read(gzip.decompress(path.read_bytes()))


def build_shared_nbt(src: Path, dst: Path) -> None:
    name, root = read_nbt(src)
    out = clone(root)
    palette = get(out, "palette")
    assert palette.id == LIST
    for entry in palette.value[1]:
        tag = get(entry, "Name")
        assert tag.id == STRING
        tag.value = OAK_TO_PLACEHOLDER.get(tag.value, tag.value)
    dst.write_bytes(gzip.compress(write(name, out)))


def build_shared_variants(src: Path, dst: Path) -> None:
    text = map_text(src.read_text(encoding="utf-8"), OAK_TO_PLACEHOLDER)
    json.loads(text)
    dst.write_text(text, encoding="utf-8")


# ── discovery ────────────────────────────────────────────────────────────────

def family_of(part_name: str) -> str | None:
    """`mudbricks_wood_oak_darkwo_acacia` → `mudbricks`; None for a name outside the wood set."""
    m = re.match(r"^(mudbricks|mudopen|mudwindows|mud)_wood(_|$)", part_name)
    return m.group(1) if m else None


def shared_name(family: str) -> str:
    return f"{family}_wood"


def linked_clones() -> dict[tuple[str, str], set[str]]:
    """(slot, per-wood part name) → the stages that link it, from every parts manifest."""
    out: dict[tuple[str, str], set[str]] = {}
    for path in sorted(TEMPLATES.glob("*.parts.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        for slot, entries in manifest.items():
            if slot not in SLOTS or not isinstance(entries, list):
                continue
            for e in entries:
                if not isinstance(e, dict):
                    continue
                name, stage = e.get("name", ""), e.get("stage")
                fam = family_of(name)
                if fam and name != shared_name(fam) and stage in STAGE_WOOD:
                    out.setdefault((slot, name), set()).add(stage)
    return out


# ── passes ───────────────────────────────────────────────────────────────────

def build_shared(check: bool) -> None:
    print("shared parts:")
    for slot, sources in SOURCE_PARTS.items():
        for source in sources:
            target = shared_name(family_of(source))
            for ext, builder in ((".nbt", build_shared_nbt), (".variants.json", build_shared_variants)):
                src, dst = PARTS / slot / (source + ext), PARTS / slot / (target + ext)
                if dst.exists():
                    continue  # already shared (the oak source is gone after the first run)
                if not src.exists():
                    raise SystemExit(f"missing oak source: {src}")
                print(f"  + {dst.relative_to(ROOT)}")
                if not check:
                    builder(src, dst)


def verify_clones() -> tuple[list[tuple[str, str]], list[str]]:
    """Every linked clone against the oak original resolved for its stage.
    Returns (replaceable (slot, name) pairs, divergence messages)."""
    ok, bad = [], []
    print("clones:")
    for (slot, name), stages in sorted(linked_clones().items()):
        family = family_of(name)
        oak_nbt = PARTS / slot / (family + "_wood_oak.nbt")
        oak_var = PARTS / slot / (family + "_wood_oak.variants.json")
        clone_nbt = PARTS / slot / (name + ".nbt")
        clone_var = PARTS / slot / (name + ".variants.json")
        if not clone_nbt.exists():
            bad.append(f"{slot}/{name}: linked by {sorted(stages)} but no .nbt on disk")
            continue
        oak_root = read_nbt(oak_nbt)[1]
        clone_canon = canonical(read_nbt(clone_nbt)[1], {})
        clone_sidecar = sidecar(clone_var, {})
        verdicts = []
        for stage in sorted(stages):
            # Oak original resolved to this stage's wood == what the shared part will stamp there.
            mapping = oak_to_stage_map(STAGE_WOOD[stage])
            same_nbt = clone_canon == canonical(oak_root, mapping)
            same_var = clone_sidecar == sidecar(oak_var, mapping)
            verdicts.append((stage, same_nbt, same_var))
        if all(n and v for _, n, v in verdicts):
            print(f"  = {slot}/{name}  ({', '.join(s for s, _, _ in verdicts)})")
            ok.append((slot, name))
        else:
            detail = ", ".join(f"{s}:" + "+".join(w for w, flag in (("nbt", not n), ("sidecar", not v)) if flag)
                               for s, n, v in verdicts if not (n and v))
            print(f"  ! {slot}/{name}  DIVERGES — {detail}")
            bad.append(f"{slot}/{name}: {detail}")
    return ok, bad


def rewrite_manifests(replace: set[tuple[str, str]], check: bool) -> None:
    for path in sorted(TEMPLATES.glob("*.parts.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        n = 0
        for slot, entries in manifest.items():
            if slot not in SLOTS or not isinstance(entries, list):
                continue
            for e in entries:
                if isinstance(e, dict) and (slot, e.get("name", "")) in replace:
                    e["name"] = shared_name(family_of(e["name"]))
                    n += 1
        if n:
            print(f"  {path.name}: {n} entries → shared")
            if not check:
                path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def delete_clones(replace: set[tuple[str, str]], check: bool) -> None:
    for slot, name in sorted(replace):
        for ext in (".nbt", ".variants.json"):
            p = PARTS / slot / (name + ext)
            if p.exists():
                print(f"  - {p.relative_to(ROOT)}")
                if not check:
                    p.unlink()


def sync_slot_manifests(check: bool) -> None:
    """Legacy `parts/<slot>/manifest.json` = the .nbt basenames on disk (drift is only logged)."""
    for slot in SLOTS:
        path = PARTS / slot / "manifest.json"
        names = sorted(f.stem for f in (PARTS / slot).glob("*.nbt"))
        if path.exists() and json.loads(path.read_text(encoding="utf-8")) == names:
            continue
        print(f"  slot manifest parts/{slot}: {len(names)} parts")
        if not check:
            path.write_text(json.dumps(names), encoding="utf-8")


def lock_wood(stage_ids: set[str], check: bool) -> None:
    stages = json.loads(STAGES.read_text(encoding="utf-8"))
    changed = False
    for sid in sorted(stage_ids):
        pal = stages.get(sid, {}).get("palette")
        if pal is None:
            print(f"  ! stage {sid}: no baked palette — bake it before locking")
            continue
        if pal.get("woodLocked"):
            continue
        print(f"  lock wood on stage {sid} ({pal.get('wood')})")
        pal["woodLocked"] = True
        changed = True
    if changed and not check:
        STAGES.write_text(json.dumps(dict(sorted(stages.items())), indent=2), encoding="utf-8")


def main() -> None:
    args = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    args.add_argument("--check", action="store_true", help="report what would change; write nothing")
    check = args.parse_args().check

    build_shared(check)
    ok, bad = verify_clones()
    replace = set(ok)
    stage_ids = set()
    for key, stages in linked_clones().items():
        if key in replace:
            stage_ids |= stages
    print("manifests:")
    rewrite_manifests(replace, check)
    print("delete:")
    delete_clones(replace, check)
    sync_slot_manifests(check)
    print("stages:")
    lock_wood(stage_ids, check)
    if bad:
        print("\nLeft in place (not a pure id-swap of the oak original):")
        for b in bad:
            print("  " + b)
        sys.exit(1 if check else 0)


if __name__ == "__main__":
    main()
