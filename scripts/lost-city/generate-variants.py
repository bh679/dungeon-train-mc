#!/usr/bin/env python3
"""Emit the data that places DT's own Lost City buildings in their designs.

    python3 scripts/lost-city/generate-variants.py            # write processor lists, pools, structures, set entries
    python3 scripts/lost-city/generate-variants.py --check    # fail if the committed data drifts from the recipes

Recipes live in lostcity/variants.py; the templates they dress are written by build-templates.py.
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lostcity.archetypes import ALL  # noqa: E402
from lostcity.datagen import SET_PATH, render  # noqa: E402
from lostcity.variants import designs  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    existing = json.loads((ROOT / SET_PATH).read_text())
    files = render(ALL, designs(), existing)
    if args.check:
        drifted = [p for p, data in files.items() if not (ROOT / p).exists() or (ROOT / p).read_bytes() != data]
        if drifted:
            print("lost-city data drifts from its recipes; re-run generate-variants.py:", *drifted, sep="\n  ")
            return 1
        print(f"lost-city data up to date ({len(files)} files)")
        return 0
    for path, data in files.items():
        (ROOT / path).parent.mkdir(parents=True, exist_ok=True)
        (ROOT / path).write_bytes(data)
    print(f"wrote {len(files)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
