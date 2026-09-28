#!/usr/bin/env python3
"""Which plural forms of a key a given locale is supposed to carry.

A count-dependent string is not one key but a FAMILY of sibling keys — ``<base>.one``,
``<base>.few``, ``<base>.other`` — and each language carries exactly the forms its own grammar
can reach. Russian must have ``.few``/``.many`` that English has no word for; Japanese must NOT
carry the ``.one`` English needs, because Japanese plural rules can never select it.

So a locale's key set is *derived* from the English one rather than matched to it, and "this key
is missing" and "this key does not belong here" are both questions only this module can answer.
Two callers need it and used to be one: ``validate-locale.py`` (which keys a locale must have)
and ``import-approved-translations.py`` (whether an approved unit has anywhere to land). The
importer needs it because the in-game editor offers translators the raw English key set, so
Russian players were shown — and translated, and had approved — 27 ``.other`` keys that Russian
never uses. Those approvals can never be imported; saying so precisely is the difference between
a deferred unit and a failed run.

``assets/dungeontrain/plural_rules.json`` is the shared source of truth, read at runtime by
``narrative/PluralRules.java`` as well, so the game and the tooling cannot drift apart.
"""
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PLURAL_RULES = REPO / "src/main/resources/assets/dungeontrain/plural_rules.json"

#: Rule family -> the categories a locale in that family can produce. Shared verbatim with
#: PluralRules.java's `categoriesOf`.
FAMILY_CATEGORIES: dict[str, tuple[str, ...]] = {
    "one_other": ("one", "other"),
    "zero_one_other": ("one", "other"),
    "east_slavic": ("one", "few", "many"),
    "polish": ("one", "few", "many"),
    "romanian": ("one", "few", "other"),
    "single": ("other",),
}
#: Every suffix that can name a plural form, including ones no family here selects — a key ending
#: in one of these is a candidate family member; whether it belongs is FAMILY_CATEGORIES' call.
PLURAL_SUFFIXES = frozenset({"one", "two", "few", "many", "other"})

DEFAULT_FAMILY = "one_other"


def plural_categories(loc: str, errors: list[str] | None = None,
                      rules_path: Path = PLURAL_RULES) -> tuple[str, ...]:
    """The plural categories `loc`'s rules can produce, e.g. ("one", "few", "many") for ru_ru.

    Falls back to the English family on an unreadable or unknown rule table, appending to
    `errors` when the caller passed one. A fallback is deliberately not fatal here: the caller
    decides whether a locale it cannot classify is a validation failure or merely a unit it
    declines to judge.
    """
    try:
        with open(rules_path, encoding="utf-8") as fh:
            table = json.load(fh)["locales"]
    except (OSError, json.JSONDecodeError, KeyError) as exc:
        if errors is not None:
            errors.append(f"[plural] cannot read {rules_path.name}: {exc}")
        return FAMILY_CATEGORIES[DEFAULT_FAMILY]
    family = table.get(loc[:2].lower(), DEFAULT_FAMILY)
    if family not in FAMILY_CATEGORIES:
        if errors is not None:
            errors.append(f"[plural] {loc}: unknown rule family '{family}'")
        return FAMILY_CATEGORIES[DEFAULT_FAMILY]
    return FAMILY_CATEGORIES[family]


def plural_bases(keys) -> set[str]:
    """Bases in `keys` that form a plural family — a base carrying BOTH `.one` and `.other`.

    Requiring both is what stops an ordinary key that happens to end in ``.one`` from being
    mistaken for a family and rewritten out of existence.
    """
    return {k.rsplit(".", 1)[0] for k in keys if k.endswith(".one")
            and k.rsplit(".", 1)[0] + ".other" in keys}


def expected_keys(ref_keys, loc: str, errors: list[str] | None = None,
                  rules_path: Path = PLURAL_RULES) -> tuple[set[str], set[str]]:
    """The reference key set rewritten into the forms `loc` is required to carry.

    Returns ``(keys, bases)`` — the bases come back because a caller checking placeholders needs
    to know which keys are plural forms in order to find their English twin.
    """
    bases = plural_bases(ref_keys)
    cats = plural_categories(loc, errors, rules_path)
    out: set[str] = set()
    for key in ref_keys:
        base, _, suffix = key.rpartition(".")
        if base in bases and suffix in PLURAL_SUFFIXES:
            out.update(f"{base}.{c}" for c in cats)
        else:
            out.add(key)
    return out, bases


def wrong_plural_form(key: str, loc: str, ref_keys, rules_path: Path = PLURAL_RULES) -> bool:
    """True when `key` is a plural form of a family `loc`'s grammar can never select.

    The narrow question the importer asks about a key it cannot find in a locale file: is this
    absent because the locale has fallen behind English (drift, fixable), or because the key has
    no business existing in this language at all (a phantom the editor should never have offered)?
    """
    base, _, suffix = key.rpartition(".")
    if suffix not in PLURAL_SUFFIXES or base not in plural_bases(ref_keys):
        return False
    return suffix not in plural_categories(loc, None, rules_path)
