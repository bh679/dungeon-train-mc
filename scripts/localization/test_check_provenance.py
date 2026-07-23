#!/usr/bin/env python3
"""Unit tests for check-provenance.py — the per-line provenance lockstep guard.

Invokes the script as a CLI (matching scripts/modpack/test_check_pins.py) against
isolated temp fixtures, plus one check against the real repo so the shipped sidecars
can't silently drift from the lang files.

Run: python3 scripts/localization/test_check_provenance.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-provenance.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

LANG = {"a.key": "Alpha", "b.key": "Beta", "c.key": "Gamma"}
PROV = {
    "a.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
    "b.key": {"author": "老本願", "reviewer": "老本願"},
    "c.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
}


def workspace(lang_files=None, prov_files=None, credit_files=None):
    """An isolated lang/provenance/credits directory triple."""
    ws = tempfile.mkdtemp(prefix="provenance-check-test-")
    dirs = {}
    for name in ("lang", "prov", "credits"):
        dirs[name] = os.path.join(ws, name)
        os.makedirs(dirs[name])
    for locale, data in (lang_files or {}).items():
        with open(os.path.join(dirs["lang"], f"{locale}.json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
    for locale, data in (prov_files or {}).items():
        with open(os.path.join(dirs["prov"], f"{locale}.json"), "w", encoding="utf-8") as f:
            if isinstance(data, str):
                f.write(data)  # raw text, for malformed-JSON cases
            else:
                json.dump(data, f, ensure_ascii=False)
    for slug, data in (credit_files or {}).items():
        with open(os.path.join(dirs["credits"], f"{slug}.json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
    return dirs


def run(dirs, *extra):
    return subprocess.run(
        [sys.executable, SCRIPT,
         "--lang-dir", dirs["lang"],
         "--provenance-dir", dirs["prov"],
         "--credits-dir", dirs["credits"], *extra],
        capture_output=True, text=True,
    )


def test_aligned_sidecar_passes():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "OK: xx_yy" in proc.stdout


def test_missing_sidecar_fails_with_fix_hint():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "no provenance sidecar" in proc.stderr
    assert "stamp-provenance.py --sync" in proc.stderr


def test_orphan_sidecar_fails():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV, "zz_zz": PROV})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "orphan sidecar" in proc.stderr


def test_en_us_sidecar_rejected():
    """The source language is exempt — a sidecar for it is an explicit error."""
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV, "en_us": PROV})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "source language" in proc.stderr


def test_missing_key_fails():
    prov = {k: v for k, v in PROV.items() if k != "b.key"}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "missing from provenance" in proc.stderr
    assert "b.key" in proc.stderr


def test_orphaned_key_fails():
    prov = dict(PROV, **{"gone.key": {"author": "X", "reviewer": ""}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "no longer in the lang file" in proc.stderr


def test_order_mismatch_fails_naming_divergence():
    """Same key set, different order — must fail and name the first divergent key."""
    reordered = {k: PROV[k] for k in ("b.key", "a.key", "c.key")}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": reordered})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "key order diverges" in proc.stderr
    assert "index 0" in proc.stderr


def test_empty_author_fails():
    prov = dict(PROV, **{"a.key": {"author": "  ", "reviewer": ""}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "author must be non-empty" in proc.stderr


def test_missing_reviewer_field_fails():
    prov = dict(PROV, **{"a.key": {"author": "X"}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "missing field(s) reviewer" in proc.stderr


def test_unknown_field_fails():
    prov = dict(PROV, **{"a.key": {"author": "X", "reviewer": "", "note": "hi"}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "unknown field(s) note" in proc.stderr


def test_non_object_entry_fails():
    prov = dict(PROV, **{"a.key": "just a string"})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "must be an object" in proc.stderr


def test_non_string_reviewer_fails():
    prov = dict(PROV, **{"a.key": {"author": "X", "reviewer": None}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "reviewer must be a string" in proc.stderr


def test_malformed_json_fails():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": "{not json"})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "unparseable" in proc.stderr


def test_flag_true_with_partial_coverage_warns_but_passes():
    """The zh_cn regression lock: human_reviewed=true + <100% lines must WARN, never fail.

    zh_cn legitimately ships the locale-level flag from the #770 review while the
    Opus-added lines after it stay unreviewed; failing here would train everyone to
    ignore the guard.
    """
    credits = {"xx_yy": {"locale": "xx_yy", "name": "Human", "human_reviewed": True}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" in proc.stderr
    assert "only 1/3 lines" in proc.stderr


def test_flag_true_with_full_coverage_is_silent():
    prov = {k: {"author": "H", "reviewer": "H"} for k in LANG}
    credits = {"xx_yy": {"locale": "xx_yy", "name": "Human", "human_reviewed": True}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" not in proc.stderr


def test_flag_false_with_full_coverage_suggests_flipping():
    prov = {k: {"author": "H", "reviewer": "H"} for k in LANG}
    credits = {"xx_yy": {"locale": "xx_yy", "name": "AI", "human_reviewed": False}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "consider flipping" in proc.stderr


def test_report_counts():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    proc = run(dirs, "--report")
    assert proc.returncode == 0, proc.stderr
    assert "Opus 4.8 (Claude)=2" in proc.stdout
    assert "老本願=1" in proc.stdout
    row = next(l for l in proc.stdout.splitlines() if l.startswith("xx_yy"))
    assert " 3 " in f" {row} " or "    3" in row  # 3 keys
    assert "33.3" in row  # 1/3 reviewed


def test_unknown_locale_flag_is_usage_error():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    proc = run(dirs, "--locale", "nope")
    assert proc.returncode == 2
    assert "unknown locale" in proc.stderr


def test_real_repo_sidecars_are_aligned():
    """The shipped 19 sidecars must stay in lockstep with the shipped lang files."""
    proc = subprocess.run(
        [sys.executable, SCRIPT], capture_output=True, text=True, cwd=REPO_ROOT,
    )
    assert proc.returncode == 0, proc.stderr
    # exactly the one known advisory (zh_cn flag vs partial coverage) — no errors
    assert "FAILED" not in proc.stderr


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
