#!/usr/bin/env python3
"""Unit tests for book_format.py — the placeholder contract every book field has to keep.

The rule that earns its own module is that book prose is Component.literal and uses {braces}, so
neither printf guard that landed on 2026-08-30 covers it. The regression they both missed is
pinned here by name.

Run: python3 scripts/localization/test_book_format.py   (or via pytest)
"""
import sys

import book_format as bf


def test_placeholder_tokens_are_found():
    assert bf.placeholders("the {deaths_nth} to fall") == ["{deaths_nth}"]
    assert bf.placeholders("carriage {carriage}, {friends} friends") == ["{carriage}", "{friends}"]
    assert bf.placeholders("no figures here") == []


def test_saying_the_figure_once_where_english_says_it_twice_is_allowed():
    """The live ru_ru rendering: "столько же раз" — "as many times" — for the second {deaths}.

    The number is not lost; Russian just says it in Russian. Every other locale repeats the token,
    and both are correct, so the COUNT is not part of the contract.
    """
    english = "{deaths} times the dark took you, and {deaths} times you boarded again."
    assert bf.mismatch("{deaths} раз тьма забирала тебя, и столько же раз ты садился вновь.",
                       english) is None


def test_placeholders_still_reports_the_repeats_it_finds():
    """`placeholders` is the raw reading; `tokens` is the contract. Both are used."""
    assert bf.placeholders("{deaths} and {deaths}") == ["{deaths}", "{deaths}"]
    assert bf.tokens("{deaths} and {deaths}") == ["{deaths}"]


def test_losing_the_figure_entirely_is_still_a_mismatch():
    """The line between this and the case above: once is fine, none is not."""
    assert bf.mismatch("тьма забирала тебя много раз.",
                       "{deaths} times the dark took you, and {deaths} times you boarded.") is not None


def test_prose_braces_that_are_not_tokens_are_ignored():
    """A brace has to look like a placeholder to be one; punctuation is prose."""
    assert bf.placeholders("a { and a }") == []
    assert bf.placeholders("{ deaths }") == []


def test_the_russian_ordinal_regression():
    """The live 2026-08-30 defect: {deaths_nth} replaced by an ending glued onto the CARDINAL.

    "2й, кто пал" where it should read "второй, кто пал" — see book_format's module docstring.
    """
    why = bf.mismatch("{deaths}й, кто пал. и похоже, не последний.",
                      "the {deaths_nth} to fall. perhaps not the last.")
    assert why and "do not match" in why


def test_a_dropped_placeholder_is_a_mismatch():
    why = bf.mismatch("кто пал", "the {deaths_nth} to fall")
    assert why and "{deaths_nth}" in why


def test_an_invented_placeholder_is_a_mismatch():
    assert bf.mismatch("{deaths} of {mobs}", "{deaths} fallen") is not None


def test_reordering_to_suit_the_grammar_is_allowed():
    """Japanese counts before the noun; Romanian wraps the ordinal. The multiset is what must match."""
    assert bf.mismatch("{deaths}人、{mobs}体", "{mobs} slain by the {deaths} fallen") is None


def test_prose_with_no_placeholders_at_all_is_fine():
    """The 57 books that carry no figures — the common case, and it must stay silent."""
    assert bf.mismatch("ein ganz gewöhnlicher Satz.", "an ordinary sentence.") is None


def test_a_token_the_resolver_knows_is_not_unresolved():
    assert bf.unresolved("the {deaths_nth} of {carriage_nth}") == []


def test_a_token_nothing_substitutes_is_reported():
    """DeathLoreStore.sub is a chain of literal .replace calls — {death} reaches the player as-is."""
    assert bf.unresolved("the {death} toll") == ["{death}"]
    assert bf.unresolved("{carraige} and {carriage}") == ["{carraige}"]


def test_unresolved_does_not_repeat_itself():
    assert bf.unresolved("{nope} then {nope}") == ["{nope}"]


def test_non_strings_are_tolerated():
    assert bf.placeholders(None) == []
    assert bf.placeholders(7) == []
    assert bf.unresolved(None) == []


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
