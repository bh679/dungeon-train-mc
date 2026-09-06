#!/usr/bin/env python3
"""The editor writes files a human hand-formatted, so these tests are mostly about NOT changing them.

    python3 -m pytest scripts/books-editor/test_corpus.py

The headline case is ``test_every_shipped_book_round_trips_byte_identical``: load and re-render the
whole corpus and demand the bytes come back the same. Anything that regresses the style probe shows
up there as a would-be diff in someone's PR.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

import corpus

ROOT = corpus.repo_root(Path(__file__))
BOOKS = corpus.list_books(ROOT)

#: `musings_of_faulthurst.json` indents one key with a single space — genuinely hand-formatted, so
#: no serialiser reproduces it. It is allowed to be inexact; the editor badges it for the author.
KNOWN_INEXACT = {"random_books/musings_of_faulthurst.json"}


def read_text(rel: str) -> str:
    return (corpus.narratives_dir(ROOT) / rel).read_text(encoding="utf-8")


# --- format preservation -----------------------------------------------------

def test_corpus_is_discovered():
    assert len(BOOKS) > 50
    assert {b["corpus"] for b in BOOKS} == {"starting", "random", "stories"}
    assert all("originals" not in b["path"] and "unused" not in b["path"] for b in BOOKS)


@pytest.mark.parametrize("rel", [b["path"] for b in BOOKS])
def test_every_shipped_book_round_trips_byte_identical(rel):
    text = read_text(rel)
    book = corpus.read_book(ROOT, rel)
    assert book["data"] == json.loads(text)
    if rel in KNOWN_INEXACT:
        assert not book["style"]["exact"], f"{rel} now round-trips — drop it from KNOWN_INEXACT"
        return
    assert book["style"]["exact"], f"{rel} does not round-trip — saving it would reformat the file"
    assert corpus.render(book["data"], book["style"]) == text


def test_writing_an_untouched_book_leaves_the_file_alone(tmp_path):
    rel = "starting_books/journey.json"
    fake_root = tmp_path / "repo"
    (fake_root / "src/main/resources/data/dungeontrain/narratives/starting_books").mkdir(parents=True)
    (fake_root / "gradle.properties").write_text("mod_version=0.0.0\n")
    source = corpus.narratives_dir(ROOT) / rel
    target = corpus.narratives_dir(fake_root) / rel
    shutil.copyfile(source, target)
    before = target.read_bytes()

    book = corpus.read_book(fake_root, rel)
    corpus.write_book(fake_root, rel, book["data"], book["style"], expected_mtime=book["mtime"])
    assert target.read_bytes() == before


def test_a_stale_write_is_refused(tmp_path):
    fake_root = tmp_path / "repo"
    (fake_root / "src/main/resources/data/dungeontrain/narratives/random_books").mkdir(parents=True)
    (fake_root / "gradle.properties").write_text("mod_version=0.0.0\n")
    rel = "random_books/example.json"
    data = {"id": "example", "title": "Example", "author": "A", "generation": 0, "weight": 1,
            "variants": ["One page."]}
    written = corpus.write_book(fake_root, rel, data, corpus.DEFAULT_STYLE)
    assert isinstance(written["mtime"], str), "an ns stamp must cross the wire as a string"
    with pytest.raises(corpus.CorpusError, match="changed on disk"):
        corpus.write_book(fake_root, rel, data, corpus.DEFAULT_STYLE,
                          expected_mtime=str(int(written["mtime"]) - 1))


# --- validation --------------------------------------------------------------

def test_valid_books_pass():
    for book in BOOKS:
        assert book["valid"], f"{book['path']} fails validation"


@pytest.mark.parametrize("data,message", [
    ({"variants": []}, "at least one"),
    ({"variants": ["  "]}, "empty"),
    ({"variants": ["ok"], "weight": -1}, "weight"),
    ({"variants": ["ok"], "generation": 9}, "generation"),
    ({"variants": ["ok"], "title": 5}, "title"),
])
def test_invalid_books_are_refused(data, message):
    with pytest.raises(corpus.CorpusError, match=message):
        corpus.validate("random_books/x.json", data, "x")


@pytest.mark.parametrize("data,message", [
    ({"letters": []}, "at least one letter"),
    ({"letters": [{"index": 0, "variants": ["a"]}]}, "positive integer"),
    ({"letters": [{"index": 1, "variants": ["a"]}, {"index": 1, "variants": ["b"]}]}, "duplicate"),
    ({"letters": [{"index": 1, "variants": []}]}, "variants"),
])
def test_invalid_stories_are_refused(data, message):
    with pytest.raises(corpus.CorpusError, match=message):
        corpus.validate("stories/x.json", data, "x")


def test_a_filename_outside_the_resourcelocation_charset_is_refused():
    # `lighting copy.json` is the real-world paper-cut: the registry warns and skips it.
    with pytest.raises(corpus.CorpusError, match="ResourceLocation"):
        corpus.validate("starting_books/x.json", {"variants": ["ok"]}, "lighting copy")


def test_an_id_that_does_not_match_the_filename_warns_but_does_not_block():
    book = corpus.read_book(ROOT, "starting_books/lighting.json")
    assert book["data"]["id"] == "lightning"          # shipped typo
    assert any("does not match" in w for w in book["warnings"])
    corpus.validate("starting_books/lighting.json", book["data"], "lighting")  # still saveable


# --- path confinement --------------------------------------------------------

@pytest.mark.parametrize("rel", [
    "../../../../../etc/passwd",
    "starting_books/../../../secrets.json",
    "/etc/passwd",
    "stages.json",
    "starting_books/journey.txt",
    "originals/old.json",
    "starting_books/unused/old.json",
])
def test_paths_outside_a_loadable_corpus_are_refused(rel):
    with pytest.raises(corpus.CorpusError):
        corpus.resolve(ROOT, rel)


def test_context_and_corpus_are_read_from_the_path():
    assert corpus.corpus_of("starting_books/respawn/x.json") == "starting"
    assert corpus.context_of("starting_books/respawn/x.json") == "respawn"
    assert corpus.context_of("starting_books/x.json") == ""


# --- localizations -----------------------------------------------------------

def test_translations_are_listed_and_readable():
    rel = "starting_books/journey.json"
    locales = corpus.locales_for(ROOT, rel)
    assert locales, "journey.json should have translations"
    data = corpus.read_localized(ROOT, rel, locales[0])
    assert data["variants"], "a translated book still has variants"


def test_a_bad_locale_is_refused():
    with pytest.raises(corpus.CorpusError):
        corpus.read_localized(ROOT, "starting_books/journey.json", "../../etc")
