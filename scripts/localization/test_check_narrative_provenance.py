#!/usr/bin/env python3
"""Unit tests for check-narrative-provenance.py — the per-book provenance lockstep guard.

Invokes the script as a CLI against isolated temp fixtures, plus one check against the real
repo so the shipped narrative sidecars can't silently drift from the book files.

Run: python3 scripts/localization/test_check_narrative_provenance.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-narrative-provenance.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

BOOKS = ["random_books/deathnote", "random_books/quiet_rules", "starting_books/intro"]
PROV = {
    "random_books/deathnote": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
    "random_books/quiet_rules": {"author": "老本願", "reviewer": "老本願"},
    "starting_books/intro": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
}
AUTHORS = {"Opus 4.8 (Claude)": "ai", "老本願": "human", "X": "human"}


def workspace(book_files=None, prov_files=None, authors=AUTHORS):
    """An isolated narratives/provenance/registry workspace.

    book_files: {locale: [book_key, ...]}; prov_files: {locale: dict|raw str}.
    """
    ws = tempfile.mkdtemp(prefix="narrative-check-test-")
    narrative_dir = os.path.join(ws, "narratives")
    prov_dir = os.path.join(ws, "prov")
    os.makedirs(narrative_dir)
    os.makedirs(prov_dir)
    authors_file = os.path.join(ws, "authors.json")
    with open(authors_file, "w", encoding="utf-8") as f:
        json.dump(authors, f, ensure_ascii=False)
    for locale, books in (book_files or {}).items():
        for book in books:
            path = os.path.join(narrative_dir, locale, book + ".json")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"id": os.path.basename(book)}, f, ensure_ascii=False)
    for locale, data in (prov_files or {}).items():
        with open(os.path.join(prov_dir, f"{locale}.json"), "w", encoding="utf-8") as f:
            if isinstance(data, str):
                f.write(data)
            else:
                json.dump(data, f, ensure_ascii=False)
    return narrative_dir, prov_dir, authors_file


def run(narrative_dir, prov_dir, authors_file, *extra):
    return subprocess.run(
        [sys.executable, SCRIPT, "--narrative-dir", narrative_dir,
         "--provenance-dir", prov_dir, "--authors-file", authors_file, *extra],
        capture_output=True, text=True,
    )


def test_aligned_sidecar_passes():
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": PROV})
    proc = run(nd, pd, af)
    assert proc.returncode == 0, proc.stderr
    assert "OK: zh_cn" in proc.stdout


def test_missing_sidecar_fails_with_fix_hint():
    nd, pd, af = workspace({"zh_cn": BOOKS}, {})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "no provenance sidecar" in proc.stderr
    assert "stamp-narrative-provenance.py" in proc.stderr


def test_missing_book_entry_fails():
    partial = {k: PROV[k] for k in list(PROV)[:2]}
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": partial})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "missing from provenance" in proc.stderr


def test_orphan_entry_fails():
    extra = dict(PROV, **{"random_books/ghost": {"author": "X", "reviewer": ""}})
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": extra})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "no longer a book" in proc.stderr


def test_book_order_divergence_fails():
    reordered = {k: PROV[k] for k in reversed(BOOKS)}
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": reordered})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "order diverges" in proc.stderr


def test_orphan_sidecar_fails():
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": PROV, "xx_yy": PROV})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "orphan sidecar" in proc.stderr


def test_unregistered_author_fails():
    bad = dict(PROV, **{"random_books/deathnote": {"author": "Ghost", "reviewer": ""}})
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": bad})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "not in localization/authors.json" in proc.stderr


def test_ai_reviewer_fails():
    bad = dict(PROV, **{"random_books/deathnote":
                        {"author": "Opus 4.8 (Claude)", "reviewer": "Opus 4.8 (Claude)"}})
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": bad})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "only a human can human-review" in proc.stderr


def test_malformed_entry_fails():
    bad = dict(PROV, **{"random_books/deathnote": {"author": "Opus 4.8 (Claude)"}})  # no reviewer
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": bad})
    proc = run(nd, pd, af)
    assert proc.returncode == 1
    assert "missing field" in proc.stderr


def test_unknown_locale_flag_is_usage_error():
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": PROV})
    proc = run(nd, pd, af, "--locale", "nope")
    assert proc.returncode == 2
    assert "unknown locale" in proc.stderr


def test_report_runs():
    nd, pd, af = workspace({"zh_cn": BOOKS}, {"zh_cn": PROV})
    proc = run(nd, pd, af, "--report")
    assert proc.returncode == 0, proc.stderr
    assert "ai-unrev" in proc.stdout and "TOTAL" in proc.stdout


def test_real_repo_narrative_sidecars_are_aligned():
    """The shipped narrative sidecars must stay in lockstep with the book files."""
    proc = subprocess.run(
        [sys.executable, SCRIPT], capture_output=True, text=True, cwd=REPO_ROOT,
    )
    assert proc.returncode == 0, proc.stderr
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
