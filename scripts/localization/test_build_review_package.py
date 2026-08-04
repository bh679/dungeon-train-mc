#!/usr/bin/env python3
"""Unit tests for build-review-package.py — the review-queue builder.

The interesting half is ``source_changed_since_review``: a human-reviewed line whose ENGLISH
was edited afterwards. No such line exists in the repo right now (0 across all 19 locales), so
the detector is proved here against throwaway git repos instead of by observation.

Run: python3 scripts/localization/test_build_review_package.py   (or via pytest)
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

spec = importlib.util.spec_from_file_location("brp", HERE / "build-review-package.py")
brp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brp)

AI = "Opus 5 (Claude)"
AUTHORS = {AI: "ai", "老本願": "human"}


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)


def commit(repo: Path, path: Path, data: dict, message: str, when: str) -> None:
    """Write ``data`` to ``path`` and commit it at a fixed author/committer date."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    git(repo, "add", "-A")
    env = dict(os.environ, GIT_AUTHOR_DATE=when, GIT_COMMITTER_DATE=when)
    subprocess.run(["git", "commit", "-q", "-m", message], cwd=repo, check=True,
                   capture_output=True, env=env)


def repo() -> Path:
    r = Path(tempfile.mkdtemp(prefix="review-package-test-"))
    git(r, "init", "-q")
    git(r, "config", "user.email", "t@example.com")
    git(r, "config", "user.name", "T")
    return r


def test_english_edit_after_review_is_flagged():
    r = repo()
    lang = r / "en_us.json"
    commit(r, lang, {"a": "Hello"}, "add", "2026-01-01T00:00:00Z")
    prov = r / "prov.json"
    commit(r, prov, {"a": {"author": "老本願", "reviewer": "老本願"}}, "review",
           "2026-02-01T00:00:00Z")
    commit(r, lang, {"a": "Hello there"}, "reword the English", "2026-03-01T00:00:00Z")

    en = brp.value_change_times(lang, r)
    reviewed = brp.review_stamp_times(prov, r)
    entry = {"author": "老本願", "reviewer": "老本願"}
    assert brp.classify(entry, AUTHORS, en["a"], reviewed["a"]) == brp.SOURCE_CHANGED, \
        f"en={en} reviewed={reviewed}"


def test_review_after_english_edit_is_clean():
    r = repo()
    lang = r / "en_us.json"
    commit(r, lang, {"a": "Hello"}, "add", "2026-01-01T00:00:00Z")
    commit(r, lang, {"a": "Hello there"}, "reword", "2026-02-01T00:00:00Z")
    prov = r / "prov.json"
    commit(r, prov, {"a": {"author": "老本願", "reviewer": "老本願"}}, "review then",
           "2026-03-01T00:00:00Z")

    en = brp.value_change_times(lang, r)
    reviewed = brp.review_stamp_times(prov, r)
    assert brp.classify({"author": "老本願", "reviewer": "老本願"}, AUTHORS,
                        en["a"], reviewed["a"]) is None


def test_key_removed_and_readded_verbatim_is_not_an_edit():
    """#868 dropped four consent-card keys; #869 re-added them with identical text. That is
    not an English change, and flagging it would send a translator after nothing."""
    r = repo()
    lang = r / "en_us.json"
    commit(r, lang, {"a": "Harvest your soul", "b": "x"}, "add", "2026-01-01T00:00:00Z")
    prov = r / "prov.json"
    commit(r, prov, {"a": {"author": "老本願", "reviewer": "老本願"}}, "review",
           "2026-02-01T00:00:00Z")
    commit(r, lang, {"b": "x"}, "restructure: key gone", "2026-03-01T00:00:00Z")
    commit(r, lang, {"a": "Harvest your soul", "b": "x"}, "key back, same text",
           "2026-04-01T00:00:00Z")

    en = brp.value_change_times(lang, r)
    assert en["a"] < brp.review_stamp_times(prov, r)["a"], "re-add counted as an edit"
    assert brp.classify({"author": "老本願", "reviewer": "老本願"}, AUTHORS,
                        en["a"], brp.review_stamp_times(prov, r)["a"]) is None


def test_same_commit_english_and_review_is_not_stale():
    """A squash merge can carry an English edit and its review together; the review wins."""
    r = repo()
    lang, prov = r / "en_us.json", r / "prov.json"
    lang.write_text(json.dumps({"a": "One"}), encoding="utf-8")
    commit(r, prov, {"a": {"author": "老本願", "reviewer": "老本願"}}, "both at once",
           "2026-01-01T00:00:00Z")
    en = brp.value_change_times(lang, r)
    reviewed = brp.review_stamp_times(prov, r)
    assert en["a"] == reviewed["a"]
    assert brp.classify({"author": "老本願", "reviewer": "老本願"}, AUTHORS,
                        en["a"], reviewed["a"]) is None


def test_unreviewed_ai_line_is_flagged_and_human_line_is_not():
    assert brp.classify({"author": AI, "reviewer": ""}, AUTHORS, None, None) == brp.NEEDS_FIRST
    # A human's own untouched translation is not a machine-translation review item.
    assert brp.classify({"author": "老本願", "reviewer": ""}, AUTHORS, None, None) is None


def test_reviewer_change_resets_the_review_date():
    """Re-reviewing a line moves its review date forward, clearing an earlier staleness."""
    r = repo()
    lang, prov = r / "en_us.json", r / "prov.json"
    commit(r, lang, {"a": "One"}, "add", "2026-01-01T00:00:00Z")
    commit(r, prov, {"a": {"author": AI, "reviewer": ""}}, "machine", "2026-01-02T00:00:00Z")
    commit(r, lang, {"a": "Two"}, "reword", "2026-02-01T00:00:00Z")
    commit(r, prov, {"a": {"author": "老本願", "reviewer": "老本願"}}, "reviewed after",
           "2026-03-01T00:00:00Z")
    en = brp.value_change_times(lang, r)
    reviewed = brp.review_stamp_times(prov, r)
    assert reviewed["a"] > en["a"]
    assert brp.classify({"author": "老本願", "reviewer": "老本願"}, AUTHORS,
                        en["a"], reviewed["a"]) is None


def _main() -> int:
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
