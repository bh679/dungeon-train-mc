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
     _translator_note). Only translatable string values may differ. The single exemption is
     array LENGTH in a flat AIN word pool — see ``flat_text_pool``.
  4. Every trigger-book title (Death Note, Love Note) is <= 15 characters (Minecraft book-title
     limit) — a longer one can't be typed, so that language silently loses the mechanic.
  5. The localization credit names the locale.

Usage:  validate-locale.py <loc> [<loc> ...]
        validate-locale.py --values-only <loc> [<loc> ...]
Exit code is non-zero if any locale fails.

``--values-only`` checks just the things a change to TRANSLATED TEXT can break — placeholders,
empty strings, trigger-book title length, note offsets into prose — and skips key sets, file
sets and structural shape. It exists for the relay import job, which edits values and nothing
else: gating that job on the whole structural suite meant a translation of one Vietnamese string
could not be imported because the vi_vn AIN overlay had been missing `item_types` for months.
A tool that refuses today's correct work over yesterday's unrelated debt gets ignored, so the
import job asks the narrower question it is entitled to ask — "did I break anything?" — and the
full suite stays the gate for PRs.
"""
import json
import re
import sys
from pathlib import Path

from plural_forms import PLURAL_SUFFIXES, expected_keys, plural_categories  # noqa: F401

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "src/main/resources/assets"
DATA = REPO / "src/main/resources/data/dungeontrain"
REF = "es_es"                       # structural template (validated in PR #768)
# The GUI key set is measured against ENGLISH for our own namespace, and against the es_es
# template for the three sibling overlays — those ship no en_us here at all (their English lives
# in the upstream mod's jar), so es_es remains the only reference they have.
#
# `dungeontrain` used to be measured against es_es too, and that is precisely how three weeks of
# drift stayed invisible twice: a key added to en_us.json alone left every locale INCLUDING the
# reference untouched, so the comparison was of one gap against an identical gap and passed. By
# 2026-08-30 en_us had 1791 keys and every other locale was short by 144.
GUI_REF = {"dungeontrain": "en_us"}
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
# Instruction books whose TITLE is itself the in-game trigger word a player types (NoteKind).
# Their translated titles must stay typeable, or that language loses the mechanic silently.
TRIGGER_BOOKS = ("deathnote", "lovenote")


def reference_twin(key, ref, bases):
    """The reference key a plural form is checked against — its family's `.other`."""
    base, _, suffix = key.rpartition(".")
    if base in bases and suffix in PLURAL_SUFFIXES:
        return base + ".other"
    return key


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def placeholders(value):
    """A value's ARGUMENT tokens, sorted.

    `%%` is matched by the pattern but excluded here: it is an escaped literal percent, not an
    argument the caller passes. A translation is entitled to contain one where the English does
    not, or to drop one by rephrasing — six locales render the AI-policy line without a percent
    sign at all, and that is a translation decision, not a defect. What must match is the
    arguments, because those are positional.
    """
    if not isinstance(value, str):
        return []
    return sorted(t for t in PLACEHOLDER.findall(value) if t != "%%")


def flat_text_pool(ref):
    """True for an AIN pool that is nothing but a word list — every entry exactly ``{"text": …}``.

    ``type_synonyms`` is the only pool of the 31 whose entries carry anything else: its
    ``item_types`` binds each synonym to the item tag it may name, so a missing or extra entry
    there shifts every later tag and a sword starts being called a pair of boots. Length is
    load-bearing, and it stays checked.

    The other 30 are flat lists drawn at random by ``NameComposer.pickPoolEntry``. Position binds
    nothing, so requiring a locale to hold *exactly* as many synonyms as Spanish is parity for its
    own sake: it fails zh_cn for having thought of five more titles than es_es did, which is the
    gate objecting to a translator doing the job well. Entry SHAPE is still enforced — every entry
    must be a one-key ``text`` string — so a malformed pool is still caught.
    """
    entries = ref.get("entries") if isinstance(ref, dict) else None
    return bool(entries) and isinstance(entries, list) and all(
        isinstance(e, dict) and set(e) == {"text"} and isinstance(e["text"], str) for e in entries)


def compare_flat_pool(ref, loc, path, errors):
    """Shape-check a flat word pool without comparing its length."""
    if not isinstance(loc, dict) or set(ref) != set(loc):
        errors.append(f"{path}: pool keys {sorted(loc) if isinstance(loc, dict) else loc!r} "
                      f"!= {sorted(ref)}")
        return
    if ref.get("id") != loc.get("id"):
        errors.append(f"{path}.id: structural value changed "
                      f"({loc.get('id')!r} != {ref.get('id')!r})")
    if not flat_text_pool(loc):
        errors.append(f"{path}.entries: every entry must be a single 'text' string")


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


def validate_gui(loc, errors, warnings, values_only=False):
    for mod in GUI_MODS:
        ref_name = GUI_REF.get(mod, REF)
        ref_path = ASSETS / mod / "lang" / f"{ref_name}.json"
        loc_path = ASSETS / mod / "lang" / f"{loc}.json"
        if not ref_path.exists():
            errors.append(f"[gui:{mod}] reference {ref_name}.json missing — cannot validate")
            continue
        if not loc_path.exists():
            if not values_only:      # nothing to check the values OF; that is a structural gap
                errors.append(f"[gui:{mod}] {loc}.json missing")
            continue
        try:
            ref, cur = load(ref_path), load(loc_path)
        except json.JSONDecodeError as exc:
            errors.append(f"[gui:{mod}] JSON error: {exc}")
            continue
        wanted, bases = expected_keys(set(ref), loc, errors)
        missing = wanted - set(cur)
        extra = set(cur) - wanted
        if missing and not values_only:
            errors.append(f"[gui:{mod}] missing {len(missing)} keys e.g. {sorted(missing)[:3]}")
        if extra and not values_only:
            # A warning, not an error: a locale is allowed to carry keys English does not. zh_cn
            # and zh_tw legitimately ship 14 of them (extra death.discarding_world_* lines and
            # modpack-specific support copy), and a key surviving here after en_us dropped it
            # costs a few bytes in the jar, not a broken screen. Missing keys are the failure
            # that matters — those are the ones players see in English.
            warnings.append(f"[gui:{mod}] {len(extra)} key(s) not in {ref_name} "
                            f"e.g. {sorted(extra)[:3]}")
        for key in wanted & set(cur):
            twin = reference_twin(key, ref, bases)
            if twin in ref and placeholders(ref[twin]) != placeholders(cur[key]):
                errors.append(f"[gui:{mod}] '{key}': placeholders "
                              f"{placeholders(cur[key])} != {placeholders(ref[twin])}")
            if isinstance(cur[key], str) and not cur[key].strip():
                errors.append(f"[gui:{mod}] '{key}': empty value")


def validate_data(loc, errors, values_only=False):
    for sub in DATA_SUBTREES:
        ref_root, loc_root = DATA / sub / REF, DATA / sub / loc
        ref_files, loc_files = rel_json_set(ref_root), rel_json_set(loc_root)
        if not values_only:
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
            if not values_only:
                if sub == "ain_localizations" and rel.startswith("pools/") \
                        and flat_text_pool(ref):
                    compare_flat_pool(ref, cur, f"{sub}/{rel}", errors)
                else:
                    compare_shape(ref, cur, f"{sub}/{rel}", errors)
            if any(rel.endswith(f"random_books/{book}.json") for book in TRIGGER_BOOKS):
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


def main(locales, values_only=False):
    overall_ok = True
    for loc in locales:
        errors, warnings = [], []
        validate_gui(loc, errors, warnings, values_only)
        validate_data(loc, errors, values_only)
        if not values_only:
            validate_credit(loc, errors)
        for warn in warnings:
            print(f"⚠️  {loc}: {warn}")
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
    argv = sys.argv[1:]
    values_only = "--values-only" in argv
    argv = [a for a in argv if a != "--values-only"]
    if not argv:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(argv, values_only))
