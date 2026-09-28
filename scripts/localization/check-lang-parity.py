#!/usr/bin/env python3
"""Every locale must carry every key English has. The one check nothing was doing.

A new GUI string lands as one line in ``en_us.json``. Nothing else in the repo changes, nothing
fails, and nineteen languages quietly start showing English on that screen. That is not a
hypothetical: it has now happened twice at scale — 109 keys over three weeks (fixed in v0.619.0,
PR #1047) and 144 keys again by 2026-08-30.

Both times CI was green throughout, because nothing compared anything to English:

* ``validate-locale.py`` measured each locale against the ``es_es`` template — and es_es was
  missing exactly the same keys, so it was comparing a gap against an identical gap;
* ``check-provenance.py`` measures each locale's sidecar against *its own* lang file, which stays
  perfectly consistent while both fall behind.

The second time it also stopped the relay import: players had translated 144 of the drifted keys
from the in-game editor, and the importer had no line to write them to.

So this asks the question directly, and it is the only check here that needs no reference locale
and no template — just English, projected through each language's plural rules, because Russian
is *supposed* to carry ``.few``/``.many`` that English has no word for and Japanese is supposed
not to carry ``.one``.

Extra keys are reported but never fail: zh_cn and zh_tw legitimately ship 14 that English does
not, and a key outliving its English original costs bytes, not a broken screen.

Usage:  check-lang-parity.py [<loc> ...]      (default: every locale beside en_us)
Exit code is non-zero if any locale is missing keys.
"""
import json
import sys
from pathlib import Path

from plural_forms import expected_keys

REPO = Path(__file__).resolve().parents[2]
LANG = REPO / "src/main/resources/assets/dungeontrain/lang"
SOURCE = "en_us"
#: How many missing keys to name before the list stops being useful to read.
SHOWN = 5


def load(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def locales() -> list[str]:
    return sorted(p.stem for p in LANG.glob("*.json") if p.stem != SOURCE)


def check(loc: str, english: set[str]) -> tuple[list[str], list[str]]:
    """Returns ``(missing, extra)`` for one locale, both sorted."""
    path = LANG / f"{loc}.json"
    if not path.is_file():
        return [f"{loc}.json does not exist"], []
    current = set(load(path))
    wanted, _ = expected_keys(english, loc)
    return sorted(wanted - current), sorted(current - wanted)


def main(argv: list[str]) -> int:
    english = set(load(LANG / f"{SOURCE}.json"))
    ok = True
    for loc in argv or locales():
        missing, extra = check(loc, english)
        if extra:
            print(f"⚠️  {loc}: {len(extra)} key(s) not in {SOURCE} e.g. {extra[:SHOWN]}")
        if missing:
            ok = False
            print(f"❌ {loc}: missing {len(missing)} key(s) that {SOURCE}.json has")
            for key in missing[:SHOWN]:
                print(f"    - {key}")
            if len(missing) > SHOWN:
                print(f"    … and {len(missing) - SHOWN} more")
        else:
            print(f"✅ {loc}: carries all {len(english)} English keys")
    if not ok:
        print(f"\nfix: translate the missing keys into each locale's {LANG.name}/<loc>.json, then "
              "python3 scripts/localization/stamp-provenance.py --sync --author '<who translated>'")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
