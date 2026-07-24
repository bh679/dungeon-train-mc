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

sys.path.insert(0, HERE)
from pathlib import Path  # noqa: E402
import provenance_io  # noqa: E402

LANG = {"a.key": "Alpha", "b.key": "Beta", "c.key": "Gamma"}
PROV = {
    "a.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
    "b.key": {"author": "老本願", "reviewer": "老本願"},
    "c.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
}
AUTHORS = {"Opus 4.8 (Claude)": "ai", "老本願": "human", "X": "human", "H": "human"}


def workspace(lang_files=None, prov_files=None, credit_files=None, authors=AUTHORS):
    """An isolated lang/provenance/credits/registry workspace."""
    ws = tempfile.mkdtemp(prefix="provenance-check-test-")
    dirs = {}
    for name in ("lang", "prov", "credits"):
        dirs[name] = os.path.join(ws, name)
        os.makedirs(dirs[name])
    dirs["authors"] = os.path.join(ws, "authors.json")
    with open(dirs["authors"], "w", encoding="utf-8") as f:
        json.dump(authors, f, ensure_ascii=False)
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
    dirs["contributors"] = os.path.join(ws, "translation_contributors.json")
    # Best-effort generate the matching translator-credits file so an otherwise-clean
    # workspace passes the (global) contributors check. Malformed-sidecar workspaces raise
    # here and skip it — those tests fail earlier on the structural error, before the
    # contributors check (which is gated on a clean run) is even reached.
    try:
        built = provenance_io.build_contributors(
            Path(dirs["lang"]), Path(dirs["prov"]),
            provenance_io.load_authors(Path(dirs["authors"])),
            provenance_io.load_author_urls(Path(dirs["authors"])))
        provenance_io.write_contributors(Path(dirs["contributors"]), built)
    except Exception:
        pass
    return dirs


def run(dirs, *extra):
    return subprocess.run(
        [sys.executable, SCRIPT,
         "--lang-dir", dirs["lang"],
         "--provenance-dir", dirs["prov"],
         "--credits-dir", dirs["credits"],
         "--contributors-file", dirs["contributors"],
         "--authors-file", dirs["authors"], *extra],
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
    credits = {"xx_yy": {"locale": "xx_yy", "name": "Human", "human_reviewed": True,
                         "total_keys": 3, "ai_authored": 2, "ai_unreviewed": 2}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" in proc.stderr
    assert "only 1/3 lines" in proc.stderr


def test_flag_true_with_full_coverage_is_silent():
    prov = {k: {"author": "H", "reviewer": "H"} for k in LANG}
    credits = {"xx_yy": {"locale": "xx_yy", "name": "Human", "human_reviewed": True,
                         "total_keys": 3, "ai_authored": 0, "ai_unreviewed": 0}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" not in proc.stderr


def test_flag_false_with_full_coverage_suggests_flipping():
    prov = {k: {"author": "H", "reviewer": "H"} for k in LANG}
    credits = {"xx_yy": {"locale": "xx_yy", "name": "AI", "human_reviewed": False,
                         "total_keys": 3, "ai_authored": 0, "ai_unreviewed": 0}}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov}, credits)
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr
    assert "consider flipping" in proc.stderr


# PROV yields (total=3, ai_authored=2, ai_unreviewed=2) against AUTHORS.
CREDIT_OK = {"locale": "xx_yy", "name": "Human",
             "total_keys": 3, "ai_authored": 2, "ai_unreviewed": 2}


def test_matching_credit_counts_pass():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, {"xx_yy": CREDIT_OK})
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr


def test_drifted_credit_counts_fail_with_fix_hint():
    stale = dict(CREDIT_OK, ai_unreviewed=1)
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, {"xx_yy": stale})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "don't match provenance" in proc.stderr
    assert "xx_yy.json" in proc.stderr
    assert "stamp-provenance.py --sync" in proc.stderr


def test_missing_credit_count_fields_fail():
    bare = {"locale": "xx_yy", "name": "Human"}
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, {"xx_yy": bare})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "missing generated count field(s)" in proc.stderr


def test_non_integer_credit_counts_fail():
    """Floats (and bools) are rejected even when numerically equal."""
    bad = dict(CREDIT_OK, total_keys=3.0)
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, {"xx_yy": bad})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "non-negative integers" in proc.stderr


def test_inconsistent_credit_counts_fail():
    bad = dict(CREDIT_OK, ai_authored=5)  # ai_authored > total_keys
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV}, {"xx_yy": bad})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "inconsistent counts" in proc.stderr


def test_every_matching_credit_file_needs_counts():
    """Two contributor files for one locale — both must carry the generated fields."""
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV},
                     {"xx_yy": CREDIT_OK, "second": {"locale": "xx_yy", "name": "Second"}})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "second.json" in proc.stderr
    assert "missing generated count field(s)" in proc.stderr


def test_credit_for_unrelated_locale_ignored_by_count_check():
    """A credit whose locale has no lang file is outside the count check's scope."""
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV},
                     {"xx_yy": CREDIT_OK, "other": {"locale": "zz_zz", "name": "X"}})
    proc = run(dirs)
    assert proc.returncode == 0, proc.stderr


def test_report_counts():
    """2 of 3 lines are AI-authored and unreviewed -> ai=2, ai-unrev=2, 66.7%."""
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    proc = run(dirs, "--report")
    assert proc.returncode == 0, proc.stderr
    assert "Opus 4.8 (Claude)=2" in proc.stdout
    assert "老本願=1" in proc.stdout
    row = next(l for l in proc.stdout.splitlines() if l.startswith("xx_yy"))
    cols = row.split()
    assert cols[1:4] == ["3", "2", "2"], row  # keys, ai, ai-unrev
    assert cols[4] == "66.7", row             # %ai-unrev
    assert cols[5] == "1", row                # reviewed


def test_ai_reviewed_line_leaves_the_unreviewed_bucket():
    """An AI-authored line WITH a human reviewer must not count as AI-unreviewed."""
    prov = dict(PROV, **{"a.key": {"author": "Opus 4.8 (Claude)", "reviewer": "老本願"}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs, "--report")
    assert proc.returncode == 0, proc.stderr
    row = next(l for l in proc.stdout.splitlines() if l.startswith("xx_yy"))
    cols = row.split()
    assert cols[1:4] == ["3", "2", "1"], row  # still 2 AI-authored, only 1 unreviewed


def test_unregistered_author_fails():
    prov = dict(PROV, **{"a.key": {"author": "Opus 4.9 (Claude)", "reviewer": ""}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "not in localization/authors.json" in proc.stderr


def test_unregistered_reviewer_fails():
    prov = dict(PROV, **{"a.key": {"author": "Opus 4.8 (Claude)", "reviewer": "Nobody"}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "reviewer 'Nobody'" in proc.stderr


def test_ai_reviewer_fails():
    """An AI cannot human-review — reviewer registered as ai is an error."""
    prov = dict(PROV, **{"a.key": {"author": "Opus 4.8 (Claude)",
                                   "reviewer": "Opus 4.8 (Claude)"}})
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": prov})
    proc = run(dirs)
    assert proc.returncode == 1
    assert "only a human can human-review" in proc.stderr


def test_missing_registry_is_environment_error():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    os.remove(dirs["authors"])
    proc = run(dirs)
    assert proc.returncode == 2
    assert "author registry not found" in proc.stderr


def test_bad_registry_kind_is_environment_error():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV},
                     authors={"Opus 4.8 (Claude)": "robot"})
    proc = run(dirs)
    assert proc.returncode == 2
    assert "bad author registry" in proc.stderr


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


def test_contributors_drift_fails():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    # Corrupt the (auto-generated) contributors file so it no longer matches provenance.
    with open(dirs["contributors"], "w", encoding="utf-8") as f:
        json.dump({"contributors": [{"name": "Nobody", "languages": []}]}, f, ensure_ascii=False)
    proc = run(dirs)
    assert proc.returncode == 1
    assert "translation_contributors.json" in proc.stderr and "out of date" in proc.stderr


def test_contributors_missing_fails():
    dirs = workspace({"en_us": LANG, "xx_yy": LANG}, {"xx_yy": PROV})
    os.remove(dirs["contributors"])
    proc = run(dirs)
    assert proc.returncode == 1
    assert "missing generated translator-credits file" in proc.stderr


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
