#!/usr/bin/env python3
"""Unit tests for sync-highlights-webhook.py (the #highlights webhook → the relay).

Runnable directly or by pytest. Local-only, like the other scripts/relay/test_*.py.
"""
import contextlib
import importlib.util
import io
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "sync-highlights-webhook.py")
_spec = importlib.util.spec_from_file_location("sync_highlights_webhook", SCRIPT)
sh = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sh)

HOOK = "https://discord.com/api/webhooks/123/abc_DEF-ghi"
CAP = "secret-cap"


class Relay:
    """A stub relay admin endpoint recording what it was sent."""

    def __init__(self, status=200):
        self.calls = []
        relay = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                relay.calls.append((self.path, body))
                out = json.dumps({"ok": True, "stored": bool(body.get("url"))}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_port}/{CAP}"

    def close(self):
        self.server.shutdown()


@contextlib.contextmanager
def env(**values):
    saved = {k: os.environ.get(k) for k in (*sh.ENVS, sh.WEBHOOK_ENV)}
    for k in saved:
        os.environ.pop(k, None)
    os.environ.update({k: v for k, v in values.items() if v is not None})
    home = sh.HOME_FILE
    sh.HOME_FILE = sh.Path("/nonexistent/relay-admin-url.txt")
    try:
        yield
    finally:
        sh.HOME_FILE = home
        for k, v in saved.items():
            os.environ.pop(k, None)
            if v is not None:
                os.environ[k] = v


def run(argv=()):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = sh.main(list(argv))
    return code, out.getvalue() + err.getvalue()


def test_posts_the_webhook_and_never_prints_it():
    relay = Relay()
    try:
        with env(DUNGEONTRAIN_RELAY_ADMIN_BASE=relay.base, DISCORD_HIGHLIGHTS_WEBHOOK_URL=HOOK):
            code, text = run()
        assert code == 0
        assert relay.calls == [(f"/{CAP}/socials/milestones/discord-webhook", {"url": HOOK})]
        assert "stored" in text
        assert "abc_DEF" not in text and CAP not in text
    finally:
        relay.close()


def test_clear_sends_empty():
    relay = Relay()
    try:
        with env(DUNGEONTRAIN_RELAY_ADMIN_BASE=relay.base):
            code, text = run(["--clear"])
        assert code == 0
        assert relay.calls[0][1] == {"url": ""}
        assert "cleared" in text
    finally:
        relay.close()


def test_missing_secret_or_base_skips_green():
    with env(DUNGEONTRAIN_RELAY_ADMIN_BASE="http://127.0.0.1:1/x"):
        code, text = run()
    assert code == 0 and "::warning::" in text
    with env(DISCORD_HIGHLIGHTS_WEBHOOK_URL=HOOK):
        code, text = run()
    assert code == 0 and "::warning::" in text and "abc_DEF" not in text


def test_non_discord_url_refused_unsent():
    relay = Relay()
    try:
        with env(DUNGEONTRAIN_RELAY_ADMIN_BASE=relay.base, DISCORD_HIGHLIGHTS_WEBHOOK_URL="https://evil.example/api/webhooks/1/a"):
            code, text = run()
        assert code == 2 and relay.calls == [] and "evil" not in text
    finally:
        relay.close()


def test_http_error_reports_status_without_the_url():
    relay = Relay(status=400)
    try:
        with env(DUNGEONTRAIN_RELAY_ADMIN_BASE=relay.base, DISCORD_HIGHLIGHTS_WEBHOOK_URL=HOOK):
            code, text = run()
        assert code == 1 and "HTTP 400" in text
        assert CAP not in text and "abc_DEF" not in text
    finally:
        relay.close()


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
