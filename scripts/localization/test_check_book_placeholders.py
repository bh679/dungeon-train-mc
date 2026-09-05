#!/usr/bin/env python3
"""Unit tests for check-book-placeholders.py — the repo-wide book placeholder guard.

Invokes the script as a CLI against isolated temp fixtures, plus one run against the real repo so
the shipped books cannot silently drift and the baseline cannot silently rot.

Run: python3 scripts/localization/test_check_book_placeholders.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-book-placeholders.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

EN_EPITAPHS = [{"id": "fall", "epitaph": "the {deaths_nth} to fall. perhaps not the last."}]

#: A two-page starting book — the pages are the text either side of the %PAGE% marker.
EN_STARTING = {"id": "welcome", "title": "Welcome",
               "variants": ["Page one.\n%PAGE%\nPage two."]}


def workspace(english=None, translated=None, baseline=None, story=None, starting=None):
    """A miniature two-tree repo: one English death_lore + book, one locale mirroring them."""
    ws = tempfile.mkdtemp(prefix="book-placeholder-test-")
    write(os.path.join(ws, "en", "death_lore", "default.json"),
          EN_EPITAPHS if english is None else english)
    write(os.path.join(ws, "en", "narratives", "random_books", "deathnote.json"),
          {"id": "deathnote", "title": "A Note", "variants": ["one", "two"]})
    write(os.path.join(ws, "locales", "xx_yy", "death_lore", "default.json"),
          EN_EPITAPHS if translated is None else translated)
    write(os.path.join(ws, "locales", "xx_yy", "random_books", "deathnote.json"),
          {"id": "deathnote", "title": "Eine Notiz",
           "variants": ["eins", "zwei"]} if story is None else story)
    write(os.path.join(ws, "en", "narratives", "starting_books", "welcome.json"), EN_STARTING)
    write(os.path.join(ws, "locales", "xx_yy", "starting_books", "welcome.json"),
          EN_STARTING if starting is None else starting)
    if baseline is not None:
        write(os.path.join(ws, "baseline.json"), baseline)
    return ws


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def run(ws, *extra):
    return subprocess.run(
        [sys.executable, SCRIPT,
         "--narrative-dir", os.path.join(ws, "locales"),
         "--english-dir", os.path.join(ws, "en"),
         "--baseline", os.path.join(ws, "baseline.json"), *extra],
        capture_output=True, text=True)


def test_a_faithful_translation_passes():
    proc = run(workspace())
    assert proc.returncode == 0, proc.stdout + proc.stderr


def test_a_dropped_placeholder_fails():
    ws = workspace(translated=[{"id": "fall", "epitaph": "gefallen. vielleicht nicht als letzte."}])
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "xx_yy/death_lore/default#0.epitaph" in proc.stdout
    assert "{deaths_nth}" in proc.stdout


def test_the_russian_ordinal_regression_fails():
    """{deaths_nth} swapped for {deaths} plus a glued ending — the defect this guard exists for."""
    ws = workspace(translated=[{"id": "fall", "epitaph": "{deaths}й, кто пал."}])
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "drops or invents a placeholder" in proc.stdout


def test_an_invented_placeholder_fails():
    ws = workspace(translated=[{"id": "fall", "epitaph": "{deaths_nth} of {mobs} to fall."}])
    assert run(ws).returncode == 1


def test_moving_and_repeating_the_figure_is_allowed():
    """Order is free and so is the count — see book_format.tokens."""
    ws = workspace(translated=[{"id": "fall", "epitaph": "{deaths_nth}。{deaths_nth}人目。"}])
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout


def test_a_structural_field_is_not_measured():
    """`id` is load-bearing, never translated, and never prose — pio.book_string_fields skips it."""
    ws = workspace(translated=[{"id": "{deaths}", "epitaph": EN_EPITAPHS[0]["epitaph"]}])
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout


def test_english_naming_a_token_nothing_resolves_fails():
    ws = workspace(english=[{"id": "fall", "epitaph": "the {death} toll."}],
                   translated=[{"id": "fall", "epitaph": "der {death} Zoll."}])
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "reaches the player as literal text" in proc.stdout


def test_a_locale_only_book_is_skipped_not_failed():
    """No English original means nothing to measure against, not a failure."""
    ws = workspace()
    write(os.path.join(ws, "locales", "xx_yy", "stories", "local_only.json"),
          {"id": "local", "title": "{deaths} eigene"})
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout


def test_a_baselined_divergence_is_tolerated():
    ws = workspace(translated=[{"id": "fall", "epitaph": "als {deaths}. gefallen."}],
                   baseline={"xx_yy/death_lore/default#0.epitaph": {
                       "english": ["{deaths_nth}"], "translated": ["{deaths}"]}})
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout


def test_a_baseline_entry_excuses_only_that_divergence():
    """The point of storing the token lists: the entry is not a permanent pass for the field."""
    ws = workspace(translated=[{"id": "fall", "epitaph": "als {mobs}. gefallen."}],
                   baseline={"xx_yy/death_lore/default#0.epitaph": {
                       "english": ["{deaths_nth}"], "translated": ["{deaths}"]}})
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "does not match its baseline entry" in proc.stdout


def test_a_stale_baseline_entry_fails_so_the_list_shrinks():
    ws = workspace(baseline={"xx_yy/death_lore/default#0.epitaph": {
        "english": ["{deaths_nth}"], "translated": ["{deaths}"]}})
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "stale" in proc.stdout


def test_write_baseline_makes_the_run_green():
    ws = workspace(translated=[{"id": "fall", "epitaph": "als {deaths}. gefallen."}])
    assert run(ws).returncode == 1
    assert run(ws, "--write-baseline").returncode == 0
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout


def test_a_translation_that_drops_a_page_break_fails():
    """A merged page is invisible to every other guard: the book still loads and still reads."""
    ws = workspace(starting={"id": "welcome", "title": "Willkommen",
                             "variants": ["Seite eins.\nSeite zwei."]})
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "xx_yy/starting_books/welcome#variants.0" in proc.stdout
    assert "%PAGE%" in proc.stdout


def test_a_translation_that_invents_a_page_break_fails():
    ws = workspace(starting={"id": "welcome", "title": "Willkommen",
                             "variants": ["Seite eins.\n%PAGE%\nSeite zwei.\n%PAGE%\nUnd drei."]})
    proc = run(ws)
    assert proc.returncode == 1, proc.stdout
    assert "2 %PAGE% marker(s), English has 1" in proc.stdout


def test_page_break_parity_ignores_blank_lines_and_flow_books():
    """Newlines are the translator's to place — only the marker count is the contract."""
    ws = workspace(starting={"id": "welcome", "title": "Willkommen",
                             "variants": ["Seite eins.\n\n\nnoch text\n%PAGE%\nSeite zwei."]},
                   story={"id": "deathnote", "title": "Eine Notiz",
                          "variants": ["eins\n\n\nmehr", "zwei"]})
    proc = run(ws)
    assert proc.returncode == 0, proc.stdout + proc.stderr


def test_the_real_repo_is_clean():
    """The shipped books, against the shipped baseline. Fails on new drift AND on a stale entry."""
    proc = subprocess.run([sys.executable, SCRIPT], capture_output=True, text=True, cwd=REPO_ROOT)
    assert proc.returncode == 0, proc.stdout + proc.stderr


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
