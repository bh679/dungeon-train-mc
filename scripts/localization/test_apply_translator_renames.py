#!/usr/bin/env python3
"""Unit tests for apply-translator-renames.py — the relay's rename log, applied to the repo.

Drives the script as a CLI against isolated temp fixtures through --from-file, like
test_import_approved_translations.py. Regeneration of the shipped credit files is
stamp-provenance.py's job and is covered there; --skip-stamp keeps these tests to what this
script owns — which names change, which are refused, and that nothing else is touched.

Run: python3 scripts/localization/test_apply_translator_renames.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "apply-translator-renames.py")

AUTHORS = {
    "Opus 5 (Claude)": "ai",
    "老本願": {"kind": "human", "url": "https://space.bilibili.com/296088227"},
    "Old Name": "human",
    "Other Person": "human",
}
PROV = {
    "a.key": {"author": "Old Name", "reviewer": ""},
    "b.key": {"author": "Opus 5 (Claude)", "reviewer": "Old Name"},
    "c.key": {"author": "Other Person", "reviewer": "Old Name"},
}
SIBLING_PROV = {"x.key": {"author": "Old Name", "reviewer": ""}}
BOOKS_PROV = {"random_books/deathnote": {"author": "Opus 5 (Claude)", "reviewer": "Old Name"}}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def workspace():
    ws = tempfile.mkdtemp(prefix="apply-renames-test-")
    write_json(os.path.join(ws, "authors.json"), AUTHORS)
    write_json(os.path.join(ws, "prov", "xx_yy.json"), PROV)
    write_json(os.path.join(ws, "prov", "adventureitemnames", "xx_yy.json"), SIBLING_PROV)
    write_json(os.path.join(ws, "narrative-prov", "xx_yy.json"), BOOKS_PROV)
    return ws


def run(ws, renames, *extra):
    payload = os.path.join(ws, "renames.json")
    write_json(payload, {"ok": True, "count": len(renames), "renames": renames})
    report = os.path.join(ws, "report.json")
    cmd = [sys.executable, SCRIPT, "--from-file", payload,
           "--authors-file", os.path.join(ws, "authors.json"),
           "--provenance-dir", os.path.join(ws, "prov"),
           "--narrative-provenance-dir", os.path.join(ws, "narrative-prov"),
           "--report-out", report, "--skip-stamp", *extra]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc, (read_json(report) if os.path.isfile(report) else None)


def rename(src, dst, uuid="0123456789abcdef0123456789abcdef"):
    return {"id": 1, "ts": 1, "uuid": uuid, "from": src, "to": dst, "updated": 1}


class ApplyRenames(unittest.TestCase):

    def test_renames_the_registry_and_every_sidecar(self):
        ws = workspace()
        proc, report = run(ws, [rename("Old Name", "New Name")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        authors = read_json(os.path.join(ws, "authors.json"))
        self.assertNotIn("Old Name", authors)
        self.assertEqual(authors["New Name"], "human")
        self.assertEqual(list(authors), ["Opus 5 (Claude)", "老本願", "New Name", "Other Person"],
                         "renamed in place, order kept")
        prov = read_json(os.path.join(ws, "prov", "xx_yy.json"))
        self.assertEqual(prov["a.key"], {"author": "New Name", "reviewer": ""})
        self.assertEqual(prov["b.key"], {"author": "Opus 5 (Claude)", "reviewer": "New Name"})
        self.assertEqual(prov["c.key"], {"author": "Other Person", "reviewer": "New Name"})
        self.assertEqual(read_json(os.path.join(ws, "prov", "adventureitemnames", "xx_yy.json")),
                         {"x.key": {"author": "New Name", "reviewer": ""}})
        self.assertEqual(read_json(os.path.join(ws, "narrative-prov", "xx_yy.json")),
                         {"random_books/deathnote": {"author": "Opus 5 (Claude)", "reviewer": "New Name"}})
        self.assertEqual(report["applied"], [{"from": "Old Name", "to": "New Name", "entries": 5}])
        self.assertEqual(report["skipped"], [])

    def test_keeps_the_object_form_and_url(self):
        ws = workspace()
        proc, _ = run(ws, [rename("老本願", "老本願 (bilibili)")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        authors = read_json(os.path.join(ws, "authors.json"))
        self.assertEqual(authors["老本願 (bilibili)"],
                         {"kind": "human", "url": "https://space.bilibili.com/296088227"})

    def test_skips_a_name_the_repo_never_credited(self):
        ws = workspace()
        proc, report = run(ws, [rename("Never Landed", "Whoever")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(report["applied"], [])
        self.assertEqual(len(report["skipped"]), 1)
        self.assertIn("never credited", report["skipped"][0]["reason"])
        self.assertEqual(read_json(os.path.join(ws, "authors.json")), AUTHORS, "untouched")

    def test_refuses_to_merge_onto_a_registered_name(self):
        ws = workspace()
        proc, report = run(ws, [rename("Old Name", "Other Person")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(report["applied"], [])
        self.assertIn("already registered", report["skipped"][0]["reason"])
        self.assertEqual(read_json(os.path.join(ws, "prov", "xx_yy.json")), PROV, "untouched")

    def test_refuses_to_rename_a_machine(self):
        ws = workspace()
        proc, report = run(ws, [rename("Opus 5 (Claude)", "Somebody")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("registered as AI", report["skipped"][0]["reason"])

    def test_applies_a_chain_in_order(self):
        ws = workspace()
        proc, report = run(ws, [rename("Old Name", "Middle"), rename("Middle", "Final")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual([a["to"] for a in report["applied"]], ["Middle", "Final"])
        authors = read_json(os.path.join(ws, "authors.json"))
        self.assertIn("Final", authors)
        self.assertNotIn("Middle", authors)
        self.assertEqual(read_json(os.path.join(ws, "prov", "xx_yy.json"))["a.key"]["author"], "Final")

    def test_dry_run_writes_nothing(self):
        ws = workspace()
        proc, report = run(ws, [rename("Old Name", "New Name")], "--dry-run")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("would rename 'Old Name' -> 'New Name'", proc.stdout)
        self.assertIsNone(report, "no report on a dry run")
        self.assertEqual(read_json(os.path.join(ws, "authors.json")), AUTHORS)
        self.assertEqual(read_json(os.path.join(ws, "prov", "xx_yy.json")), PROV)

    def test_ignores_malformed_rows_and_an_empty_log(self):
        ws = workspace()
        proc, report = run(ws, [{"from": "", "to": "x"}, {"from": "Old Name", "to": "Old Name"}, {"nope": 1}])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("no renames to apply", proc.stdout)
        self.assertEqual(report, {"applied": [], "skipped": []})
        self.assertEqual(read_json(os.path.join(ws, "authors.json")), AUTHORS)


if __name__ == "__main__":
    unittest.main()
