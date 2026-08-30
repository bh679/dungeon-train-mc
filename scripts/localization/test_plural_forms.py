#!/usr/bin/env python3
"""Unit tests for plural_forms.py — which plural forms of a key each locale should carry.

Runs against the shipped ``plural_rules.json`` rather than a fixture, because the value of this
module is that it agrees with the table the GAME reads (narrative/PluralRules.java). A fixture
would test the arithmetic and let the two drift apart, which is the failure it exists to prevent.

Run: python3 -m pytest scripts/localization/test_plural_forms.py
"""
import plural_forms as pf

#: A plural family (both forms present) plus an ordinary key that merely ends in ".one".
REF = {"stat.days.one", "stat.days.other", "gui.title", "gui.pick.one"}


def test_categories_match_the_families_the_game_uses():
    assert pf.plural_categories("ru_ru") == ("one", "few", "many")
    assert pf.plural_categories("pl_pl") == ("one", "few", "many")
    assert pf.plural_categories("ro_ro") == ("one", "few", "other")
    assert pf.plural_categories("ja_jp") == ("other",)
    assert pf.plural_categories("zh_cn") == ("other",)
    assert pf.plural_categories("ko_kr") == ("other",)
    assert pf.plural_categories("de_de") == ("one", "other")


def test_an_unknown_locale_falls_back_to_the_english_family_without_raising():
    assert pf.plural_categories("xx_yy") == ("one", "other")


def test_only_a_base_carrying_both_forms_counts_as_a_family():
    assert pf.plural_bases(REF) == {"stat.days"}


def test_expected_keys_rewrites_families_and_leaves_everything_else_alone():
    ru, _ = pf.expected_keys(REF, "ru_ru")
    assert ru == {"stat.days.one", "stat.days.few", "stat.days.many",
                  "gui.title", "gui.pick.one"}
    ja, _ = pf.expected_keys(REF, "ja_jp")
    assert ja == {"stat.days.other", "gui.title", "gui.pick.one"}
    de, _ = pf.expected_keys(REF, "de_de")
    assert de == REF


def test_wrong_plural_form_names_the_phantoms_the_editor_offered():
    # The exact shape of the 27 Russian units the relay approved and the repo can never accept.
    assert pf.wrong_plural_form("stat.days.other", "ru_ru", REF)
    assert pf.wrong_plural_form("stat.days.one", "ja_jp", REF)
    # …and does not misfire on forms the language really does use.
    assert not pf.wrong_plural_form("stat.days.many", "ru_ru", REF)
    assert not pf.wrong_plural_form("stat.days.other", "de_de", REF)


def test_a_plain_key_is_never_mistaken_for_a_plural_form():
    """`gui.pick.one` ends in `.one` but has no `.other` twin, so it is just a key."""
    assert not pf.wrong_plural_form("gui.pick.one", "ja_jp", REF)
    assert not pf.wrong_plural_form("gui.title", "ru_ru", REF)


def test_the_real_english_key_set_projects_onto_russian_without_losing_keys():
    """A guard on the live data: ru_ru must end up with more keys than English, never fewer."""
    import json
    from pathlib import Path
    lang = Path(__file__).resolve().parents[2] / "src/main/resources/assets/dungeontrain/lang"
    with open(lang / "en_us.json", encoding="utf-8") as fh:
        english = set(json.load(fh))
    ru, bases = pf.expected_keys(english, "ru_ru")
    assert bases, "en_us.json should contain at least one plural family"
    assert not any(k.endswith(".other") for k in ru if k.rsplit(".", 1)[0] in bases)
    assert len(ru) > len(english)     # one/few/many replaces one/other
