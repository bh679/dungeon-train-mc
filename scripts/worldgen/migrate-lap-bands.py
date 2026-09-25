#!/usr/bin/env python3
"""Rewrite pre-lap TrainPhase gate tokens in JSON files to LapBand tokens.

Template spawn gates used to name 18 TrainPhase values ("NETHER", "VOID", ...). They now name per-lap
band occurrences (LapBand: "V_NETHER", "M_NETHER", ...). The game still READS the old names — each
expands to every band it used to cover (LapBand.fromLegacy) — so this is not needed for correctness;
it keeps the shipped data in the canonical form and makes it editable per lap.

Every `"phases": [ ... ]` array is rewritten in place (its layout and indentation are kept, entries
come out in LapBand declaration order). Entries that are already LapBand names pass through; an
unknown entry aborts. Keep FROM_LEGACY and ORDER in step with LapBand.java (LapBandLegacyTableTest
asserts they match).

Usage: python3 scripts/worldgen/migrate-lap-bands.py [--check] [root ...]
  default root: src/main/resources/data/dungeontrain.  --check: exit 1 if anything would change.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ORDER = [
    "V_OVERWORLD_1", "V_NETHER", "V_OVERWORLD_2", "V_END", "V_UPSIDE_DOWN", "V_REASSEMBLY",
    "M_OVERWORLD_1", "M_NETHER", "M_OVERWORLD_2", "M_END", "M_SPHERES", "M_OVERWORLD_3",
    "L_LARGE_BIOMES", "L_AMPLIFIED", "L_BETA", "L_FAR_LANDS", "L_CAVES_OF_CHAOS", "L_SKYLANDS",
    "L_FLOATING", "L_ALPHA", "L_INFDEV", "L_CLASSIC", "L_SUPERFLAT", "L_VOID",
    "C_OVERWORLD_1", "C_CHUNCKS", "C_OVERWORLD_2", "C_STACKS",
]

FROM_LEGACY = {
    "OVERWORLD": ["V_OVERWORLD_1", "V_OVERWORLD_2", "V_REASSEMBLY", "M_OVERWORLD_1", "M_OVERWORLD_2",
                  "M_OVERWORLD_3", "L_SUPERFLAT", "C_OVERWORLD_1", "C_OVERWORLD_2"],
    "NETHER": ["V_NETHER", "M_NETHER"],
    "END": ["V_END", "M_END"],
    "VOID": ["L_VOID"],
    "UPSIDE_DOWN": ["V_UPSIDE_DOWN"],
    "CHUNCKS": ["C_CHUNCKS"],
    "SPHERES": ["M_SPHERES"],
    "STACKS": ["C_STACKS"],
    "BETA": ["L_BETA"],
    "ALPHA": ["L_ALPHA"],
    "SKYLANDS": ["L_SKYLANDS"],
    "INFDEV": ["L_INFDEV"],
    "FLOATING": ["L_FLOATING"],
    "FAR_LANDS": ["L_FAR_LANDS"],
    "CLASSIC": ["L_CLASSIC"],
    "CAVES_OF_CHAOS": ["L_CAVES_OF_CHAOS"],
    "LARGE_BIOMES": ["L_LARGE_BIOMES"],
    "AMPLIFIED": ["L_AMPLIFIED"],
}

ARRAY = re.compile(r'("phases"\s*:\s*\[)([^\]]*)(\])')
ITEM = re.compile(r'"([^"]*)"')


def migrate_tokens(tokens: list[str], where: str) -> list[str]:
    out: set[str] = set()
    for t in tokens:
        u = t.strip().upper()
        if u in ORDER:
            out.add(u)
        elif u in FROM_LEGACY:
            out.update(FROM_LEGACY[u])
        else:
            raise SystemExit(f"{where}: unknown phase token {t!r}")
    return [b for b in ORDER if b in out]


def rewrite_array(m: re.Match, where: str) -> str:
    body = m.group(2)
    tokens = ITEM.findall(body)
    new = migrate_tokens(tokens, where)
    if "\n" in body:
        # Multi-line: reuse the first item's indentation and the closing bracket's.
        item_indent = re.search(r'\n([ \t]*)"', body).group(1)
        close_indent = body[body.rfind("\n") + 1:]
        inner = ",\n".join(f'{item_indent}"{t}"' for t in new)
        return f'{m.group(1)}\n{inner}\n{close_indent}{m.group(3)}'
    return m.group(1) + ", ".join(f'"{t}"' for t in new) + m.group(3)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("roots", nargs="*", default=["src/main/resources/data/dungeontrain"])
    args = ap.parse_args()
    changed = 0
    for root in args.roots:
        for path in sorted(Path(root).rglob("*.json")):
            raw = path.read_bytes().decode("utf-8")
            new = ARRAY.sub(lambda m: rewrite_array(m, str(path)), raw)
            if new != raw:
                changed += 1
                print(f"{'would rewrite' if args.check else 'rewrote'} {path}")
                if not args.check:
                    path.write_bytes(new.encode("utf-8"))
    print(f"{changed} file(s) {'need migrating' if args.check else 'migrated'}")
    return 1 if (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
