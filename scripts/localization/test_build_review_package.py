#!/usr/bin/env python3
"""Unit tests for build-review-package.py — the review-queue builder.

The interesting half is ``source_changed_since_review``: a human-reviewed line whose ENGLISH
was edited afterwards. It is read from the sidecar's ``source_hash`` (the digest of the English
each stamp attested) against the current English, so it is proved here on plain dicts. Git
history is only used to DATE the columns; that walk keeps one regression test.

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

import provenance_io as pio  # noqa: E402

spec = importlib.util.spec_from_file_location("brp", HERE / "build-review-package.py")
brp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brp)

AI = "Opus 5 (Claude)"
HUMAN = "老本願"
AUTHORS = {AI: "ai", HUMAN: "human"}
HELLO = pio.source_hash("Hello")
HELLO_THERE = pio.source_hash("Hello there")


def reviewed(digest: str) -> dict:
    return {"author": HUMAN, "reviewer": HUMAN, "source_hash": digest}


def test_english_edit_after_review_is_flagged():
    assert brp.classify(reviewed(HELLO), AUTHORS, HELLO_THERE) == brp.SOURCE_CHANGED


def test_review_of_current_english_is_clean():
    assert brp.classify(reviewed(HELLO), AUTHORS, HELLO) is None


def test_unknown_recorded_source_is_not_stale():
    """"" means the English was not in the repo at stamp time (siblings) — unknown, not changed."""
    assert brp.classify(reviewed(""), AUTHORS, HELLO) is None


def test_missing_current_english_is_not_stale():
    """A key with no English to compare against (siblings, or a since-deleted key) can only
    ever be a first-review item."""
    assert brp.classify(reviewed(HELLO), AUTHORS, None) is None


def test_unreviewed_ai_line_is_flagged_and_human_line_is_not():
    assert brp.classify({"author": AI, "reviewer": "", "source_hash": HELLO}, AUTHORS,
                        HELLO_THERE) == brp.NEEDS_FIRST
    # A human's own untouched translation is not a machine-translation review item — and an
    # unreviewed line is never "stale", whatever its English did: it was never attested.
    assert brp.classify({"author": HUMAN, "reviewer": "", "source_hash": HELLO}, AUTHORS,
                        HELLO_THERE) is None


def test_legacy_entry_without_source_hash_is_clean():
    assert brp.classify({"author": HUMAN, "reviewer": HUMAN}, AUTHORS, HELLO) is None


# ---- the git dating used for the english_changed_at / reviewed_at columns ----

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


def test_key_removed_and_readded_verbatim_is_not_an_edit():
    """#868 dropped keys that #869 re-added with identical text — not an English change."""
    r = repo()
    lang = r / "en_us.json"
    commit(r, lang, {"a": "Hello", "b": "Other"}, "add", "2026-01-01T00:00:00Z")
    commit(r, lang, {"b": "Other"}, "drop a", "2026-02-01T00:00:00Z")
    commit(r, lang, {"a": "Hello", "b": "Other"}, "re-add a verbatim", "2026-03-01T00:00:00Z")
    en = brp.value_change_times(lang, r)
    assert brp.stamp(en["a"]) == "2026-01-01"


def test_reviewer_change_moves_the_review_date():
    r = repo()
    prov = r / "prov.json"
    commit(r, prov, {"a": {"author": AI, "reviewer": "", "source_hash": HELLO}}, "machine",
           "2026-01-02T00:00:00Z")
    commit(r, prov, {"a": reviewed(HELLO)}, "reviewed", "2026-03-01T00:00:00Z")
    assert brp.stamp(brp.review_stamp_times(prov, r)["a"]) == "2026-03-01"


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
