#!/usr/bin/env python3
"""Unit tests for stamp-narrative-provenance.py — the per-book provenance sync/stamp helper.

Invokes the script as a CLI against isolated temp fixtures (books are JSON files under
locale dirs). Locks the same canonical on-disk format as the lang provenance: one entry per
line, raw UTF-8, trailing newline, idempotent rewrite.

Run: python3 scripts/localization/test_stamp_narrative_provenance.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "stamp-narrative-provenance.py")

# Book keys (path relative to the locale dir, sans .json), in sorted order.
BOOKS = ["random_books/deathnote", "random_books/quiet_rules", "starting_books/intro"]
PROV = {
    "random_books/deathnote": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
    "random_books/quiet_rules": {"author": "老本願", "reviewer": "老本願"},
    "starting_books/intro": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
}
AUTHORS = {
    "Opus 4.8 (Claude)": "ai",
    "New Model (Claude)": "ai",
    "老本願": "human",
    "阿世xAsh": "human",
    "unused": "human",
    "R": "human",
}


def workspace(books=BOOKS, prov=PROV, locale="zh_cn", authors=AUTHORS):
    ws = tempfile.mkdtemp(prefix="narrative-stamp-test-")
    narrative_dir = os.path.join(ws, "narratives")
    prov_dir = os.path.join(ws, "prov")
    locale_dir = os.path.join(narrative_dir, locale)
    os.makedirs(locale_dir)
    os.makedirs(prov_dir)
    for book in books:
        path = os.path.join(locale_dir, book + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"id": os.path.basename(book), "variants": ["x"]}, f, ensure_ascii=False)
    if prov is not None:
        with open(os.path.join(prov_dir, f"{locale}.json"), "w", encoding="utf-8") as f:
            json.dump(prov, f, ensure_ascii=False)
    with open(os.path.join(ws, "authors.json"), "w", encoding="utf-8") as f:
        json.dump(authors, f, ensure_ascii=False)
    return narrative_dir, prov_dir


def run(narrative_dir, prov_dir, *extra):
    ws = os.path.dirname(narrative_dir)
    return subprocess.run(
        [sys.executable, SCRIPT, "--narrative-dir", narrative_dir, "--provenance-dir", prov_dir,
         "--authors-file", os.path.join(ws, "authors.json"), *extra],
        capture_output=True, text=True,
    )


def read(prov_dir, locale="zh_cn"):
    with open(os.path.join(prov_dir, f"{locale}.json"), encoding="utf-8") as f:
        return json.load(f)


def test_sync_adds_missing_book_unreviewed():
    books = BOOKS + ["stories/new_tale"]
    narrative_dir, prov_dir = workspace(books=books)
    proc = run(narrative_dir, prov_dir, "--sync", "--author", "Opus 4.8 (Claude)")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir)["stories/new_tale"] == {"author": "Opus 4.8 (Claude)", "reviewer": ""}
    assert "added 1" in proc.stdout


def test_sync_removes_orphan():
    prov = dict(PROV, **{"random_books/gone": {"author": "unused", "reviewer": ""}})
    narrative_dir, prov_dir = workspace(prov=prov)
    proc = run(narrative_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert "random_books/gone" not in read(prov_dir)
    assert "removed 1" in proc.stdout


def test_sync_reorders_to_book_order_preserving_entries():
    reordered = {k: PROV[k] for k in reversed(BOOKS)}
    narrative_dir, prov_dir = workspace(prov=reordered)
    proc = run(narrative_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert list(result) == sorted(BOOKS)
    assert result == PROV  # values untouched, only order changed


def test_sync_missing_books_without_author_fails():
    books = BOOKS + ["stories/new_tale"]
    narrative_dir, prov_dir = workspace(books=books)
    proc = run(narrative_dir, prov_dir, "--sync")
    assert proc.returncode == 1
    assert "--author" in proc.stderr
    assert read(prov_dir) == PROV  # untouched


def test_sync_creates_sidecar_from_scratch():
    narrative_dir, prov_dir = workspace(prov=None)
    proc = run(narrative_dir, prov_dir, "--sync", "--author", "Opus 4.8 (Claude)")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert list(result) == sorted(BOOKS)
    assert all(e == {"author": "Opus 4.8 (Claude)", "reviewer": ""} for e in result.values())


def test_reviewer_stamp_by_files():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--reviewer", "阿世xAsh",
               "--files", "random_books/deathnote")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert result["random_books/deathnote"]["reviewer"] == "阿世xAsh"
    assert result["random_books/deathnote"]["author"] == "Opus 4.8 (Claude)"  # untouched
    assert result["starting_books/intro"]["reviewer"] == ""


def test_reviewer_stamp_by_prefix():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--reviewer", "R", "--prefix", "random_books/")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert result["random_books/deathnote"]["reviewer"] == "R"
    assert result["random_books/quiet_rules"]["reviewer"] == "R"
    assert result["starting_books/intro"]["reviewer"] == ""


def test_author_restamp_resets_reviewer():
    """A re-translated book invalidates its previous review."""
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--author", "New Model (Claude)",
               "--files", "random_books/quiet_rules")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir)["random_books/quiet_rules"] == {
        "author": "New Model (Claude)", "reviewer": ""}


def test_stamp_nonexistent_book_fails_without_writing():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--reviewer", "R", "--files", "random_books/nope")
    assert proc.returncode == 1
    assert "not in the sidecar" in proc.stderr
    assert read(prov_dir) == PROV


def test_ai_reviewer_rejected():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--reviewer", "Opus 4.8 (Claude)", "--all")
    assert proc.returncode == 1
    assert "only a human can human-review" in proc.stderr
    assert read(prov_dir) == PROV


def test_unregistered_author_rejected_without_writing():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir, "--author", "Mystery Model", "--all")
    assert proc.returncode == 1
    assert "authors.json" in proc.stderr
    assert read(prov_dir) == PROV


def test_locale_filter_leaves_others_untouched():
    narrative_dir, prov_dir = workspace()
    # a second locale dir + sidecar
    other_locale_dir = os.path.join(narrative_dir, "ja_jp", "random_books")
    os.makedirs(other_locale_dir)
    with open(os.path.join(other_locale_dir, "deathnote.json"), "w", encoding="utf-8") as f:
        json.dump({"id": "deathnote"}, f)
    other = os.path.join(prov_dir, "ja_jp.json")
    with open(other, "w", encoding="utf-8") as f:
        json.dump({"random_books/deathnote": {"author": "unused", "reviewer": ""}}, f,
                  ensure_ascii=False)
    before = open(other, encoding="utf-8").read()
    proc = run(narrative_dir, prov_dir, "--locale", "zh_cn", "--reviewer", "R", "--all")
    assert proc.returncode == 0, proc.stderr
    assert open(other, encoding="utf-8").read() == before


def test_no_operation_is_usage_error():
    narrative_dir, prov_dir = workspace()
    proc = run(narrative_dir, prov_dir)
    assert proc.returncode == 2
    assert "nothing to do" in proc.stderr


def test_output_format_lock():
    """One entry per line, raw UTF-8, trailing newline, byte-idempotent second run."""
    narrative_dir, prov_dir = workspace()
    path = os.path.join(prov_dir, "zh_cn.json")
    proc = run(narrative_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    text = open(path, encoding="utf-8").read()
    assert "老本願" in text and "\\u" not in text
    assert text.endswith("}\n")
    lines = text.splitlines()
    assert len(lines) == len(BOOKS) + 2  # { + one per entry + }
    first = open(path, "rb").read()
    assert run(narrative_dir, prov_dir, "--sync", "--author", "unused").returncode == 0
    assert open(path, "rb").read() == first


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
