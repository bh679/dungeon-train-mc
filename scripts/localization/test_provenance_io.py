#!/usr/bin/env python3
"""Unit tests for the shipped provenance MANIFESTS in provenance_io.py.

The manifests (``assets/dungeontrain/localization_provenance/<locale>.json``) are the
only part of the per-line provenance system that enters the jar: they tell the in-game
translation editor which units are AI-authored-and-unreviewed. Both stamp scripts
generate them and check-provenance.py hard-fails on drift, so the build/compact rules
are tested here once, against the single definition all three share.

The rest of provenance_io is already covered through the four script test files.

Run: python3 scripts/localization/test_provenance_io.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import provenance_io as pio  # noqa: E402  (needs the path insert above)

STAMP = os.path.join(HERE, "stamp-provenance.py")
CHECK = os.path.join(HERE, "check-provenance.py")

AI = "Opus 5 (Claude)"
HUMAN = "老本願"
AUTHORS = {AI: "ai", HUMAN: "human"}

LANG = {"a.key": "Alpha", "b.key": "Beta", "c.key": "Gamma"}
# a: machine, never reviewed -> the only AI-unreviewed line.
# b: machine but a human reviewed it. c: translated by a human outright.
PROV_MIXED = {
    "a.key": {"author": AI, "reviewer": ""},
    "b.key": {"author": AI, "reviewer": HUMAN},
    "c.key": {"author": HUMAN, "reviewer": HUMAN},
}
PROV_ALL_AI = {key: {"author": AI, "reviewer": ""} for key in LANG}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def workspace(prov=PROV_MIXED, locale="xx_yy", books=None, sibling_prov=None):
    """A self-contained lang/provenance/narrative/manifest tree. Returns its paths."""
    ws = tempfile.mkdtemp(prefix="provenance-manifest-test-")
    paths = {
        "root": ws,
        "lang": os.path.join(ws, "lang"),
        "prov": os.path.join(ws, "prov"),
        "sibling_prov": os.path.join(ws, "prov", "sibling"),
        "narrative": os.path.join(ws, "narrative_prov"),
        "manifests": os.path.join(ws, "manifests"),
        "authors": os.path.join(ws, "authors.json"),
    }
    write_json(os.path.join(paths["lang"], "en_us.json"), LANG)
    write_json(os.path.join(paths["lang"], f"{locale}.json"), LANG)
    write_json(os.path.join(paths["prov"], f"{locale}.json"), prov)
    if sibling_prov is not None:
        write_json(os.path.join(paths["sibling_prov"], f"{locale}.json"), sibling_prov)
    if books is not None:
        write_json(os.path.join(paths["narrative"], f"{locale}.json"), books)
    write_json(paths["authors"], AUTHORS)
    os.makedirs(paths["manifests"], exist_ok=True)
    return paths


def namespaces_for(paths, with_sibling=False):
    out = [pio.Namespace("dungeontrain", pio.Path(paths["lang"]), pio.Path(paths["prov"]),
                         None, None)]
    if with_sibling:
        out.append(pio.Namespace("playermob", pio.Path(paths["lang"]),
                                 pio.Path(paths["sibling_prov"]), None, None))
    return out


def build(paths, with_sibling=False, locale="xx_yy"):
    return pio.build_manifest(locale, AUTHORS, namespaces_for(paths, with_sibling),
                              pio.Path(paths["narrative"]))


def read_manifest(paths, locale="xx_yy"):
    with open(os.path.join(paths["manifests"], f"{locale}.json"), encoding="utf-8") as f:
        return json.load(f)


# ------------------------------------------------------------------ compact / expand

def test_compact_flags_whole_body_collapses_to_star():
    assert pio.compact_flags(3, ["a", "b", "c"]) == pio.MANIFEST_ALL


def test_compact_flags_partial_stays_a_list():
    assert pio.compact_flags(3, ["a"]) == ["a"]


def test_compact_flags_none_flagged_is_empty_list():
    assert pio.compact_flags(3, []) == []


def test_compact_flags_empty_body_is_never_star():
    # "everything in nothing" would render in-game as a wholly unreviewed locale.
    assert pio.compact_flags(0, []) == []


def test_expand_flags_round_trips_star():
    units = ["a", "b", "c"]
    assert pio.expand_flags(pio.compact_flags(3, units), units) == units


def test_expand_flags_round_trips_a_list():
    units = ["a", "b", "c"]
    assert pio.expand_flags(pio.compact_flags(3, ["b"]), units) == ["b"]


def test_expand_flags_tolerates_garbage():
    assert pio.expand_flags(None, ["a"]) == []


# ------------------------------------------------------------------ the predicate

def test_ai_unreviewed_keys_excludes_reviewed_and_human_authored():
    assert pio.ai_unreviewed_keys(PROV_MIXED, AUTHORS) == ["a.key"]


def test_ai_unreviewed_keys_keeps_lang_file_order():
    prov = {k: {"author": AI, "reviewer": ""} for k in ("c.key", "a.key", "b.key")}
    assert pio.ai_unreviewed_keys(prov, AUTHORS) == ["c.key", "a.key", "b.key"]


def test_ai_unreviewed_keys_ignores_unregistered_author():
    # An unregistered name is check-provenance.py's error to report, not a silent "ai".
    prov = {"a.key": {"author": "Nobody", "reviewer": ""}}
    assert pio.ai_unreviewed_keys(prov, AUTHORS) == []


# ------------------------------------------------------------------ build_manifest

def test_build_manifest_lists_only_ai_unreviewed():
    manifest = build(workspace())
    assert manifest["lang"]["dungeontrain"] == ["a.key"]


def test_build_manifest_collapses_a_fully_machine_locale():
    manifest = build(workspace(prov=PROV_ALL_AI))
    assert manifest["lang"]["dungeontrain"] == pio.MANIFEST_ALL


def test_build_manifest_omits_a_namespace_with_no_sidecar():
    # discordpresence has no zh_cn sidecar (its Chinese lives in its own repo) — "absent"
    # must stay distinguishable from "nothing needs review".
    manifest = build(workspace(), with_sibling=True)
    assert "playermob" not in manifest["lang"]
    assert manifest["lang"]["dungeontrain"] == ["a.key"]


def test_build_manifest_includes_a_sibling_that_has_a_sidecar():
    paths = workspace(sibling_prov=PROV_ALL_AI)
    assert build(paths, with_sibling=True)["lang"]["playermob"] == pio.MANIFEST_ALL


def test_build_manifest_omits_books_when_no_narrative_sidecar():
    assert "books" not in build(workspace())


def test_build_manifest_reads_books_from_the_narrative_sidecar():
    books = {"random_books/deathnote": {"author": AI, "reviewer": ""},
             "stories/one": {"author": HUMAN, "reviewer": HUMAN}}
    assert build(workspace(books=books))["books"] == ["random_books/deathnote"]


def test_build_manifest_carries_the_do_not_hand_edit_note():
    assert build(workspace())["_note"] == pio.MANIFEST_NOTE


# ------------------------------------------------------------------ refresh_manifests

def refresh(paths, with_sibling=False):
    return pio.refresh_manifests(
        AUTHORS, lang_dir=pio.Path(paths["lang"]),
        ns_list=namespaces_for(paths, with_sibling),
        narrative_prov_dir=pio.Path(paths["narrative"]),
        manifest_dir=pio.Path(paths["manifests"]))


def test_refresh_manifests_writes_one_file_per_locale():
    paths = workspace()
    assert len(refresh(paths)) == 1
    assert read_manifest(paths)["locale"] == "xx_yy"


def test_refresh_manifests_skips_an_unchanged_file():
    paths = workspace()
    refresh(paths)
    before = open(os.path.join(paths["manifests"], "xx_yy.json"), "rb").read()
    assert refresh(paths) == []
    assert open(os.path.join(paths["manifests"], "xx_yy.json"), "rb").read() == before


def test_refresh_manifests_rewrites_a_drifted_file():
    paths = workspace()
    refresh(paths)
    write_json(os.path.join(paths["manifests"], "xx_yy.json"), {"locale": "xx_yy"})
    assert len(refresh(paths)) == 1
    assert read_manifest(paths)["lang"]["dungeontrain"] == ["a.key"]


def test_refresh_manifests_rewrites_an_unparseable_file():
    paths = workspace()
    with open(os.path.join(paths["manifests"], "xx_yy.json"), "w", encoding="utf-8") as f:
        f.write("{not json")
    assert len(refresh(paths)) == 1
    assert read_manifest(paths)["locale"] == "xx_yy"


def test_refresh_manifests_deletes_an_orphan():
    # A locale that stopped shipping a lang file must not leave a stale jar asset behind.
    paths = workspace()
    orphan = os.path.join(paths["manifests"], "zz_zz.json")
    write_json(orphan, {"locale": "zz_zz"})
    refresh(paths)
    assert not os.path.exists(orphan)


def test_refresh_manifests_never_manifests_the_source_locale():
    paths = workspace()
    refresh(paths)
    assert not os.path.exists(os.path.join(paths["manifests"], "en_us.json"))


# ------------------------------------------------------------------ CLI wiring

def stamp_cli(paths, *extra):
    return subprocess.run(
        [sys.executable, STAMP, "--sync", "--lang-dir", paths["lang"],
         "--provenance-dir", paths["prov"], "--authors-file", paths["authors"],
         "--credits-dir", os.path.join(paths["root"], "credits"),
         "--contributors-file", os.path.join(paths["root"], "contributors.json"),
         "--narrative-provenance-dir", paths["narrative"],
         "--manifest-dir", paths["manifests"], *extra],
        capture_output=True, text=True)


def check_cli(paths, *extra):
    return subprocess.run(
        [sys.executable, CHECK, "--lang-dir", paths["lang"],
         "--provenance-dir", paths["prov"], "--authors-file", paths["authors"],
         "--narrative-provenance-dir", paths["narrative"],
         "--manifest-dir", paths["manifests"], *extra],
        capture_output=True, text=True)


def sole_lang_flags(paths):
    """The workspace's one namespace's flags — it is named "(explicit)" on the --lang-dir
    path, which is an internal label this test has no business asserting on."""
    return list(read_manifest(paths)["lang"].values())


def test_stamp_cli_generates_the_manifest():
    paths = workspace()
    result = stamp_cli(paths)
    assert result.returncode == 0, result.stderr
    assert sole_lang_flags(paths) == [["a.key"]]


def test_stamp_cli_without_manifest_dir_writes_nothing():
    # The explicit single-dir path must never touch the real shipped assets tree.
    paths = workspace()
    result = subprocess.run(
        [sys.executable, STAMP, "--sync", "--lang-dir", paths["lang"],
         "--provenance-dir", paths["prov"], "--authors-file", paths["authors"],
         "--credits-dir", os.path.join(paths["root"], "credits"),
         "--contributors-file", os.path.join(paths["root"], "contributors.json")],
        capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert os.listdir(paths["manifests"]) == []


def test_check_cli_accepts_a_freshly_stamped_manifest():
    paths = workspace()
    stamp_cli(paths)
    result = check_cli(paths)
    assert result.returncode == 0, result.stderr


def test_check_cli_fails_on_a_drifted_manifest():
    paths = workspace()
    stamp_cli(paths)
    manifest = read_manifest(paths)
    manifest["lang"] = {ns: ["b.key"] for ns in manifest["lang"]}
    write_json(os.path.join(paths["manifests"], "xx_yy.json"), manifest)
    result = check_cli(paths)
    assert result.returncode == 1, result.stdout
    assert "out of date" in result.stderr


def test_check_cli_fails_on_a_missing_manifest():
    paths = workspace()
    result = check_cli(paths)
    assert result.returncode == 1, result.stdout
    assert "missing generated manifest" in result.stderr


def test_check_cli_fails_on_an_orphan_manifest():
    paths = workspace()
    stamp_cli(paths)
    write_json(os.path.join(paths["manifests"], "zz_zz.json"), {"locale": "zz_zz"})
    result = check_cli(paths)
    assert result.returncode == 1, result.stdout
    assert "orphan manifest" in result.stderr


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
