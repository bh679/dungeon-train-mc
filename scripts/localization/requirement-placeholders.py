#!/usr/bin/env python3
"""Swap the hand-typed requirement number in every locale's advancement description for ``%s``.

The mod now feeds each requirement advancement's number into its description as a translation
argument (``RequirementJsonRewriter`` sets ``display.description.with``), so the lang text must say
``%s`` where it used to say ``1,000``. ``en_us.json`` is rewritten by hand; this script does the
twenty other locales line-in-place, without reformatting anything — the same rules
``merge-locale-keys.py`` follows (blank-line grouping kept, zh_cn stays CRLF).

For every key it knows the shipped number (read from the advancement JSON, ticks converted to
hours or days), builds the digit spellings a locale might use (``1,000`` / ``1.000`` / ``1 000`` /
``1 000`` / ``1000``), and replaces the ONE occurrence. A value with no match, or more than one, is
reported and left alone — those are the spelled-out numbers ("two hours", "deux heures") that need a
human edit. ``validate-locale.py`` then enforces ``%s`` parity, so nothing can be missed silently.

Usage::

    python3 scripts/localization/requirement-placeholders.py            # rewrite + report
    python3 scripts/localization/requirement-placeholders.py --dry-run  # report only
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LANG_DIR = ROOT / "src/main/resources/assets/dungeontrain/lang"
ADV_DIR = ROOT / "src/main/resources/data/dungeontrain/advancement/dungeon_train"
KEY_PREFIX = "advancements.dungeontrain.dungeon_train."
TICKS_PER_HOUR = 72_000
FIELDS = ("threshold", "thresholdReads", "thresholdMeters", "thresholdTicks")

#: Keys whose English deliberately carries no number (the text says "every biome", or the value is
#: 1 and unspoken). Skipped: nothing to swap.
NO_PLACEHOLDER = {"all_biome_families", "roof_run_1"}
#: Keys where the number appears twice — English uses an indexed ``%1$s`` for both.
INDEXED = {"the_long_way_back"}


def requirement_numbers() -> dict[str, int]:
    """advancement name → the number a description would spell (hours/days for tick fields)."""
    out: dict[str, int] = {}
    for path in sorted(ADV_DIR.glob("*.json")):
        adv = json.loads(path.read_text(encoding="utf-8"))
        for crit in adv.get("criteria", {}).values():
            cond = crit.get("conditions") or {}
            for field in FIELDS:
                if isinstance(cond.get(field), int):
                    n = cond[field]
                    if field == "thresholdTicks":
                        hours = n // TICKS_PER_HOUR
                        n = hours // 24 if hours % 24 == 0 and hours >= 24 else hours
                    out[path.stem] = n
    return out


def spellings(n: int) -> list[str]:
    """Digit forms of n, longest first so grouped forms win over the bare digits."""
    digits = str(n)
    forms = {digits}
    if n >= 1000:
        for sep in (",", ".", " ", " ", " ", "'"):
            forms.add(f"{n:,}".replace(",", sep))
    return sorted(forms, key=len, reverse=True)


def swap(value: str, n: int, placeholder: str) -> str | None:
    """value with its single occurrence of n replaced, or None when not exactly one match."""
    for form in spellings(n):
        # Not inside a longer run of digits: "10" must not match inside "100".
        pattern = re.compile(r"(?<![\d])" + re.escape(form) + r"(?![\d])")
        hits = pattern.findall(value)
        if len(hits) == 1:
            return pattern.sub(placeholder, value)
        if len(hits) > 1:
            return None
    return None


def process_locale(path: Path, numbers: dict[str, int], dry_run: bool) -> tuple[int, list[str]]:
    raw = path.read_bytes().decode("utf-8")
    lines = raw.split("\n")
    changed = 0
    leftovers: list[str] = []
    for i, line in enumerate(lines):
        m = re.match(r'^(\s*)"' + re.escape(KEY_PREFIX) + r'([a-z0-9_]+)\.description":\s*(".*")(,?)(\r?)$', line)
        if not m:
            continue
        name = m.group(2)
        if name not in numbers or name in NO_PLACEHOLDER:
            continue
        value = json.loads(m.group(3))
        if "%s" in value or "%1$s" in value:
            continue  # already converted
        placeholder = "%1$s" if name in INDEXED else "%s"
        if name in INDEXED:
            # Both occurrences become the indexed placeholder.
            forms = [f for f in spellings(numbers[name])
                     if re.search(r"(?<![\d])" + re.escape(f) + r"(?![\d])", value)]
            new = re.sub(r"(?<![\d])" + re.escape(forms[0]) + r"(?![\d])", placeholder, value) if forms else None
            if new is not None and new.count(placeholder) != 2:
                new = None
        else:
            new = swap(value, numbers[name], placeholder)
        if new is None:
            leftovers.append(f"{name}: {value}")
            continue
        lines[i] = f'{m.group(1)}"{KEY_PREFIX}{name}.description": {json.dumps(new, ensure_ascii=False)}{m.group(4)}{m.group(5)}'
        changed += 1
    if changed and not dry_run:
        path.write_bytes("\n".join(lines).encode("utf-8"))
    return changed, leftovers


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    numbers = requirement_numbers()
    total_left = 0
    for path in sorted(LANG_DIR.glob("*.json")):
        if path.stem == "en_us":
            continue
        changed, leftovers = process_locale(path, numbers, args.dry_run)
        print(f"{path.stem}: {changed} swapped, {len(leftovers)} left")
        for item in leftovers:
            print(f"    {item}")
        total_left += len(leftovers)
    return 0


if __name__ == "__main__":
    sys.exit(main())
