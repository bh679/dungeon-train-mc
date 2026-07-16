#!/usr/bin/env python3
"""
Validate a Dungeon Train locale tree against the known-good ``es_es`` reference.

The localization engine resolves every overlay by *exact locale* with per-file English
fallback, and the narrative/AIN loaders key off structural fields (ids, indices, array
positions, weights). A translator must therefore change **only** prose strings and leave the
shape byte-identical. This gate enforces that:

  1. Every GUI lang file (dungeontrain / adventureitemnames / playermob / discordpresence)
     has exactly the reference key set, with the same printf-style placeholders per value.
  2. The narrative_localizations/ and ain_localizations/ subtrees mirror es_es's file set.
  3. Every JSON parses and is *shape-identical* to es_es: same object keys, same array
     lengths, equal numbers/booleans/null, and equal structural strings (id / ref / page /
     _translator_note). Only translatable string values may differ.
  4. The Death Note trigger title is <= 15 characters (Minecraft book-title limit).
  5. The localization credit names the locale.

Usage:  validate-locale.py <loc> [<loc> ...]
Exit code is non-zero if any locale fails.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "src/main/resources/assets"
DATA = REPO / "src/main/resources/data/dungeontrain"
REF = "es_es"                       # structural template (validated in PR #768)
GUI_MODS = ["dungeontrain", "adventureitemnames", "playermob", "discordpresence"]
DATA_SUBTREES = ["narrative_localizations", "ain_localizations"]

# Strings whose value is structural (loader-significant) and must match the reference exactly.
STRUCT_STR_KEYS = {"id", "ref", "page", "_translator_note"}
# Numeric keys whose value legitimately varies per translation (character offsets into prose).
# These are shape-checked (must be a number) but range-checked separately, not equality-checked.
SOFT_NUM_KEYS = {"offset"}
# printf-style tokens MC's format parser recognises ( %s %d %1$s %% … ).
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")
MAX_TITLE_CHARS = 15               # DeathNoteTitleLocalization.VANILLA_MAX_TITLE_CHARS


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def placeholders(value):
    return sorted(PLACEHOLDER.findall(value)) if isinstance(value, str) else []


def compare_shape(ref, loc, path, errors):
    """Recursively assert loc has the same shape as ref (values may differ only for prose)."""
    if type(ref) is not type(loc):
        # int/float mismatch is tolerated (JSON 1 vs 1.0); everything else is an error.
        if not (isinstance(ref, (int, float)) and isinstance(loc, (int, float))
                and not isinstance(ref, bool) and not isinstance(loc, bool)):
            errors.append(f"{path}: type {type(loc).__name__} != {type(ref).__name__}")
            return
    if isinstance(ref, dict):
        if set(ref) != set(loc):
            missing = set(ref) - set(loc)
            extra = set(loc) - set(ref)
            if missing:
                errors.append(f"{path}: missing keys {sorted(missing)}")
            if extra:
                errors.append(f"{path}: unexpected keys {sorted(extra)}")
        for key in ref:
            if key not in loc:
                continue
            if key in STRUCT_STR_KEYS:
                if ref[key] != loc[key]:
                    errors.append(f"{path}.{key}: structural value changed "
                                  f"({loc[key]!r} != {ref[key]!r})")
            elif key in SOFT_NUM_KEYS:
                if not isinstance(loc[key], (int, float)) or isinstance(loc[key], bool):
                    errors.append(f"{path}.{key}: expected a number, got {loc[key]!r}")
            else:
                compare_shape(ref[key], loc[key], f"{path}.{key}", errors)
    elif isinstance(ref, list):
        if len(ref) != len(loc):
            errors.append(f"{path}: array length {len(loc)} != {len(ref)}")
        for i in range(min(len(ref), len(loc))):
            compare_shape(ref[i], loc[i], f"{path}[{i}]", errors)
    elif isinstance(ref, bool):
        if ref != loc:
            errors.append(f"{path}: {loc} != {ref}")
    elif isinstance(ref, (int, float)):
        if ref != loc:
            errors.append(f"{path}: {loc} != {ref}")
    # plain strings (non-structural): translatable, no value check.


def rel_json_set(root):
    return {p.relative_to(root).as_posix() for p in root.rglob("*.json")} if root.is_dir() else set()


def validate_gui(loc, errors):
    for mod in GUI_MODS:
        ref_path = ASSETS / mod / "lang" / f"{REF}.json"
        loc_path = ASSETS / mod / "lang" / f"{loc}.json"
        if not ref_path.exists():
            errors.append(f"[gui:{mod}] reference {REF}.json missing — cannot validate")
            continue
        if not loc_path.exists():
            errors.append(f"[gui:{mod}] {loc}.json missing")
            continue
        try:
            ref, cur = load(ref_path), load(loc_path)
        except json.JSONDecodeError as exc:
            errors.append(f"[gui:{mod}] JSON error: {exc}")
            continue
        missing = set(ref) - set(cur)
        extra = set(cur) - set(ref)
        if missing:
            errors.append(f"[gui:{mod}] missing {len(missing)} keys e.g. {sorted(missing)[:3]}")
        if extra:
            errors.append(f"[gui:{mod}] {len(extra)} unexpected keys e.g. {sorted(extra)[:3]}")
        for key in set(ref) & set(cur):
            if placeholders(ref[key]) != placeholders(cur[key]):
                errors.append(f"[gui:{mod}] '{key}': placeholders "
                              f"{placeholders(cur[key])} != {placeholders(ref[key])}")
            if isinstance(cur[key], str) and not cur[key].strip():
                errors.append(f"[gui:{mod}] '{key}': empty value")


def validate_data(loc, errors):
    for sub in DATA_SUBTREES:
        ref_root, loc_root = DATA / sub / REF, DATA / sub / loc
        ref_files, loc_files = rel_json_set(ref_root), rel_json_set(loc_root)
        for miss in sorted(ref_files - loc_files):
            errors.append(f"[{sub}] missing file {miss}")
        for extra in sorted(loc_files - ref_files):
            errors.append(f"[{sub}] unexpected file {extra}")
        for rel in sorted(ref_files & loc_files):
            try:
                ref, cur = load(ref_root / rel), load(loc_root / rel)
            except json.JSONDecodeError as exc:
                errors.append(f"[{sub}] {rel}: JSON error: {exc}")
                continue
            compare_shape(ref, cur, f"{sub}/{rel}", errors)
            if rel.endswith("random_books/deathnote.json"):
                title = cur.get("title", "")
                if len(title) > MAX_TITLE_CHARS:
                    errors.append(f"[{sub}] {rel}: title {title!r} is {len(title)} chars "
                                  f"(> {MAX_TITLE_CHARS}) — trigger will be silently skipped")
            if sub == "narrative_localizations" and isinstance(cur, dict):
                check_note_offsets(f"{sub}/{rel}", cur, errors)


def check_note_offsets(where, story, errors):
    """A note's offset must index within its variant's translated text (see zh_cn augustus_park)."""
    for li, letter in enumerate(story.get("letters", []) or []):
        variants = letter.get("variants", []) or []
        for ni, note in enumerate(letter.get("notes", []) or []):
            vi = note.get("variant")
            off = note.get("offset")
            if not isinstance(vi, int) or vi < 0 or vi >= len(variants):
                errors.append(f"{where}: letters[{li}].notes[{ni}].variant {vi} out of range")
                continue
            if not isinstance(off, int) or off < 0 or off > len(variants[vi]):
                errors.append(f"{where}: letters[{li}].notes[{ni}].offset {off} outside "
                              f"[0, {len(variants[vi])}] of its variant text")


def validate_credit(loc, errors):
    path = ASSETS / "dungeontrain/localization_credits" / f"{loc}.json"
    if not path.exists():
        errors.append(f"[credit] {loc}.json missing")
        return
    try:
        cur = load(path)
    except json.JSONDecodeError as exc:
        errors.append(f"[credit] JSON error: {exc}")
        return
    if cur.get("locale") != loc:
        errors.append(f"[credit] locale field {cur.get('locale')!r} != {loc!r}")
    if not str(cur.get("name", "")).strip():
        errors.append("[credit] empty name")


def main(locales):
    overall_ok = True
    for loc in locales:
        errors = []
        validate_gui(loc, errors)
        validate_data(loc, errors)
        validate_credit(loc, errors)
        if errors:
            overall_ok = False
            print(f"❌ {loc}: {len(errors)} problem(s)")
            for err in errors[:40]:
                print(f"    - {err}")
            if len(errors) > 40:
                print(f"    … and {len(errors) - 40} more")
        else:
            print(f"✅ {loc}: OK")
    return 0 if overall_ok else 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
