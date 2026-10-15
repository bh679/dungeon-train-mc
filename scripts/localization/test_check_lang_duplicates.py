#!/usr/bin/env python3
"""Unit tests for check-lang-duplicates.py — the repeated-key guard over lang files and sidecars.

Invokes the script as a CLI against temp fixtures, plus one run against the real repo so a
duplicate cannot land in a shipped lang file with CI green.

Run: python3 scripts/localization/test_check_lang_duplicates.py   (or via pytest)
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-lang-duplicates.py")


def run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


def fixture(text):
    fd, path = tempfile.mkstemp(suffix=".json", prefix="lang-dup-test-")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def test_a_repeated_key_fails_and_names_both_lines():
    """The #1938 shape: the new text added above the old one, which kept winning."""
    path = fixture('{\n  "a.tip": "Short.",\n  "b": "B",\n  "a.tip": "Long, stale."\n}\n')
    result = run(path)
    assert result.returncode == 1, result.stdout
    assert "'a.tip' (lines 2, 4)" in result.stdout, result.stdout


def test_a_non_ascii_key_is_located():
    path = fixture('{\n  "名前": "x",\n  "名前": "y"\n}\n')
    result = run(path)
    assert result.returncode == 1, result.stdout
    assert "(lines 2, 3)" in result.stdout, result.stdout


def test_the_same_key_in_two_nested_objects_is_fine():
    """Repeats only count within one object — sidecar entries share field names by design."""
    path = fixture('{"x": {"author": "a"}, "y": {"author": "b"}}')
    result = run(path)
    assert result.returncode == 0, result.stdout


def test_an_unparseable_file_fails():
    result = run(fixture('{"a": "unterminated}'))
    assert result.returncode == 1 and "cannot parse" in result.stdout, result.stdout


def test_the_real_repo_has_no_duplicate_keys():
    result = run()
    assert result.returncode == 0, result.stdout


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
