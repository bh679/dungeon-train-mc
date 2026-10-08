#!/usr/bin/env python3
"""Local advancement editor: the page reads the working tree and "Save to repo" writes back into it;
"Save & commit" also commits and pushes just what it wrote (``commit.py``).

    python3 scripts/advancements/editor/serve.py        # → http://127.0.0.1:8833

Binds to 127.0.0.1 only. Every page load rebuilds the bundle from the files as they are now, so
after a save (or a ``git checkout``) a reload shows the result. Review a save with ``git diff``
and commit it yourself, like any other edit.
"""

from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from apply import ApplyError, apply_changes
from commit import CommitError, save_and_commit
from build import build_bundle, more_icons, page_html

MAX_BODY = 2 * 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    server_version = "DTAdvancementEditor/1.0"

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, status: int = 200) -> None:
        self._send(status, json.dumps(payload).encode(), "application/json; charset=utf-8")

    def do_GET(self) -> None:  # noqa: N802 (http.server's naming)
        if self.path.split("?")[0] == "/api/icons":
            bundle, _ = build_bundle()
            self._json(more_icons(bundle))
            return
        if self.path.split("?")[0] not in ("/", "/index.html"):
            self._send(404, b"not found", "text/plain")
            return
        bundle, missing = build_bundle()
        if missing:
            self.log_message("icons not found: %s", ", ".join(sorted(set(missing))))
        self._send(200, page_html(bundle, "local").encode(), "text/html; charset=utf-8")

    def do_POST(self) -> None:  # noqa: N802
        if self.path not in ("/api/apply", "/api/commit"):
            self._send(404, b"not found", "text/plain")
            return
        # Same-origin only: a page on another site must not be able to write the working tree.
        origin = self.headers.get("Origin")
        host = self.headers.get("Host", "")
        if origin and origin not in (f"http://{host}",):
            self._json({"error": "cross-origin request refused"}, 403)
            return
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= MAX_BODY:
            self._json({"error": "empty or oversized body"}, 400)
            return
        try:
            changes = json.loads(self.rfile.read(length))
            if self.path == "/api/commit":
                result = save_and_commit(changes)
                self.log_message("save & commit: %s", result.message)
                self._json({"written": result.written, "todo": result.todo, "committed": result.committed,
                            "message": result.message, "commit": result.commit, "version": result.version})
                return
            report = apply_changes(changes)
        except (ApplyError, CommitError, json.JSONDecodeError) as exc:
            self._json({"error": str(exc)}, 400)
            return
        self.log_message("saved %d file(s)", len(report.written))
        self._json({"written": report.written, "todo": report.todo})


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Serve the advancement editor on localhost.")
    ap.add_argument("--port", type=int, default=8833)
    args = ap.parse_args(argv)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Advancement editor on http://127.0.0.1:{args.port}  (Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
