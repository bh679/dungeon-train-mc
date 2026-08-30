#!/usr/bin/env python3
"""What Minecraft's format parser will do to a lang value — the one place that decides.

``Component.translatable`` runs MC's format parser over the string it resolves, which makes two
things true of every lang value and of every translation of one:

* **Its argument tokens must match the English's.** The caller passes arguments positionally, so a
  translation that drops a ``%s`` renders with an argument missing, and one that invents a ``%2$s``
  reads an argument nobody passed.
* **A literal percent must be written ``%%``.** A bare ``%`` is not a valid specifier and throws
  ``TranslatableFormatException``, taking the screen down. ``Component.literal`` did not — which is
  how a string acquires this bug on the day it becomes a lang key. It has shipped: the AI-policy
  screen carried "involve 0% machine learning" until 2026-08-30.

The subtlety that earns this its own module is that **``%%`` is an escape, not an argument**, so it
must be excluded from the comparison. Get that wrong and every locale that rephrases around a
percent sign fails against an English that has one — which is exactly what happened to six locales
in one afternoon, in code that had this rule written out twice and diverged. Three callers need it
now (``validate-locale.py``, ``merge-locale-keys.py``, ``import-approved-translations.py``); a
third copy would have been a third chance to get it wrong.
"""
import re

#: The printf-style tokens MC's format parser recognises: %s %d %1$s %% …
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")


def placeholders(value) -> list[str]:
    """A value's ARGUMENT tokens, sorted. Non-strings have none.

    Sorted rather than ordered on purpose: a translation is free to reorder arguments for its own
    grammar (that is what the positional ``%1$s`` forms are for), so what must match the English is
    the multiset of tokens, not their sequence.

    ``%%`` is matched by the pattern and dropped here — see the module docstring.
    """
    if not isinstance(value, str):
        return []
    return sorted(token for token in PLACEHOLDER.findall(value) if token != "%%")


def unsafe(value) -> str | None:
    """Why `value` would throw when rendered, or None if it is safe.

    Only one way to throw is possible today: a bare ``%``. Everything the parser accepts is matched
    by PLACEHOLDER, so whatever percent survives removing them is one it will choke on.
    """
    if not isinstance(value, str):
        return None
    if "%" in PLACEHOLDER.sub("", value):
        return "a bare '%' — a literal percent must be written '%%'"
    return None


def mismatch(value, english) -> str | None:
    """Why `value` cannot stand in for `english`, or None.

    The whole format contract for one translated string, in the order a reader wants to hear it:
    a value that would crash is worth saying so about before one that merely reads wrong.
    """
    why = unsafe(value)
    if why:
        return why
    got, want = placeholders(value), placeholders(english)
    if got != want:
        return f"format arguments {got} do not match the English's {want}"
    return None
