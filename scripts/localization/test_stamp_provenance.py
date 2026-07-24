#!/usr/bin/env python3
"""Unit tests for stamp-provenance.py — the provenance sync/stamp helper.

Invokes the script as a CLI (matching scripts/modpack/test_check_pins.py) against
isolated temp fixtures. Also locks the canonical on-disk format: one entry per line,
raw UTF-8, trailing newline, idempotent rewrite.

Run: python3 scripts/localization/test_stamp_provenance.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "stamp-provenance.py")

LANG = {"a.key": "Alpha", "b.key": "Beta", "c.key": "Gamma"}
PROV = {
    "a.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
    "b.key": {"author": "老本願", "reviewer": "老本願"},
    "c.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
}


AUTHORS = {
    "Opus 4.8 (Claude)": "ai",
    "New Model (Claude)": "ai",
    "老本願": "human",
    "阿世xAsh": "human",
    "unused": "human",
    "R": "human",
}


def workspace(lang=LANG, prov=PROV, locale="xx_yy", authors=AUTHORS, credits=None):
    ws = tempfile.mkdtemp(prefix="provenance-stamp-test-")
    lang_dir = os.path.join(ws, "lang")
    prov_dir = os.path.join(ws, "prov")
    credits_dir = os.path.join(ws, "credits")
    os.makedirs(lang_dir)
    os.makedirs(prov_dir)
    os.makedirs(credits_dir)
    for slug, data in (credits or {}).items():
        with open(os.path.join(credits_dir, f"{slug}.json"), "w", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    with open(os.path.join(lang_dir, "en_us.json"), "w", encoding="utf-8") as f:
        json.dump(lang, f, ensure_ascii=False)
    with open(os.path.join(lang_dir, f"{locale}.json"), "w", encoding="utf-8") as f:
        json.dump(lang, f, ensure_ascii=False)
    if prov is not None:
        with open(os.path.join(prov_dir, f"{locale}.json"), "w", encoding="utf-8") as f:
            json.dump(prov, f, ensure_ascii=False)
    with open(os.path.join(ws, "authors.json"), "w", encoding="utf-8") as f:
        json.dump(authors, f, ensure_ascii=False)
    return lang_dir, prov_dir


def run(lang_dir, prov_dir, *extra):
    ws = os.path.dirname(lang_dir)
    return subprocess.run(
        [sys.executable, SCRIPT, "--lang-dir", lang_dir, "--provenance-dir", prov_dir,
         "--authors-file", os.path.join(ws, "authors.json"),
         "--credits-dir", os.path.join(ws, "credits"),
         # Into the temp workspace, never the real shipped file.
         "--contributors-file", os.path.join(ws, "translation_contributors.json"), *extra],
        capture_output=True, text=True,
    )


def read_contributors(lang_dir):
    ws = os.path.dirname(lang_dir)
    with open(os.path.join(ws, "translation_contributors.json"), encoding="utf-8") as f:
        return json.load(f)


def read(prov_dir, locale="xx_yy"):
    with open(os.path.join(prov_dir, f"{locale}.json"), encoding="utf-8") as f:
        return json.load(f)


def test_sync_adds_missing_key_unreviewed():
    lang = dict(LANG, **{"d.key": "Delta"})
    lang_dir, prov_dir = workspace(lang=lang)
    proc = run(lang_dir, prov_dir, "--sync", "--author", "Opus 4.8 (Claude)")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir)["d.key"] == {"author": "Opus 4.8 (Claude)", "reviewer": ""}
    assert "added 1" in proc.stdout


def test_sync_removes_orphan():
    prov = dict(PROV, **{"gone.key": {"author": "X", "reviewer": ""}})
    lang_dir, prov_dir = workspace(prov=prov)
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert "gone.key" not in read(prov_dir)
    assert "removed 1" in proc.stdout


def test_sync_reorders_to_lang_order_preserving_entries():
    reordered = {k: PROV[k] for k in ("c.key", "a.key", "b.key")}
    lang_dir, prov_dir = workspace(prov=reordered)
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert list(result) == list(LANG)
    assert result == PROV  # values untouched, only order changed


def test_sync_missing_keys_without_author_fails():
    lang = dict(LANG, **{"d.key": "Delta"})
    lang_dir, prov_dir = workspace(lang=lang)
    proc = run(lang_dir, prov_dir, "--sync")
    assert proc.returncode == 1
    assert "--author" in proc.stderr
    # and the sidecar must be untouched
    assert read(prov_dir) == PROV


def test_sync_creates_sidecar_from_scratch():
    lang_dir, prov_dir = workspace(prov=None)
    proc = run(lang_dir, prov_dir, "--sync", "--author", "Opus 4.8 (Claude)")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert list(result) == list(LANG)
    assert all(e == {"author": "Opus 4.8 (Claude)", "reviewer": ""} for e in result.values())


def test_reviewer_stamp_by_keys():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--reviewer", "阿世xAsh", "--keys", "a.key")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert result["a.key"]["reviewer"] == "阿世xAsh"
    assert result["a.key"]["author"] == "Opus 4.8 (Claude)"  # author untouched
    assert result["c.key"]["reviewer"] == ""


def test_reviewer_stamp_by_prefix():
    lang = {"x.one": "1", "x.two": "2", "y.other": "3"}
    prov = {k: {"author": "A", "reviewer": ""} for k in lang}
    lang_dir, prov_dir = workspace(lang=lang, prov=prov)
    proc = run(lang_dir, prov_dir, "--reviewer", "R", "--prefix", "x.")
    assert proc.returncode == 0, proc.stderr
    result = read(prov_dir)
    assert result["x.one"]["reviewer"] == "R" and result["x.two"]["reviewer"] == "R"
    assert result["y.other"]["reviewer"] == ""


def test_reviewer_stamp_all():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--reviewer", "R", "--all")
    assert proc.returncode == 0, proc.stderr
    assert all(e["reviewer"] == "R" for e in read(prov_dir).values())


def test_stamp_nonexistent_key_fails_without_writing():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--reviewer", "R", "--keys", "nope.key")
    assert proc.returncode == 1
    assert "not in the sidecar" in proc.stderr
    assert read(prov_dir) == PROV


def test_author_restamp_resets_reviewer():
    """A re-translated line invalidates its previous review."""
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--author", "New Model (Claude)", "--keys", "b.key")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir)["b.key"] == {"author": "New Model (Claude)", "reviewer": ""}


def test_author_with_reviewer_sets_both():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir,
               "--author", "阿世xAsh", "--reviewer", "阿世xAsh", "--keys", "a.key")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir)["a.key"] == {"author": "阿世xAsh", "reviewer": "阿世xAsh"}


def test_locale_filter_leaves_others_untouched():
    lang_dir, prov_dir = workspace()
    other = os.path.join(prov_dir, "zz_zz.json")
    with open(os.path.join(lang_dir, "zz_zz.json"), "w", encoding="utf-8") as f:
        json.dump(LANG, f)
    with open(other, "w", encoding="utf-8") as f:
        json.dump(PROV, f, ensure_ascii=False)
    before = open(other, encoding="utf-8").read()
    proc = run(lang_dir, prov_dir, "--locale", "xx_yy", "--reviewer", "R", "--all")
    assert proc.returncode == 0, proc.stderr
    assert open(other, encoding="utf-8").read() == before


def test_selection_without_stamp_is_usage_error():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--all")
    assert proc.returncode == 2


def test_no_operation_is_usage_error():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir)
    assert proc.returncode == 2
    assert "nothing to do" in proc.stderr


def test_unregistered_author_rejected_without_writing():
    """A name not in authors.json must be registered first — nothing written."""
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--author", "Mystery Model", "--all")
    assert proc.returncode == 1
    assert "authors.json" in proc.stderr
    assert read(prov_dir) == PROV


def test_ai_reviewer_rejected():
    """Only a registered human can be stamped as reviewer."""
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--reviewer", "Opus 4.8 (Claude)", "--all")
    assert proc.returncode == 1
    assert "only a human can human-review" in proc.stderr
    assert read(prov_dir) == PROV


def test_exclusive_selection_flags():
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--reviewer", "R", "--all", "--prefix", "x.")
    assert proc.returncode == 2
    assert "mutually exclusive" in proc.stderr


# PROV yields (total=3, ai_authored=2, ai_unreviewed=2) against AUTHORS.
CREDIT = {"locale": "xx_yy", "name": "老本願",
          "url": "https://example.com", "human_reviewed": True}


def credit_path(lang_dir, slug="xx_yy"):
    return os.path.join(os.path.dirname(lang_dir), "credits", f"{slug}.json")


def read_credit(lang_dir, slug="xx_yy"):
    with open(credit_path(lang_dir, slug), encoding="utf-8") as f:
        return json.load(f)


def test_sync_writes_credit_counts():
    lang_dir, prov_dir = workspace(credits={"xx_yy": CREDIT})
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    credit = read_credit(lang_dir)
    assert (credit["total_keys"], credit["ai_authored"], credit["ai_unreviewed"]) == (3, 2, 2)
    # hand-edited fields preserved in order, generated fields appended after them
    assert list(credit) == ["locale", "name", "url", "human_reviewed",
                            "total_keys", "ai_authored", "ai_unreviewed"]
    text = open(credit_path(lang_dir), encoding="utf-8").read()
    assert "老本願" in text and "\\u" not in text
    assert text.endswith("}\n")
    assert "counts refreshed in xx_yy.json" in proc.stdout


def test_credit_counts_idempotent():
    lang_dir, prov_dir = workspace(credits={"xx_yy": CREDIT})
    assert run(lang_dir, prov_dir, "--sync", "--author", "unused").returncode == 0
    first = open(credit_path(lang_dir), "rb").read()
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert "counts refreshed" not in proc.stdout  # unchanged file is not rewritten
    assert open(credit_path(lang_dir), "rb").read() == first


def test_stale_credit_counts_corrected_in_place():
    stale = dict(CREDIT, total_keys=99, ai_authored=98, ai_unreviewed=97)
    lang_dir, prov_dir = workspace(credits={"xx_yy": stale})
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    credit = read_credit(lang_dir)
    assert (credit["total_keys"], credit["ai_authored"], credit["ai_unreviewed"]) == (3, 2, 2)
    assert list(credit) == ["locale", "name", "url", "human_reviewed",
                            "total_keys", "ai_authored", "ai_unreviewed"]


def test_counts_stamped_into_every_matching_credit_file():
    lang_dir, prov_dir = workspace(credits={
        "xx_yy": CREDIT,
        "second-contributor": {"locale": "xx_yy", "name": "Second"},
    })
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    for slug in ("xx_yy", "second-contributor"):
        assert read_credit(lang_dir, slug)["total_keys"] == 3, slug


def test_sync_generates_translation_contributors():
    # 老本願 (human) authored+reviewed b.key in xx_yy; Opus (ai) authored the rest.
    lang_dir, prov_dir = workspace()
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert "regenerated translation_contributors.json" in proc.stdout
    data = read_contributors(lang_dir)
    assert data["contributors"] == [
        {"name": "老本願", "languages": [{"locale": "xx_yy", "contributed": 1, "total": 3}]}
    ]


def test_contributors_pick_up_author_url():
    authors = dict(AUTHORS)
    authors["老本願"] = {"kind": "human", "url": "https://example.com/lao"}
    lang_dir, prov_dir = workspace(authors=authors)
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert read_contributors(lang_dir)["contributors"][0]["url"] == "https://example.com/lao"


def test_contributors_regenerate_idempotent():
    lang_dir, prov_dir = workspace()
    assert run(lang_dir, prov_dir, "--sync", "--author", "unused").returncode == 0
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert "regenerated translation_contributors.json" not in proc.stdout  # unchanged, not rewritten


def test_credit_file_for_other_locale_untouched():
    lang_dir, prov_dir = workspace(credits={"zz_zz": {"locale": "zz_zz", "name": "Someone"}})
    before = open(credit_path(lang_dir, "zz_zz"), "rb").read()
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert open(credit_path(lang_dir, "zz_zz"), "rb").read() == before


def test_sync_without_credit_file_still_writes_sidecar():
    lang_dir, prov_dir = workspace()  # empty credits dir
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    assert read(prov_dir) == PROV
    assert "counts refreshed" not in proc.stdout


def test_reviewer_stamp_refreshes_counts():
    """A review pass empties the unreviewed bucket without touching ai_authored."""
    lang_dir, prov_dir = workspace(credits={"xx_yy": CREDIT})
    proc = run(lang_dir, prov_dir, "--reviewer", "阿世xAsh", "--all")
    assert proc.returncode == 0, proc.stderr
    credit = read_credit(lang_dir)
    assert credit["ai_unreviewed"] == 0
    assert credit["ai_authored"] == 2


def test_output_format_lock():
    """One entry per line, raw UTF-8 (no \\u escapes for CJK), trailing newline,
    and a second identical run must be byte-idempotent."""
    lang_dir, prov_dir = workspace()
    path = os.path.join(prov_dir, "xx_yy.json")
    proc = run(lang_dir, prov_dir, "--sync", "--author", "unused")
    assert proc.returncode == 0, proc.stderr
    text = open(path, encoding="utf-8").read()
    assert "老本願" in text and "\\u" not in text
    assert text.endswith("}\n")
    lines = text.splitlines()
    assert len(lines) == len(LANG) + 2  # { + one per entry + }
    assert lines[1] == '  "a.key": {"author": "Opus 4.8 (Claude)", "reviewer": ""},'
    first = open(path, "rb").read()
    assert run(lang_dir, prov_dir, "--sync", "--author", "unused").returncode == 0
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
