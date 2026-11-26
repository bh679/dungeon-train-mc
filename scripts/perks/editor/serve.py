#!/usr/bin/env python3
"""Local perks constellation editor: the site's Perks page (edit mode) against this checkout's tree.json.

    python3 scripts/perks/editor/serve.py        # → http://127.0.0.1:8834/dungeontrain/perks/

No login: the page's relay API is stubbed here, the viewer is always the owner. The page's static files
come from a dp-relay checkout (``--site``, default ~/Projects/dp-relay/public/dungeontrain). "Save" in edit
mode (PUT /perks/tree) runs the tree through ``scripts/perks/tree-from-editor.py`` and writes
``src/main/resources/data/dungeontrain/perks/tree.json``; review it with ``git diff``. Binds 127.0.0.1 only.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TREE = REPO / "src/main/resources/data/dungeontrain/perks/tree.json"
CONVERTER = REPO / "scripts/perks/tree-from-editor.py"
API = "/api/dp-relay/perks/"
MAX_BODY = 4 * 1024 * 1024
ME = {"ok": True, "user": {"id": "local", "name": "Local editor", "avatar": ""}, "valueAdder": True, "owner": True}


def save_tree(tree: dict) -> None:
    """Write the editor's tree to tree.json through the converter, so editor-only fields stay out."""
    if not isinstance(tree, dict) or not isinstance(tree.get("perks"), list):
        raise ValueError("body is not a perks tree")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(tree, f)
        src = f.name
    r = subprocess.run([sys.executable, str(CONVERTER), src, "--out", str(TREE)], capture_output=True, text=True)
    Path(src).unlink(missing_ok=True)
    if r.returncode != 0:
        raise ValueError((r.stderr or r.stdout).strip() or "converter failed")


class Handler(BaseHTTPRequestHandler):
    server_version = "DTPerksEditor/1.0"
    site: Path

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, status: int = 200) -> None:
        self._send(status, json.dumps(payload).encode(), "application/json; charset=utf-8")

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path in ("/", "/dungeontrain", "/dungeontrain/"):
            self.send_response(302)
            self.send_header("Location", "/dungeontrain/perks/")
            self.end_headers()
            return
        if path.startswith(API):
            return self._api_get(path[len(API):])
        if path.startswith("/dungeontrain/"):
            return self._static(path[len("/dungeontrain/"):])
        self._send(404, b"not found", "text/plain")

    def _api_get(self, sub: str) -> None:
        if sub == "me":
            return self._json(ME)
        if sub == "tree":
            tree = json.loads(TREE.read_text())
            return self._json({"ok": True, "tree": tree, "updatedAt": int(TREE.stat().st_mtime * 1000)})
        self._json({"ok": False, "error": "not_found"}, 404)

    def _static(self, rel: str) -> None:
        if rel.endswith("/") or rel == "":
            rel += "index.html"
        target = (self.site / rel).resolve()
        if self.site not in target.parents or not target.is_file():
            return self._send(404, b"not found", "text/plain")
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        self._send(200, target.read_bytes(), ctype)

    def do_PUT(self) -> None:  # noqa: N802
        if self.path.split("?", 1)[0] != API + "tree":
            return self._json({"ok": False, "error": "not_found"}, 404)
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self._json({"ok": False, "error": "bad_size"}, 413)
        try:
            body = json.loads(self.rfile.read(length))
            save_tree(body.get("tree", body) if isinstance(body, dict) else body)
        except (ValueError, json.JSONDecodeError) as e:
            print(f"save refused: {e}", file=sys.stderr)
            return self._json({"ok": False, "error": "bad_tree", "detail": str(e)}, 400)
        print(f"saved {TREE.relative_to(REPO)}")
        self._json({"ok": True, "updatedAt": int(time.time() * 1000)})

    def do_POST(self) -> None:  # noqa: N802  (logout and other site calls: nothing to do locally)
        self._json({"ok": True})


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8834)
    ap.add_argument("--site", type=Path, default=Path.home() / "Projects/dp-relay/public/dungeontrain")
    a = ap.parse_args()
    site = a.site.expanduser().resolve()
    if not (site / "perks/index.html").is_file():
        print(f"no perks page under {site} (pass --site <dp-relay>/public/dungeontrain)", file=sys.stderr)
        return 2
    Handler.site = site
    print(f"perks editor: http://127.0.0.1:{a.port}/dungeontrain/perks/  (tree: {TREE.relative_to(REPO)})")
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
