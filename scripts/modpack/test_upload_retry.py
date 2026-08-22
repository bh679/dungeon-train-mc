#!/usr/bin/env python3
"""Unit tests for lib/upload-retry.sh — the shared retrying upload helper.

Drives the bash function against a local stub HTTP server (stdlib only, matching the
other test_*.py here) so the retry policy is pinned down by behaviour rather than by
reading the script:

  * transient 5xx  -> retried, and a later success is returned
  * persistent 5xx -> retried up to the cap, then handed back to the caller
  * 4xx            -> returned immediately, NEVER retried (a bad payload stays bad;
                      this is the historic errorCode 1002 case, fixed in #757)
  * transport fail -> surfaced as 000 and retried

Run: python3 scripts/modpack/test_upload_retry.py   (or via pytest)
"""
import http.server
import os
import subprocess
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = os.path.join(HERE, "lib", "upload-retry.sh")


class _Stub(http.server.BaseHTTPRequestHandler):
    """Answers `fail_times` requests with `fail_status`, then 200."""

    fail_times = 0
    fail_status = 500
    seen = 0

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler API
        type(self).seen += 1
        self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        if type(self).seen <= type(self).fail_times:
            self.send_response(type(self).fail_status)
            self.end_headers()
            self.wfile.write(b'{"errorCode":500,"errorMessage":"unhandled exception"}')
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"id":12345}')

    def log_message(self, *_args):
        pass


def serve(fail_times, fail_status=500):
    """Start a stub on an ephemeral port. Returns (url, handler_cls, shutdown)."""
    cls = type("S", (_Stub,), {"fail_times": fail_times, "fail_status": fail_status, "seen": 0})
    srv = http.server.HTTPServer(("127.0.0.1", 0), cls)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}/", cls, srv.shutdown


def call(url, attempts=3):
    """Source the helper, call it, and return (exit_code, http_code, body, stdout)."""
    script = (
        f'source "{HELPER}"\n'
        f'upload_with_retry "test upload" -F "x=1" "{url}"\n'
        'echo "CODE=$UPLOAD_HTTP_CODE"\n'
        'echo "BODY=$UPLOAD_BODY"\n'
    )
    env = {**os.environ, "UPLOAD_RETRY_ATTEMPTS": str(attempts), "UPLOAD_RETRY_DELAYS": "0 0 0"}
    p = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                       capture_output=True, text=True, env=env)
    out = dict(
        line.split("=", 1) for line in p.stdout.splitlines() if "=" in line and
        line.split("=", 1)[0] in ("CODE", "BODY")
    )
    return p.returncode, out.get("CODE"), out.get("BODY"), p.stdout


def test_retries_transient_5xx_then_succeeds():
    url, cls, stop = serve(fail_times=2)
    try:
        rc, code, body, _ = call(url)
    finally:
        stop()
    assert rc == 0, rc
    assert code == "200", code
    assert body == '{"id":12345}', body
    assert cls.seen == 3, f"expected 3 requests (2 failures + 1 success), got {cls.seen}"


def test_gives_up_after_attempt_cap():
    url, cls, stop = serve(fail_times=99)
    try:
        rc, code, _, stdout = call(url, attempts=3)
    finally:
        stop()
    assert rc == 0, rc                       # helper reports; the caller decides to fail
    assert code == "500", code
    assert cls.seen == 3, f"expected exactly 3 attempts, got {cls.seen}"
    assert "giving up" in stdout, f"exhaustion must be announced, got: {stdout!r}"
    assert stdout.count("retrying in") == 2, f"expected 2 retry notices, got: {stdout!r}"


def test_does_not_retry_4xx():
    url, cls, stop = serve(fail_times=99, fail_status=400)
    try:
        rc, code, _, _ = call(url)
    finally:
        stop()
    assert rc == 0, rc
    assert code == "400", code
    assert cls.seen == 1, f"4xx must not be retried, but saw {cls.seen} requests"


def test_transport_failure_surfaces_as_000():
    # Port 9 (discard) refuses connections -> curl exits non-zero, helper reports 000.
    rc, code, _, _ = call("http://127.0.0.1:9/", attempts=2)
    assert rc == 0, rc
    assert code == "000", code


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    raise SystemExit(1 if failures else 0)
