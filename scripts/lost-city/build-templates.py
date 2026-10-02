#!/usr/bin/env python3
"""Write Dungeon Train's own Lost City building templates.

    python3 scripts/lost-city/build-templates.py            # (re)write every .nbt + manifest.json
    python3 scripts/lost-city/build-templates.py --check    # regenerate in memory, fail on any drift
    python3 scripts/lost-city/build-templates.py --only office_tower hotel

Output: src/main/resources/data/dungeontrain/structure/lost_city/<name>.nbt and manifest.json. The
designs live in lostcity/archetypes/; edit a recipe and re-run. Everything written here is DT's own —
no Big Lost City file is read or copied (see lostcity/__init__.py).
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lostcity import variantdoc  # noqa: E402
from lostcity.archetypes import ALL  # noqa: E402
from lostcity.check import validate  # noqa: E402
from lostcity.nbt_out import to_bytes  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/data/dungeontrain/structure/lost_city"
AUTHORED = Path(__file__).resolve().parent / "authored-variants.json"
VARIANTS = ".variants.json"


def variants_for(cells, spec, authored: frozenset[str]) -> tuple[bytes | None, list[str]]:
    """The building's variants document to write (None: hand-kept or nothing to vary), and what is wrong with it."""
    if spec.name in authored:
        kept = OUT / (spec.name + VARIANTS)
        if not kept.exists():
            return None, [f"{spec.name}: listed in {AUTHORED.name} but has no {kept.name}"]
        return None, variantdoc.validate(variantdoc.loads(kept.read_bytes()), cells, spec)
    doc = variantdoc.seed(cells, spec)
    if not doc:
        return None, []
    data = variantdoc.dumps(doc)
    return data, variantdoc.validate(variantdoc.loads(data), cells, spec)


def render_all(only: set[str] | None) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    manifest: dict[str, dict] = {}
    errors: list[str] = []
    authored = frozenset(json.loads(AUTHORED.read_text()))
    for archetype in ALL:
        if only and archetype.spec.name not in only:
            continue
        cells = archetype.render()
        errors += validate(cells, archetype.spec)
        files[archetype.spec.name + ".nbt"] = to_bytes(cells, archetype.spec.size)
        document, problems = variants_for(cells, archetype.spec, authored)
        errors += problems
        if document is not None:
            files[archetype.spec.name + VARIANTS] = document
        manifest[archetype.spec.name] = archetype.spec.manifest()
    if errors:
        raise SystemExit("\n".join(errors))
    if not only:
        files["manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    return files


def check(files: dict[str, bytes]) -> int:
    drifted = [name for name, data in files.items() if not (OUT / name).exists() or (OUT / name).read_bytes() != data]
    if drifted:
        print("lost-city templates drift from their recipes; re-run build-templates.py:", *drifted, sep="\n  ")
        return 1
    print(f"lost-city templates up to date ({len(files)} files)")
    return 0


def write(files: dict[str, bytes]) -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (OUT / name).write_bytes(data)
        print(f"wrote {name} ({len(data):,} bytes)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify committed files match the recipes")
    parser.add_argument("--only", nargs="*", help="archetype names to write (default: all)")
    args = parser.parse_args()
    files = render_all(set(args.only) if args.only else None)
    return check(files) if args.check else write(files)


if __name__ == "__main__":
    sys.exit(main())
