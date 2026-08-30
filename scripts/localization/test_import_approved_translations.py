#!/usr/bin/env python3
"""Unit tests for import-approved-translations.py — the relay return leg.

Drives the script as a CLI against isolated temp fixtures (matching test_stamp_provenance.py),
feeding it saved payloads through --from-file so nothing here touches the network. The stamping
itself is stamp-provenance.py's, already covered by its own tests; what is proved here is the
part this script owns — what gets written, what gets refused, and who ends up credited.

Run: python3 scripts/localization/test_import_approved_translations.py   (or via pytest)
"""
import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "import-approved-translations.py")

EN = {"a.key": "Alpha", "b.key": "Beta", "c.key": "Gamma"}
XX = {"a.key": "AI Alpha", "b.key": "AI Beta", "c.key": "AI Gamma"}
PROV = {
    "a.key": {"author": "Opus 5 (Claude)", "reviewer": ""},
    "b.key": {"author": "Opus 5 (Claude)", "reviewer": ""},
    "c.key": {"author": "Opus 5 (Claude)", "reviewer": ""},
}
AUTHORS = {"Opus 5 (Claude)": "ai", "老本願": "human"}

BOOK = {
    "id": "deathnote",
    "title": "AI Title",
    "variants": ["AI one", "AI two"],
}

#: The English original the fixture book translates. Placeholder-free, like most of the 60 real
#: books, so the ordinary book tests are about provenance and text-level edits and nothing else.
#: The tests that are about placeholders pass their own via ``workspace(book_en=...)``.
BOOK_EN = {
    "id": "deathnote",
    "title": "Title",
    "variants": ["one", "two"],
}

#: An English original that names a figure. Book prose is Component.literal and writes its figures
#: as {deaths}, so lang_format's printf rule cannot see them at all — see book_format.
BOOK_EN_WITH_FIGURE = {
    "id": "deathnote",
    "title": "Title",
    "variants": ["the {deaths_nth} to fall", "two"],
}


def unit(**over):
    """One approved row in the shape the relay's admin listing returns."""
    row = {"id": 1, "translator": "老本願", "locale": "xx_yy", "unitType": "lang",
           "namespace": "dungeontrain", "unitId": "a.key", "source": "Alpha",
           "value": "人工 Alpha", "flag": "approved"}
    row.update(over)
    return row


def workspace(lang=XX, prov=PROV, authors=AUTHORS, book=BOOK, book_en=BOOK_EN):
    """A miniature repo: one locale's lang file + sidecar, one book + its English, a registry."""
    ws = tempfile.mkdtemp(prefix="import-approved-test-")
    lang_dir = os.path.join(ws, "lang")
    prov_dir = os.path.join(ws, "prov")
    nar_prov_dir = os.path.join(ws, "narrative-prov")
    nar_dir = os.path.join(ws, "narrative", "xx_yy", "random_books")
    nar_en_dir = os.path.join(ws, "narrative-en", "narratives", "random_books")
    for d in (lang_dir, prov_dir, nar_prov_dir, nar_dir, nar_en_dir):
        os.makedirs(d)
    write_json(os.path.join(lang_dir, "en_us.json"), EN)
    write_json(os.path.join(lang_dir, "xx_yy.json"), lang)
    write_json(os.path.join(ws, "authors.json"), authors)
    write_json(os.path.join(nar_dir, "deathnote.json"), book)
    if book_en is not None:
        write_json(os.path.join(nar_en_dir, "deathnote.json"), book_en)
    write_provenance(os.path.join(prov_dir, "xx_yy.json"), prov)
    write_provenance(os.path.join(nar_prov_dir, "xx_yy.json"),
                     {"random_books/deathnote": {"author": "Opus 5 (Claude)", "reviewer": ""}})
    return ws


def write_provenance(path, prov):
    """The canonical one-entry-per-line sidecar format (provenance_io.write_provenance)."""
    lines = ["{"] + [
        f'  {json.dumps(k, ensure_ascii=False)}: '
        f'{json.dumps(v, ensure_ascii=False, separators=(", ", ": "))}'
        + ("," if i < len(prov) - 1 else "")
        for i, (k, v) in enumerate(prov.items())
    ] + ["}"]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def run(ws, rows, *extra):
    """Invoke the importer against ``ws``, pointing every default at the fixture."""
    payload = os.path.join(ws, "payload.json")
    write_json(payload, rows)
    # Every dir is redirected into the fixture — the same single-namespace escape hatch
    # test_stamp_provenance.py uses, so a test run can never touch the real assets tree.
    return subprocess.run(
        [sys.executable, SCRIPT, "--from-file", payload,
         "--authors-file", os.path.join(ws, "authors.json"),
         "--lang-dir", os.path.join(ws, "lang"),
         "--provenance-dir", os.path.join(ws, "prov"),
         "--narrative-dir", os.path.join(ws, "narrative"),
         "--narrative-en-dir", os.path.join(ws, "narrative-en"),
         "--narrative-provenance-dir", os.path.join(ws, "narrative-prov"), *extra],
        capture_output=True, text=True)


def prov_of(ws):
    return read_json(os.path.join(ws, "prov", "xx_yy.json"))


def lang_of(ws):
    return read_json(os.path.join(ws, "lang", "xx_yy.json"))


def test_revised_line_credits_the_translator_as_author_and_reviewer():
    ws = workspace()
    proc = run(ws, [unit()])
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["a.key"] == "人工 Alpha"
    assert prov_of(ws)["a.key"] == {"author": "老本願", "reviewer": "老本願"}
    # untouched keys keep their machine attribution
    assert prov_of(ws)["b.key"]["author"] == "Opus 5 (Claude)"


def test_matching_line_is_reviewed_not_reauthored():
    ws = workspace()
    proc = run(ws, [unit(value="AI Alpha")])
    assert proc.returncode == 0, proc.stderr
    assert prov_of(ws)["a.key"] == {"author": "Opus 5 (Claude)", "reviewer": "老本願"}


# ---- two people, one line -----------------------------------------------------
#
# The relay lets two translators both hold an approved unit for the same key (38 keys did in the
# 2026-08-30 import). Only one text can ship and the sidecar has one author and one reviewer, so
# these prove which text wins and that the runner-up is still credited — before the fix the
# per-translator stamp groups overwrote each other and one of the two vanished from the credits.

CONTESTED_AUTHORS = {**AUTHORS, "SandRuin": "human", "ecodead": "human"}


def contested(ws, *extra):
    """Two approved units for a.key, the older one listed first so order cannot be what decides."""
    return run(ws, [unit(id=4, ts=100, translator="SandRuin", value="早い Alpha"),
                    unit(id=9, ts=200, translator="老本願", value="新しい Alpha")], *extra)


def test_the_newest_of_two_approvals_ships_and_both_are_credited():
    ws = workspace(authors=CONTESTED_AUTHORS)
    contributors = os.path.join(ws, "contributors.json")
    proc = contested(ws, "--contributors-file", contributors)
    assert proc.returncode == 0, proc.stderr
    # Newest wins because that is the one the relay already serves every player in this locale.
    assert lang_of(ws)["a.key"] == "新しい Alpha"
    assert prov_of(ws)["a.key"] == {"author": "老本願", "reviewer": "SandRuin"}
    # build_contributors counts a key for its author OR its reviewer, so neither name is lost.
    names = {c["name"] for c in read_json(contributors)["contributors"]}
    assert names == {"老本願", "SandRuin"}, names


def test_a_contested_key_is_stamped_exactly_once():
    ws = workspace(authors=CONTESTED_AUTHORS)
    proc = contested(ws)
    assert proc.returncode == 0, proc.stderr
    stamps = [ln for ln in proc.stdout.splitlines() if "--keys" in ln and "a.key" in ln]
    assert len(stamps) == 1, proc.stdout


def test_a_relay_pick_outranks_the_newest_approval():
    ws = workspace(authors=CONTESTED_AUTHORS)
    proc = run(ws, [unit(id=9, ts=200, translator="老本願", value="新しい Alpha"),
                    unit(id=4, ts=100, translator="SandRuin", value="選ばれた Alpha", picked=True)])
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["a.key"] == "選ばれた Alpha"
    assert prov_of(ws)["a.key"] == {"author": "SandRuin", "reviewer": "老本願"}


def test_a_contender_for_a_line_that_already_matches_is_named_not_credited():
    ws = workspace(authors=CONTESTED_AUTHORS)
    # The winner's text is what the file already holds, so the AI author stands and the only slot
    # left is the reviewer's — there is nowhere to put the runner-up but the report.
    proc = run(ws, [unit(id=9, ts=200, translator="老本願", value="AI Alpha"),
                    unit(id=4, ts=100, translator="ecodead", value="別の Alpha")])
    assert proc.returncode == 0, proc.stderr
    assert prov_of(ws)["a.key"] == {"author": "Opus 5 (Claude)", "reviewer": "老本願"}
    assert "WARNING" in proc.stdout and "ecodead" in proc.stdout, proc.stdout


def test_a_third_translator_of_one_line_is_named_and_not_registered():
    ws = workspace()  # neither SandRuin nor ecodead is in the registry yet
    proc = run(ws, [unit(id=9, ts=300, translator="老本願", value="一 Alpha"),
                    unit(id=8, ts=200, translator="SandRuin", value="二 Alpha"),
                    unit(id=7, ts=100, translator="ecodead", value="三 Alpha")], "--register-new")
    assert proc.returncode == 0, proc.stderr
    assert prov_of(ws)["a.key"] == {"author": "老本願", "reviewer": "SandRuin"}
    assert "WARNING" in proc.stdout and "ecodead" in proc.stdout, proc.stdout
    # Registering a name nothing credits is what put ecodead in authors.json with no line behind
    # them and no entry in the shipped credits.
    authors = read_json(os.path.join(ws, "authors.json"))
    assert "SandRuin" in authors and "ecodead" not in authors, authors


def test_stale_approval_is_skipped():
    ws = workspace()
    proc = run(ws, [unit(source="Some older English")])
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["a.key"] == "AI Alpha"
    assert "en_us changed" in proc.stdout
    assert prov_of(ws)["a.key"]["reviewer"] == ""


def test_unregistered_translator_fails_and_changes_nothing():
    ws = workspace()
    proc = run(ws, [unit(translator="Newcomer")])
    assert proc.returncode != 0
    assert "not in authors.json" in proc.stderr
    assert lang_of(ws)["a.key"] == "AI Alpha"
    assert read_json(os.path.join(ws, "authors.json")) == AUTHORS


def test_register_new_adds_the_translator_as_human():
    ws = workspace()
    proc = run(ws, [unit(translator="Newcomer")], "--register-new")
    assert proc.returncode == 0, proc.stderr
    assert read_json(os.path.join(ws, "authors.json"))["Newcomer"] == "human"
    assert prov_of(ws)["a.key"]["author"] == "Newcomer"


def test_register_new_reports_the_names_it_added():
    """The report the PR body is built from — who is new, so the reviewer is told."""
    ws = workspace()
    out = os.path.join(ws, "new-authors.json")
    proc = run(ws, [unit(translator="Newcomer")], "--register-new", "--new-authors-out", out)
    assert proc.returncode == 0, proc.stderr
    assert read_json(out) == ["Newcomer"]
    assert read_json(os.path.join(ws, "authors.json"))["Newcomer"] == "human"


def test_the_report_is_empty_when_every_translator_is_already_known():
    ws = workspace()
    out = os.path.join(ws, "new-authors.json")
    proc = run(ws, [unit()], "--register-new", "--new-authors-out", out)
    assert proc.returncode == 0, proc.stderr
    assert read_json(out) == []


def test_register_new_dry_run_writes_neither_registry_nor_report():
    ws = workspace()
    out = os.path.join(ws, "new-authors.json")
    proc = run(ws, [unit(translator="Newcomer")],
               "--register-new", "--new-authors-out", out, "--dry-run")
    assert proc.returncode == 0, proc.stderr
    assert "would register" in proc.stdout
    assert not os.path.exists(out), "a dry run must write no report file"
    assert read_json(os.path.join(ws, "authors.json")) == AUTHORS


def test_ai_credited_submission_is_refused_even_with_register_new():
    """--register-new must not become a way to smuggle a machine into the human counts."""
    ws = workspace()
    proc = run(ws, [unit(translator="Opus 5 (Claude)")], "--register-new")
    assert proc.returncode != 0
    assert "registered as AI" in proc.stderr
    assert read_json(os.path.join(ws, "authors.json")) == AUTHORS
    assert lang_of(ws)["a.key"] == "AI Alpha"


def test_a_translator_whose_every_approval_was_skipped_is_not_registered():
    """Registering on sight would leave a name in the ledger with no stamped line behind it."""
    ws = workspace()
    out = os.path.join(ws, "new-authors.json")
    proc = run(ws, [unit(translator="Newcomer", source="Some older English")],
               "--register-new", "--new-authors-out", out)
    assert proc.returncode == 0, proc.stderr
    assert "en_us changed" in proc.stdout
    assert read_json(os.path.join(ws, "authors.json")) == AUTHORS
    assert read_json(out) == []


def test_anonymous_submission_is_credited_to_one_registered_name():
    ws = workspace()
    proc = run(ws, [unit(translator="")], "--register-new")
    assert proc.returncode == 0, proc.stderr
    assert read_json(os.path.join(ws, "authors.json"))["Anonymous contributor"] == "human"
    assert prov_of(ws)["a.key"]["author"] == "Anonymous contributor"


def test_ai_credited_submission_is_refused():
    ws = workspace()
    proc = run(ws, [unit(translator="Opus 5 (Claude)")])
    assert proc.returncode != 0
    assert "registered as AI" in proc.stderr
    assert lang_of(ws)["a.key"] == "AI Alpha"


def test_dry_run_writes_nothing():
    ws = workspace()
    before = open(os.path.join(ws, "lang", "xx_yy.json"), "rb").read()
    proc = run(ws, [unit()], "--dry-run")
    assert proc.returncode == 0, proc.stderr
    assert open(os.path.join(ws, "lang", "xx_yy.json"), "rb").read() == before
    assert prov_of(ws)["a.key"]["reviewer"] == ""
    assert "would run" in proc.stdout


def test_unknown_key_is_reported_not_invented():
    """A key neither the locale nor English has means relay and repo disagree. Still fatal."""
    ws = workspace()
    proc = run(ws, [unit(unitId="nope.key", source="")])
    assert proc.returncode != 0
    assert "not a key" in proc.stderr


def test_key_english_has_but_the_locale_lacks_is_deferred_not_fatal():
    """Locale drift: a real translation of a real string, with no line to edit yet.

    On 2026-08-30 this was 144 of the 198 failures that stopped the weekly import dead — the
    editor offers the English key set, so players translate keys their locale has not caught up
    to. Nothing can be written for them, but nothing about them is wrong either.
    """
    ws = workspace(lang={"a.key": "AI Alpha", "b.key": "AI Beta"},   # c.key missing, EN has it
                   prov={"a.key": {"author": "Opus 5 (Claude)", "reviewer": ""},
                         "b.key": {"author": "Opus 5 (Claude)", "reviewer": ""}})
    proc = run(ws, [unit(unitId="c.key", source="Gamma", value="人工 Gamma")])
    assert proc.returncode == 0, proc.stderr
    assert "locale drift" in proc.stdout
    assert "c.key" not in lang_of(ws)          # nothing invented


def test_plural_form_the_locale_cannot_use_is_deferred_and_named_as_such():
    """A `.few` offered to a one/other language: approved work that must never be written.

    Russian was shown 27 of these and translated them all. Writing them would put a key in the
    file that validate-locale.py rejects and the language can never select.
    """
    en = dict(EN, **{"n.one": "one thing", "n.other": "many things"})
    xx = dict(XX, **{"n.one": "AI one", "n.other": "AI many"})
    prov = dict(PROV, **{"n.one": {"author": "Opus 5 (Claude)", "reviewer": ""},
                         "n.other": {"author": "Opus 5 (Claude)", "reviewer": ""}})
    ws = workspace(lang=xx, prov=prov)
    write_json(os.path.join(ws, "lang", "en_us.json"), en)
    proc = run(ws, [unit(unitId="n.few", source="", value="人工")])
    assert proc.returncode == 0, proc.stderr
    assert "not a plural form" in proc.stdout
    assert "n.few" not in lang_of(ws)


def test_deferred_units_do_not_hold_up_the_ones_that_can_land():
    """The whole point: 979 good translations must not be lost to units that have nowhere to go."""
    ws = workspace(lang={"a.key": "AI Alpha", "b.key": "AI Beta"},
                   prov={"a.key": {"author": "Opus 5 (Claude)", "reviewer": ""},
                         "b.key": {"author": "Opus 5 (Claude)", "reviewer": ""}})
    out = os.path.join(ws, "deferred.json")
    proc = run(ws, [unit(unitId="c.key", source="Gamma", value="人工 Gamma"),
                    unit(id=2, unitId="a.key", source="Alpha", value="人工 Alpha")],
               "--deferred-out", out)
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["a.key"] == "人工 Alpha"
    assert prov_of(ws)["a.key"]["author"] == "老本願"
    assert len(read_json(out)) == 1


def test_deferred_out_writes_nothing_on_a_dry_run():
    ws = workspace()
    out = os.path.join(ws, "deferred.json")
    proc = run(ws, [unit(unitId="zzz.key", source="")], "--dry-run", "--deferred-out", out)
    assert proc.returncode != 0          # zzz.key is in neither file — a genuine disagreement
    assert not os.path.exists(out)


def test_a_translation_that_drops_an_argument_is_deferred_not_written():
    """The live 2026-08-30 blocker, in miniature.

    ru_ru's approved `clear_backups.confirm.both` carried one %s where the English has two. The
    importer wrote it, and the post-import validator then failed the whole run — so one malformed
    submission held 1,105 good ones hostage, which is the exact failure deferral exists to prevent.
    """
    ws = workspace(lang={"a.key": "AI Alpha", "b.key": "AI Beta", "c.key": "AI %s and %s"},
                   prov=dict(PROV, **{"c.key": {"author": "Opus 5 (Claude)", "reviewer": ""}}))
    write_json(os.path.join(ws, "lang", "en_us.json"), dict(EN, **{"c.key": "%s and %s"}))
    out = os.path.join(ws, "deferred.json")
    proc = run(ws, [unit(unitId="c.key", source="", value="только %s"),
                    unit(id=2, unitId="a.key", source="Alpha", value="人工 Alpha")],
               "--deferred-out", out)
    assert proc.returncode == 0, proc.stderr
    assert "do not match" in proc.stdout
    assert lang_of(ws)["c.key"] == "AI %s and %s"      # the broken value was NOT written
    assert lang_of(ws)["a.key"] == "人工 Alpha"          # the good one in the same batch still landed
    assert len(read_json(out)) == 1


def test_a_translation_with_a_bare_percent_is_deferred():
    """Component.translatable runs MC's format parser: a bare % throws and takes the screen down.

    Nothing else catches this — validate-locale compares argument tokens, and a bare % produces
    none, so it compares EQUAL to an English that has none either.
    """
    ws = workspace()
    proc = run(ws, [unit(unitId="a.key", source="Alpha", value="Готово на 50%")])
    assert proc.returncode == 0, proc.stderr
    assert "bare" in proc.stdout
    assert lang_of(ws)["a.key"] == "AI Alpha"


def test_a_literal_percent_the_english_lacks_is_still_imported():
    """`%%` is an escape, not an argument — a translation may add one where English has none."""
    ws = workspace()
    proc = run(ws, [unit(unitId="a.key", source="Alpha", value="Готово на 50%%")])
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["a.key"] == "Готово на 50%%"


def test_reordered_positional_arguments_are_imported():
    """Grammar may move arguments; the multiset is what has to match."""
    ws = workspace(lang={"a.key": "AI Alpha", "b.key": "AI Beta", "c.key": "AI %1$s %2$s"},
                   prov=dict(PROV, **{"c.key": {"author": "Opus 5 (Claude)", "reviewer": ""}}))
    write_json(os.path.join(ws, "lang", "en_us.json"), dict(EN, **{"c.key": "%1$s before %2$s"}))
    proc = run(ws, [unit(unitId="c.key", source="", value="%2$s vor %1$s")])
    assert proc.returncode == 0, proc.stderr
    assert lang_of(ws)["c.key"] == "%2$s vor %1$s"


def test_book_field_that_no_longer_exists_is_deferred():
    ws = workspace()
    proc = run(ws, [unit(unitType="book", unitId="random_books/deathnote#gone",
                         source="", value="人工")])
    assert proc.returncode == 0, proc.stderr
    assert "edit this one by hand" in proc.stdout
    assert "nope.key" not in lang_of(ws)


def test_lang_file_formatting_survives():
    """The blank-line grouping and CRLF that a json round trip would flatten."""
    ws = workspace()
    path = os.path.join(ws, "lang", "xx_yy.json")
    grouped = ('{\r\n  "a.key": "AI Alpha",\r\n\r\n  "b.key": "AI Beta",\r\n'
               '  "c.key": "AI Gamma"\r\n}\r\n')
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(grouped)
    proc = run(ws, [unit()])
    assert proc.returncode == 0, proc.stderr
    raw = open(path, encoding="utf-8", newline="").read()
    assert "\r\n\r\n" in raw, "blank-line grouping was flattened"
    assert raw.count("\r\n") == grouped.count("\r\n"), "line endings changed"
    assert '"a.key": "人工 Alpha"' in raw


def book_prov_of(ws):
    return read_json(os.path.join(ws, "narrative-prov", "xx_yy.json"))


def test_book_field_is_replaced_and_reviewed_not_reauthored():
    """One field fixed out of three: a person has read the book, but did not write it."""
    ws = workspace()
    rows = [unit(unitType="book", namespace="dungeontrain",
                 unitId="random_books/deathnote#variants.0", source="", value="人工 one")]
    proc = run(ws, rows)
    assert proc.returncode == 0, proc.stderr
    book = read_json(os.path.join(ws, "narrative", "xx_yy", "random_books", "deathnote.json"))
    assert book["variants"] == ["人工 one", "AI two"]
    assert book["id"] == "deathnote", "structural key must be untouched"
    assert book_prov_of(ws)["random_books/deathnote"] == {
        "author": "Opus 5 (Claude)", "reviewer": "老本願"}


def test_book_with_every_field_replaced_takes_the_translator_as_author():
    ws = workspace()
    fields = {"title": "人工 Title", "variants.0": "人工 one", "variants.1": "人工 two"}
    rows = [unit(id=i, unitType="book", unitId=f"random_books/deathnote#{field}",
                 source="", value=value)
            for i, (field, value) in enumerate(fields.items())]
    proc = run(ws, rows)
    assert proc.returncode == 0, proc.stderr
    assert book_prov_of(ws)["random_books/deathnote"] == {
        "author": "老本願", "reviewer": "老本願"}


def test_book_structural_field_is_refused():
    ws = workspace()
    rows = [unit(unitType="book", unitId="random_books/deathnote#id", source="", value="hacked")]
    proc = run(ws, rows)
    assert proc.returncode != 0
    book = read_json(os.path.join(ws, "narrative", "xx_yy", "random_books", "deathnote.json"))
    assert book["id"] == "deathnote"


def test_repeated_book_text_edits_the_field_that_was_approved():
    """Two fields holding the same prose: the approved one is edited and only that one.

    This used to be refused as ambiguous, which is what stopped 11 approved Russian death_lore
    translations on 2026-08-30 — that file reuses the same question across four entries on
    purpose. Ambiguity by inspection is resolved by trying each occurrence and keeping the one
    whose parsed tree is the tree the edit was meant to produce.
    """
    ws = workspace(book={"id": "deathnote", "title": "Same", "variants": ["Same", "Other"]})
    rows = [unit(unitType="book", unitId="random_books/deathnote#variants.0",
                 source="", value="人工")]
    proc = run(ws, rows)
    assert proc.returncode == 0, proc.stderr
    book = read_json(os.path.join(ws, "narrative", "xx_yy", "random_books", "deathnote.json"))
    assert book["variants"] == ["人工", "Other"]
    assert book["title"] == "Same"       # the identical string in the OTHER field is untouched


def book_of(ws):
    return read_json(os.path.join(ws, "narrative", "xx_yy", "random_books", "deathnote.json"))


def figure_unit(value):
    """An approved translation of the one English field that names a figure."""
    return unit(unitType="book", unitId="random_books/deathnote#variants.0",
                source="", value=value)


def test_book_translation_that_drops_a_figure_is_deferred_not_written():
    """The gap #1210 and #1214 both left open: they check printf, book prose uses {braces}."""
    ws = workspace(book_en=BOOK_EN_WITH_FIGURE)
    proc = run(ws, [figure_unit("кто пал")])
    assert proc.returncode == 0, proc.stderr
    assert "nowhere to land" in proc.stdout
    assert "{deaths_nth}" in proc.stdout
    assert book_of(ws)["variants"] == ["AI one", "AI two"], "the broken value must not be written"


def test_the_russian_ordinal_regression_is_deferred():
    """{deaths_nth} swapped for {deaths} plus a glued adjective ending — the live 2026-08-30 near
    miss, which read "2й, кто пал" where it should read "второй, кто пал"."""
    ws = workspace(book_en=BOOK_EN_WITH_FIGURE)
    proc = run(ws, [figure_unit("{deaths}й, кто пал.")])
    assert proc.returncode == 0, proc.stderr
    assert "needs fixing at the relay" in proc.stdout
    assert book_of(ws)["variants"][0] == "AI one"


def test_a_book_translation_that_invents_a_figure_is_deferred():
    ws = workspace(book_en=BOOK_EN_WITH_FIGURE)
    proc = run(ws, [figure_unit("{deaths_nth} из {mobs}")])
    assert proc.returncode == 0, proc.stderr
    assert book_of(ws)["variants"][0] == "AI one"


def test_a_book_translation_that_keeps_the_figure_is_imported():
    """Order is the translator's business — the figure moves to wherever the grammar wants it."""
    ws = workspace(book_en=BOOK_EN_WITH_FIGURE)
    proc = run(ws, [figure_unit("павший {deaths_nth}")])
    assert proc.returncode == 0, proc.stderr
    assert book_of(ws)["variants"] == ["павший {deaths_nth}", "AI two"]


def test_one_broken_field_does_not_hold_up_the_rest_of_its_book():
    """Deferred, not fatal: somebody has to look at that field, nobody has to abandon the book."""
    ws = workspace(book_en=BOOK_EN_WITH_FIGURE)
    proc = run(ws, [figure_unit("кто пал"),
                    unit(id=2, unitType="book", unitId="random_books/deathnote#variants.1",
                         source="", value="人工 two")])
    assert proc.returncode == 0, proc.stderr
    assert book_of(ws)["variants"] == ["AI one", "人工 two"]


def test_a_book_with_no_english_original_still_imports():
    """A locale-only book has nothing to be measured against; that is not a reason to refuse it."""
    ws = workspace(book_en=None)
    proc = run(ws, [figure_unit("{deaths} что угодно")])
    assert proc.returncode == 0, proc.stderr
    assert book_of(ws)["variants"][0] == "{deaths} что угодно"


def test_rerun_is_idempotent():
    ws = workspace()
    assert run(ws, [unit()]).returncode == 0
    after_first = open(os.path.join(ws, "lang", "xx_yy.json"), "rb").read()
    prov_first = open(os.path.join(ws, "prov", "xx_yy.json"), "rb").read()
    proc = run(ws, [unit()])
    assert proc.returncode == 0, proc.stderr
    assert open(os.path.join(ws, "lang", "xx_yy.json"), "rb").read() == after_first
    assert open(os.path.join(ws, "prov", "xx_yy.json"), "rb").read() == prov_first


def serve(rows):
    """A loopback stand-in for the relay's admin listing. Returns (base_url, requests, stop)."""
    seen = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            seen.append(self.path)
            # Emulate the relay's keyset paging: `before` is the previous page's `oldestId`, and
            # the listing walks backwards from it. Inert for a row set that fits one page, so the
            # tests that serve a handful of rows see exactly what they always did.
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            limit = int((query.get("limit") or ["1000"])[0])
            before = (query.get("before") or [None])[0]
            start = 0
            if before is not None:
                ids = [str(r.get("id")) for r in rows]
                start = ids.index(before) + 1 if before in ids else len(rows)
            page = rows[start:start + limit]
            body = json.dumps({
                "ok": True,
                "units": page,
                "hasMore": start + limit < len(rows),
                "oldestId": page[-1]["id"] if page else None,
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_port}/cap", seen, server.shutdown


def run_http(ws, base, *extra):
    return subprocess.run(
        [sys.executable, SCRIPT, "--relay-base", base, "--locale", "xx_yy",
         "--authors-file", os.path.join(ws, "authors.json"),
         "--lang-dir", os.path.join(ws, "lang"),
         "--provenance-dir", os.path.join(ws, "prov"),
         "--narrative-dir", os.path.join(ws, "narrative"),
         "--narrative-en-dir", os.path.join(ws, "narrative-en"),
         "--narrative-provenance-dir", os.path.join(ws, "narrative-prov"), *extra],
        capture_output=True, text=True)


def test_fetches_the_approved_queue_from_the_relay():
    ws = workspace()
    base, seen, stop = serve([unit()])
    try:
        proc = run_http(ws, base)
    finally:
        stop()
    assert proc.returncode == 0, proc.stderr
    assert seen and "flag=approved" in seen[0] and "locale=xx_yy" in seen[0], seen
    assert lang_of(ws)["a.key"] == "人工 Alpha"


def test_a_queue_past_the_page_ceiling_is_paged_through_not_truncated():
    """
    The listing is newest-first, so taking only the first response would import the same page on
    every run and never reach anything older than the ceiling — which is precisely what was
    happening to ru_ru. Serve two and a half pages and require every row to arrive.
    """
    ws = workspace()
    rows = [unit(id=i) for i in range(2500)]
    base, seen, stop = serve(rows)
    try:
        proc = run_http(ws, base, "--dry-run")
    finally:
        stop()
    assert proc.returncode == 0, proc.stderr
    assert "INCOMPLETE" not in proc.stderr, proc.stderr
    # Three requests: rows 0-999, 1000-1999, 2000-2499. The first carries no cursor, the rest do.
    assert len(seen) == 3, seen
    assert "before=" not in seen[0], seen[0]
    assert "before=999" in seen[1], seen[1]
    assert "before=1999" in seen[2], seen[2]


def test_paging_stops_when_the_cursor_stops_advancing():
    """A relay that keeps saying hasMore while re-serving the same page must not spin forever."""
    ws = workspace()

    stuck = [unit(id=i) for i in range(10)]

    class Stuck(http.server.BaseHTTPRequestHandler):
        hits = 0

        def do_GET(self):
            Stuck.hits += 1
            body = json.dumps({"ok": True, "units": stuck,
                               "hasMore": True, "oldestId": 9}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Stuck)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        proc = run_http(ws, f"http://127.0.0.1:{server.server_port}/cap", "--dry-run")
    finally:
        server.shutdown()
    assert proc.returncode == 0, proc.stderr
    # Second request repeats the same rows, none of them fresh, so it stops there.
    assert Stuck.hits <= 2, Stuck.hits


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
