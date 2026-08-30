#!/usr/bin/env python3
"""Fold newly translated keys into the locale lang files, without touching anything else.

The other half of ``check-lang-parity.py``: that one says which keys a locale is missing, this
one gets them translated and puts them back. Two steps, because the middle one is a person or an
agent per language:

  1. ``merge-locale-keys.py --request <dir>`` writes ``<dir>/<loc>.json`` — every key that locale
     lacks, mapped to its English text. That file IS the brief; nothing else needs explaining.
  2. Whoever translates fills in the values, leaving the keys alone.
  3. ``merge-locale-keys.py --apply <dir>`` folds them in, and prints what each file gained.

The v0.619.0 catch-up wave did this with a script that lived in a scratchpad and was thrown away,
so the 2026-08-30 wave started from nothing again. This one is committed. Letting nineteen agents
edit 1,700-key JSON files directly is the thing being avoided: these files carry blank-line
grouping that a json.dump round trip flattens, zh_cn is CRLF and the rest are LF, and the
provenance sidecars are keyed on order. So every write here is a line inserted next to the key it
belongs beside, and nothing already in the file is rewritten, reordered or reformatted.

Plural families are handled in both directions. A locale is asked for the forms ITS grammar
selects, not English's — Russian gets ``.one``/``.few``/``.many`` where English has
``.one``/``.other`` — and the English text offered as the source for a form English does not have
is that family's ``.other``, which is the generic one.

After applying, stamp the provenance or CI will reject the diff:

    python3 scripts/localization/stamp-provenance.py --sync --author '<who translated>'

Usage:
  merge-locale-keys.py --request <dir> [<loc> ...]
  merge-locale-keys.py --apply   <dir> [<loc> ...]
"""
import argparse
import json
import re
import sys
from pathlib import Path

from plural_forms import PLURAL_SUFFIXES, expected_keys, plural_bases

REPO = Path(__file__).resolve().parents[2]
LANG = REPO / "src/main/resources/assets/dungeontrain/lang"
SOURCE = "en_us"
#: The printf-style tokens MC's format parser recognises, same expression validate-locale.py uses.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")


def placeholders(text: str) -> list[str]:
    """A value's ARGUMENT tokens, sorted.

    `%%` is matched by the pattern but dropped here: it is an escaped literal percent, not an
    argument, and a translation is entitled to contain one where the English does not (or to drop
    one). What must match is the arguments — those are passed positionally by the caller.
    """
    return sorted(t for t in PLACEHOLDER.findall(text) if t != "%%")


def unsafe(text: str) -> str | None:
    """Why `text` would break at render time, or None.

    Both of these have shipped before. `Component.translatable` runs Minecraft's format parser, so
    a LITERAL percent must be doubled or the screen throws TranslatableFormatException — and a
    translator who drops or invents a placeholder produces a string that either loses its argument
    or reads one that was never passed.
    """
    stripped = PLACEHOLDER.sub("", text)
    if "%" in stripped:
        return "a bare '%' — a literal percent must be written '%%'"
    return None


def load(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def locales() -> list[str]:
    return sorted(p.stem for p in LANG.glob("*.json") if p.stem != SOURCE)


def english_for(key: str, english: dict, bases: set[str]) -> str:
    """The English text to translate `key` from, following a plural family to its `.other`."""
    if key in english:
        return english[key]
    base, _, suffix = key.rpartition(".")
    if suffix in PLURAL_SUFFIXES and base in bases:
        return english.get(f"{base}.other", english.get(f"{base}.one", ""))
    return ""


def missing_for(loc: str, english: dict) -> dict[str, str]:
    """`loc`'s missing keys mapped to their English text, in English's own key order."""
    current = set(load(LANG / f"{loc}.json"))
    wanted, bases = expected_keys(set(english), loc)
    gap = wanted - current
    ordered: list[str] = []
    for key in english:                      # English order, families expanded in place
        base, _, suffix = key.rpartition(".")
        family = [f"{base}.{c}" for c in ("one", "two", "few", "many", "other")] \
            if base in bases and suffix in PLURAL_SUFFIXES else [key]
        for member in family:
            if member in gap and member not in ordered:
                ordered.append(member)
    return {key: english_for(key, english, bases) for key in ordered}


def insert_after(lines: list[str], anchor: str | None, entries: list[tuple[str, str]]) -> None:
    """Put `entries` straight after `anchor`'s line, or at the top of the object when None.

    Mutates `lines`. The anchor is the nearest preceding key that this locale already has, so a
    new key lands beside its neighbours rather than in a block at the end — which is what keeps
    the diff readable and the file's existing grouping intact.

    The commas are the fiddly part and the reason this is not a one-liner. JSON has no trailing
    comma, so the last entry in the object is the one line that does not end in one; inserting
    after it has to give it a comma and withhold one from the new last line. Getting this wrong
    produces a file that does not parse, which is why apply_to re-parses before writing.
    """
    at = 0
    if anchor is not None:
        needle = json.dumps(anchor, ensure_ascii=False) + ":"
        found = next((i for i, line in enumerate(lines) if line.strip().startswith(needle)), -1)
        at = found + 1 if found >= 0 else len(lines) - 1
    else:
        at = next((i for i, line in enumerate(lines) if line.strip() == "{"), -1) + 1

    rendered = [f"  {json.dumps(k, ensure_ascii=False)}: {json.dumps(v, ensure_ascii=False)},"
                for k, v in entries]

    # Is there another entry after the insertion point, or does the object end here?
    tail = next((line for line in lines[at:] if line.strip()), "}")
    if tail.strip().startswith("}"):
        rendered[-1] = rendered[-1].rstrip(",")
        for i in range(at - 1, -1, -1):          # the entry we are following now needs a comma
            if lines[i].strip():
                if not lines[i].rstrip().endswith((",", "{")):
                    lines[i] = lines[i].rstrip() + ","
                break
    lines[at:at] = rendered


def apply_to(loc: str, additions: dict[str, str], english: dict) -> int:
    """Insert `additions` into `loc`'s lang file. Returns how many keys were added."""
    path = LANG / f"{loc}.json"
    raw = read_verbatim(path)
    eol = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.replace("\r\n", "\n").split("\n")
    current = set(json.loads(raw))

    new = {k: v for k, v in additions.items() if k not in current}
    if not new:
        return 0

    # Group each run of new keys under the last key BEFORE them that the locale already has, so
    # one insertion carries a whole family or a whole new screen's worth of strings.
    _, bases = expected_keys(set(english), loc)
    order: list[str] = []
    for key in english:
        base, _, suffix = key.rpartition(".")
        if base in bases and suffix in PLURAL_SUFFIXES:
            order.extend(f"{base}.{c}" for c in ("one", "two", "few", "many", "other"))
        else:
            order.append(key)
    seen: list[str] = []
    for key in order:
        if key in seen:
            continue
        seen.append(key)

    groups: dict[str | None, list[tuple[str, str]]] = {}
    anchor: str | None = None
    for key in seen:
        if key in new:
            groups.setdefault(anchor, []).append((key, new[key]))
        elif key in current:
            anchor = key
    # Later anchors first: inserting low in the file cannot shift a line number above it.
    for key in reversed(seen):
        if key in groups:
            insert_after(lines, key, groups.pop(key))
    if None in groups:
        insert_after(lines, None, groups.pop(None))
    assert not groups, f"{loc}: unplaced anchors {sorted(groups)}"

    merged = eol.join(lines)
    parsed = json.loads(merged)              # never write a file that will not parse
    expected = dict(json.loads(raw))
    expected.update(new)
    assert parsed == expected, f"{loc}: merged tree does not match the intended one"
    write_verbatim(path, merged)
    return len(new)


def read_verbatim(path: Path) -> str:
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def write_verbatim(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--request", type=Path, metavar="DIR",
                      help="write one <loc>.json of missing keys -> English text per locale")
    mode.add_argument("--apply", type=Path, metavar="DIR",
                      help="fold the translated <loc>.json files in that dir into the lang files")
    parser.add_argument("locales", nargs="*", help="default: every locale beside en_us")
    args = parser.parse_args(argv)

    english = load(LANG / f"{SOURCE}.json")
    targets = args.locales or locales()

    if args.request:
        args.request.mkdir(parents=True, exist_ok=True)
        for loc in targets:
            gap = missing_for(loc, english)
            out = args.request / f"{loc}.json"
            out.write_text(json.dumps(gap, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"{loc}: {len(gap)} key(s) -> {out}")
        return 0

    total = 0
    for loc in targets:
        src = args.apply / f"{loc}.json"
        if not src.is_file():
            print(f"⚠️  {loc}: no {src.name} in {args.apply} — skipped")
            continue
        additions = load(src)
        blank = sorted(k for k, v in additions.items() if not isinstance(v, str) or not v.strip())
        if blank:
            print(f"❌ {loc}: {len(blank)} key(s) left untranslated e.g. {blank[:3]}")
            return 1
        wanted = missing_for(loc, english)
        if set(additions) != set(wanted):
            lost = sorted(set(wanted) - set(additions))
            gained = sorted(set(additions) - set(wanted))
            print(f"❌ {loc}: key set does not match the request "
                  f"(missing {len(lost)} e.g. {lost[:3]}, unexpected {len(gained)} "
                  f"e.g. {gained[:3]})")
            return 1
        bad = []
        for key, value in additions.items():
            why = unsafe(value)
            if why:
                bad.append(f"{key}: {why}")
            elif placeholders(value) != placeholders(wanted[key]):
                bad.append(f"{key}: placeholders {placeholders(value)} != "
                           f"{placeholders(wanted[key])} in the English")
        if bad:
            print(f"❌ {loc}: {len(bad)} value(s) would break at render time:")
            for line in bad[:10]:
                print(f"    - {line}")
            return 1
        added = apply_to(loc, additions, english)
        total += added
        print(f"✅ {loc}: +{added} key(s)")
    print(f"\n{total} key(s) added. Now run:\n"
          f"  python3 scripts/localization/stamp-provenance.py --sync --author '<who translated>'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
