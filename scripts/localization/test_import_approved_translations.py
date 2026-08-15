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


def unit(**over):
    """One approved row in the shape the relay's admin listing returns."""
    row = {"id": 1, "translator": "老本願", "locale": "xx_yy", "unitType": "lang",
           "namespace": "dungeontrain", "unitId": "a.key", "source": "Alpha",
           "value": "人工 Alpha", "flag": "approved"}
    row.update(over)
    return row


def workspace(lang=XX, prov=PROV, authors=AUTHORS, book=BOOK):
    """A miniature repo: one locale's lang file + sidecar, one book, an author registry."""
    ws = tempfile.mkdtemp(prefix="import-approved-test-")
    lang_dir = os.path.join(ws, "lang")
    prov_dir = os.path.join(ws, "prov")
    nar_prov_dir = os.path.join(ws, "narrative-prov")
    nar_dir = os.path.join(ws, "narrative", "xx_yy", "random_books")
    for d in (lang_dir, prov_dir, nar_prov_dir, nar_dir):
        os.makedirs(d)
    write_json(os.path.join(lang_dir, "en_us.json"), EN)
    write_json(os.path.join(lang_dir, "xx_yy.json"), lang)
    write_json(os.path.join(ws, "authors.json"), authors)
    write_json(os.path.join(nar_dir, "deathnote.json"), book)
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
    ws = workspace()
    proc = run(ws, [unit(unitId="nope.key", source="")])
    assert proc.returncode != 0
    assert "not a key" in proc.stderr
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


def test_ambiguous_book_text_is_refused_rather_than_guessed():
    ws = workspace(book={"id": "deathnote", "title": "Same", "variants": ["Same", "Other"]})
    rows = [unit(unitType="book", unitId="random_books/deathnote#variants.0",
                 source="", value="人工")]
    proc = run(ws, rows)
    assert proc.returncode != 0
    assert "more than once" in proc.stderr
    book = read_json(os.path.join(ws, "narrative", "xx_yy", "random_books", "deathnote.json"))
    assert book["variants"] == ["Same", "Other"]


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
            body = json.dumps({"ok": True, "units": rows}).encode("utf-8")
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


def test_a_truncated_queue_is_reported_not_imported_silently():
    ws = workspace()
    base, _seen, stop = serve([unit(id=i) for i in range(1000)])
    try:
        proc = run_http(ws, base, "--dry-run")
    finally:
        stop()
    assert proc.returncode == 0, proc.stderr
    assert "INCOMPLETE" in proc.stderr, proc.stderr


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
