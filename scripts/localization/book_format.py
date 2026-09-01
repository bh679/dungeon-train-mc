#!/usr/bin/env python3
"""What the narrative layer will do to a book's prose — the one place that decides.

The book-side sibling of ``lang_format.py``, and the reason both exist separately: a lang value is
resolved by ``Component.translatable``, which hands it to Minecraft's format parser and its
``%s``/``%1$s`` tokens. Book prose is resolved by ``Component.literal`` (``narrative/BookFactory``)
and never reaches that parser at all. Its figures are ``{braces}``, substituted by
``DeathLoreStore.sub`` — a different syntax, resolved by different code, so it needs its own rule.

Two things are true of every book field and of every translation of one:

* **It must name the same placeholders as the English.** ``{deaths}`` is the number of deaths and
  nothing else; a translation that drops it loses the figure the sentence is built around, and one
  that invents a token the English does not have gets no value to put there. Where it appears, how
  often, and in what order are the translator's business — see `tokens`.
* **Every token must be one the resolver knows.** ``sub`` is a chain of plain ``String.replace``
  calls over the closed set in ``RESOLVED``. A token outside it is not substituted, not reported,
  and not escaped — it reaches the player as the literal text ``{death}``.

Why this earns a module rather than a few lines at each call site: the rule has three callers (the
importer, the repo-wide guard, and — mirrored in Java — the in-game editor's
``TranslationFormatCheck``), and ``lang_format``'s own docstring records what happened the last time
this directory wrote one rule out twice.

The regression that prompted it is real and nearly shipped. In the 2026-08-30 import, five ru_ru
epitaphs came back with ``{deaths_nth}`` replaced by ``{deaths}й`` — an adjective ending glued onto
the CARDINAL, so the epitaph read "2й, кто пал" where it should read "второй, кто пал". Both guards
that landed that day check printf tokens, so both passed it. It was caught only because
``DeathLoreOrdinalTest`` happens to assert on that one file.
"""
import re

#: A figure the narrative layer substitutes: {deaths}, {carriage_nth}, …
PLACEHOLDER = re.compile(r"\{[a-zA-Z0-9_]+\}")

#: Every token ``DeathLoreStore.sub`` resolves, and therefore every token a template may name.
#: Kept in step with that method by hand — it is a chain of literal ``.replace`` calls, so there is
#: nothing to read it from. Adding one there without adding it here makes this reject valid
#: English, loudly and on the same PR, which is the failure mode worth having.
RESOLVED = frozenset({
    "{carriage}", "{friends}", "{books}", "{mobs}", "{met}", "{slain}", "{loot}", "{hearts}",
    "{deaths}", "{deaths_nth}", "{carriage_nth}", "{distance}",
})


def placeholders(value) -> list[str]:
    """A field's placeholder tokens, sorted, repeats and all. Non-strings have none.

    Sorted rather than ordered on purpose: a translation is free to move a figure to wherever its
    grammar wants it — Japanese counts before the noun, Romanian wraps the ordinal in an article —
    so nothing about the sequence is part of the contract. What `mismatch` compares is narrower
    still; see `tokens`.
    """
    if not isinstance(value, str):
        return []
    return sorted(PLACEHOLDER.findall(value))


def tokens(value) -> list[str]:
    """The DISTINCT placeholder tokens in `value`, sorted — what a translation must preserve.

    The count is deliberately not part of the contract, and one live translation is the reason.
    English's ``{deaths} times the dark has taken you, and {deaths} times you boarded again`` names
    the figure twice; ru_ru renders the second as "столько же раз" — "as many times". The number is
    not lost, the sentence simply says it in Russian. Every other locale repeats the token, and both
    readings are correct.

    What must not survive is a token going missing entirely or turning into a different one — which
    is exactly the ru_ru ordinal regression, ``{deaths_nth}`` becoming ``{deaths}й``. Comparing sets
    catches that and leaves the translator their grammar.
    """
    return sorted(set(placeholders(value)))


def unresolved(value) -> list[str]:
    """The tokens in `value` that nothing will substitute, sorted and without repeats.

    Asked of ENGLISH originals rather than of translations: a translation carrying a token the
    English does not have is already caught by `mismatch`, and reporting it twice would name the
    same defect two different ways.
    """
    return [token for token in tokens(value) if token not in RESOLVED]


def mismatch(value, english) -> str | None:
    """Why `value` cannot stand in for `english`, or None.

    The format contract for one translated book field. Mirrors ``lang_format.mismatch``'s
    reason-or-None signature so the two calls in ``import-approved-translations.py`` read alike.

    Distinct tokens, not the multiset — see `tokens` for the translation that decides this.
    """
    got, want = tokens(value), tokens(english)
    if got != want:
        return f"placeholders {got} do not match the English's {want}"
    return None
