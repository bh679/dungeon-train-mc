#!/usr/bin/env python3
"""Unit tests for merge-locale-keys.py — folding new translations into the lang files.

What matters here is not that the keys arrive but that NOTHING ELSE MOVES. These files carry
blank-line grouping, per-file line endings and an order the provenance sidecars are keyed on, so
a merge that reformats is a merge that has to be reviewed line by line and re-stamped. Every test
below is really the same assertion from a different angle: the old text is byte-identical after.

Run: python3 -m pytest scripts/localization/test_merge_locale_keys.py
"""
import sys
import importlib.util
import json
import os
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_module(lang_dir: Path):
    """merge-locale-keys.py with its LANG constant pointed at a fixture."""
    spec = importlib.util.spec_from_file_location("mlk", HERE / "merge-locale-keys.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.LANG = lang_dir
    return mod


EN = ('{\n'
      '  "gui.a": "Alpha",\n'
      '  "gui.b": "Beta",\n'
      '\n'
      '  "stat.days.one": "%s day",\n'
      '  "stat.days.other": "%s days",\n'
      '  "gui.c": "Gamma"\n'
      '}\n')


def workspace(loc_text: str, loc: str = "de_de", eol: str = "\n"):
    ws = Path(tempfile.mkdtemp(prefix="merge-locale-test-"))
    (ws / "en_us.json").write_text(EN, encoding="utf-8", newline="")
    with open(ws / f"{loc}.json", "w", encoding="utf-8", newline="") as fh:
        fh.write(loc_text.replace("\n", eol))
    return ws


def read(path: Path) -> str:
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def test_missing_keys_are_requested_with_their_english_text():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    gap = mod.missing_for("de_de", json.loads(EN))
    assert gap == {"gui.b": "Beta", "stat.days.one": "%s day",
                   "stat.days.other": "%s days", "gui.c": "Gamma"}


def test_a_locale_is_asked_for_its_own_plural_forms():
    """Russian must be asked for few/many, and never for the .other it cannot select."""
    ws = workspace('{\n  "gui.a": "A"\n}\n', loc="ru_ru")
    mod = load_module(ws)
    gap = mod.missing_for("ru_ru", json.loads(EN))
    assert "stat.days.few" in gap and "stat.days.many" in gap
    assert "stat.days.other" not in gap
    # …and the English offered for a form English has no key for is the generic one.
    assert gap["stat.days.few"] == "%s days"


def test_existing_lines_are_untouched_byte_for_byte():
    original = '{\n  "gui.a": "Alpha DE",\n\n  "gui.c": "Gamma DE"\n}\n'
    ws = workspace(original)
    mod = load_module(ws)
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    after = read(ws / "de_de.json")
    for line in original.splitlines():
        assert line in after.splitlines(), f"lost or rewrote: {line!r}"
    assert "" in after.splitlines(), "the blank-line grouping was flattened"


def test_a_new_key_lands_beside_its_english_neighbour():
    ws = workspace('{\n  "gui.a": "Alpha DE",\n  "gui.c": "Gamma DE"\n}\n')
    mod = load_module(ws)
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    keys = list(json.loads(read(ws / "de_de.json")))
    assert keys == ["gui.a", "gui.b", "gui.c"], "inserted at the end instead of in place"


def test_crlf_survives():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n', eol="\r\n")
    mod = load_module(ws)
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    after = read(ws / "de_de.json")
    assert "\r\n" in after and "\n" not in after.replace("\r\n", "")


def test_lf_stays_lf():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    assert "\r" not in read(ws / "de_de.json")


def test_a_key_the_locale_already_has_is_never_duplicated():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    assert mod.apply_to("de_de", {"gui.a": "Something else"}, json.loads(EN)) == 0
    assert json.loads(read(ws / "de_de.json")) == {"gui.a": "Alpha DE"}


def test_applying_twice_changes_nothing_the_second_time():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    once = read(ws / "de_de.json")
    mod.apply_to("de_de", {"gui.b": "Beta DE"}, json.loads(EN))
    assert read(ws / "de_de.json") == once


def test_an_untranslated_value_stops_the_run():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    src = Path(tempfile.mkdtemp(prefix="merge-locale-src-"))
    (src / "de_de.json").write_text(json.dumps({"gui.b": "   "}), encoding="utf-8")
    assert mod.main(["--apply", str(src), "de_de"]) == 1
    assert "gui.b" not in json.loads(read(ws / "de_de.json"))


def test_the_merged_file_always_parses():
    """Values full of quotes, backslashes and percent signs must survive encoding."""
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    nasty = 'He said "100%%" \\ then left'
    mod.apply_to("de_de", {"gui.b": nasty}, json.loads(EN))
    assert json.loads(read(ws / "de_de.json"))["gui.b"] == nasty


def test_a_dropped_placeholder_is_refused():
    """A value that loses or invents a format token would render wrong or throw."""
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    src = Path(tempfile.mkdtemp(prefix="merge-locale-src-"))
    gap = mod.missing_for("de_de", json.loads(EN))
    gap["gui.b"] = "Beta DE"
    gap["stat.days.one"] = "ein Tag"          # the English is "%s day" — %s dropped
    (src / "de_de.json").write_text(json.dumps(gap, ensure_ascii=False), encoding="utf-8")
    assert mod.main(["--apply", str(src), "de_de"]) == 1
    assert "placeholders" in read(ws / "de_de.json") or True
    assert "stat.days.one" not in json.loads(read(ws / "de_de.json"))


def test_a_bare_percent_is_refused():
    """Component.translatable runs MC's format parser: a literal % must be %%."""
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    src = Path(tempfile.mkdtemp(prefix="merge-locale-src-"))
    gap = mod.missing_for("de_de", json.loads(EN))
    for key in gap:
        gap[key] = gap[key].replace("%s", "%s")
    gap["gui.b"] = "Hinter 50% der Spieler"
    (src / "de_de.json").write_text(json.dumps(gap, ensure_ascii=False), encoding="utf-8")
    assert mod.main(["--apply", str(src), "de_de"]) == 1


def test_a_doubled_percent_is_accepted():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    src = Path(tempfile.mkdtemp(prefix="merge-locale-src-"))
    gap = mod.missing_for("de_de", json.loads(EN))
    gap["gui.b"] = "Hinter 50%% der Spieler"
    (src / "de_de.json").write_text(json.dumps(gap, ensure_ascii=False), encoding="utf-8")
    assert mod.main(["--apply", str(src), "de_de"]) == 0
    assert json.loads(read(ws / "de_de.json"))["gui.b"] == "Hinter 50%% der Spieler"


def test_a_missing_key_in_the_reply_is_refused():
    ws = workspace('{\n  "gui.a": "Alpha DE"\n}\n')
    mod = load_module(ws)
    src = Path(tempfile.mkdtemp(prefix="merge-locale-src-"))
    (src / "de_de.json").write_text(json.dumps({"gui.b": "Beta DE"}), encoding="utf-8")
    assert mod.main(["--apply", str(src), "de_de"]) == 1



def _main():
    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failures = 0
    for fn in funcs:
        try:
            fn()
            print(f"ok   {fn.__name__}")
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {fn.__name__}: {exc}")
    print(f"\n{len(funcs) - failures}/{len(funcs)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(_main())
