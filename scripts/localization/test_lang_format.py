#!/usr/bin/env python3
"""Unit tests for lang_format.py — the format contract every lang value has to keep.

The rule that earns its own module is that `%%` is an ESCAPE, not an argument. It was written out
twice in this directory and diverged both times in one afternoon, so it is pinned here once.

Run: python3 scripts/localization/test_lang_format.py   (or via pytest)
"""
import sys

import lang_format as lf


def test_argument_tokens_are_found():
    assert lf.placeholders("%s ate %d apples") == ["%d", "%s"]
    assert lf.placeholders("%1$s then %2$s") == ["%1$s", "%2$s"]
    assert lf.placeholders("nothing here") == []


def test_a_doubled_percent_is_not_an_argument():
    """The whole reason this module exists."""
    assert lf.placeholders("Behind %%: the rest") == []
    assert lf.placeholders("%s is behind %%") == ["%s"]


def test_a_translation_may_add_or_drop_a_literal_percent():
    """Six locales rephrased the AI-policy line without a percent sign; that is their call."""
    english = "involve 0%% machine learning"
    assert lf.mismatch("не использует машинное обучение", english) is None
    assert lf.mismatch("involviert 0%% maschinelles Lernen", english) is None


def test_a_dropped_argument_is_a_mismatch():
    # The live 2026-08-30 defect: ru_ru's clear_backups.confirm.both had one %s, English has two.
    why = lf.mismatch("удалит копии из:\n%s", "removes from:\n%s\nand\n%s")
    assert why and "do not match" in why


def test_an_invented_argument_is_a_mismatch():
    assert lf.mismatch("%s and %s", "just %s") is not None


def test_reordering_with_positional_forms_is_allowed():
    """Grammar may move arguments; that is what %1$s is for. The multiset is what must match."""
    assert lf.mismatch("%2$s vor %1$s", "%1$s before %2$s") is None


def test_a_bare_percent_is_unsafe():
    why = lf.unsafe("50% done")
    assert why and "bare" in why
    assert lf.unsafe("50%% done") is None
    assert lf.unsafe("%s done") is None


def test_a_bare_percent_is_reported_ahead_of_an_argument_mismatch():
    """A value that would CRASH is worth saying so about before one that merely reads wrong."""
    why = lf.mismatch("50% done", "%s done")
    assert why and "bare" in why


def test_non_strings_are_tolerated():
    assert lf.placeholders(None) == []
    assert lf.placeholders(7) == []
    assert lf.unsafe(None) is None


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
