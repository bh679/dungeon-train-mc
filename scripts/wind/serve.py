#!/usr/bin/env python3
"""Train wind tuner: graphs the wind profile against distance and time, and saves your edits.

    python3 scripts/wind/serve.py          # opens http://127.0.0.1:8796

Serves the static tuner in ``web/`` plus a tiny JSON API:

    GET  /api/profile   src/main/resources/data/dungeontrain/wind/profile.json
    PUT  /api/profile   validate + write it back (pretty-printed), so the edit lands in git diff
    GET  /api/golden    scripts/wind/golden.json (parity vectors written by WindFieldTest)
    GET  /api/bands     scripts/wind/bands.json (band edges from `/dungeontrain debug wind export`)

To see an edit in game without rebuilding, save here and run `/dungeontrain debug wind reload` in a dev
client: the dev run reads the profile straight from src/main/resources.

Stdlib only and bound to 127.0.0.1: it writes your working tree, so it must not be reachable from the
network.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
WEB_DIR = HERE / "web"
PROFILE = ROOT / "src/main/resources/data/dungeontrain/wind/profile.json"
GOLDEN = HERE / "golden.json"
BANDS = HERE / "bands.json"

STAGE_FIELDS = ("at", "base", "flutter", "gustsPerMin", "gustStrength", "gustTicks",
                "gustVeer", "wander", "wanderSeconds")
MULT_FIELDS = ("player", "mob", "item", "projectile", "xp", "other")
MAX_BODY = 256 * 1024


def _number(obj: dict, key: str, where: str) -> float:
    value = obj.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{where}.{key} must be a number")
    if value != value or value in (float("inf"), float("-inf")):
        raise ValueError(f"{where}.{key} must be finite")
    return float(value)


def validate_profile(profile: object) -> dict:
    """Return a clean copy of the profile, or raise ValueError naming the first bad field."""
    if not isinstance(profile, dict):
        raise ValueError("profile must be an object")
    stages = profile.get("stages")
    if not isinstance(stages, list) or not stages:
        raise ValueError("profile.stages must be a non-empty list")
    mult = profile.get("multipliers")
    if not isinstance(mult, dict):
        raise ValueError("profile.multipliers must be an object")
    clean_stages = []
    for i, stage in enumerate(stages):
        if not isinstance(stage, dict):
            raise ValueError(f"stages[{i}] must be an object")
        clean = {k: _number(stage, k, f"stages[{i}]") for k in STAGE_FIELDS}
        if any(clean[k] < 0 for k in STAGE_FIELDS if k != "at"):
            raise ValueError(f"stages[{i}] values must not be negative")
        clean_stages.append(clean)
    clean_stages.sort(key=lambda s: s["at"])
    return {
        "enabled": bool(profile.get("enabled", True)),
        "headingDeg": _number(profile, "headingDeg", "profile"),
        "sneakFactor": _number(profile, "sneakFactor", "profile"),
        "visualLeadTicks": int(_number(profile, "visualLeadTicks", "profile")),
        "multipliers": {k: _number(mult, k, "multipliers") for k in MULT_FIELDS},
        "stages": clean_stages,
    }


def render_profile(profile: dict) -> str:
    """Same layout as the committed file: one line per stage so diffs stay readable."""
    head = {k: v for k, v in profile.items() if k != "stages"}
    lines = ["{"]
    for key, value in head.items():
        lines.append(f'  "{key}": {json.dumps(value)},')
    lines.append('  "stages": [')
    rows = [f"    {json.dumps(stage)}" for stage in profile["stages"]]
    lines.append(",\n".join(rows))
    lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    server_version = "WindTuner/1"

    def log_message(self, fmt: str, *args) -> None:  # quieter console
        pass

    def _send(self, status: int, body: bytes, ctype: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: object) -> None:
        self._send(status, json.dumps(payload).encode("utf-8"))

    def _file(self, path: Path) -> None:
        if not path.is_file():
            self._json(404, {"error": f"{path.name} not found"})
            return
        self._send(200, path.read_bytes())

    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        route = self.path.split("?", 1)[0]
        if route == "/api/profile":
            self._file(PROFILE)
        elif route == "/api/golden":
            self._file(GOLDEN)
        elif route == "/api/bands":
            self._file(BANDS)
        else:
            name = "index.html" if route in ("/", "") else route.lstrip("/")
            target = (WEB_DIR / name).resolve()
            if WEB_DIR not in target.parents or not target.is_file():
                self._json(404, {"error": "not found"})
                return
            ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
            if target.suffix == ".js":
                ctype = "text/javascript"
            self._send(200, target.read_bytes(), ctype)

    def do_PUT(self) -> None:  # noqa: N802
        if self.path != "/api/profile":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self._json(400, {"error": "body missing or too large"})
            return
        try:
            profile = validate_profile(json.loads(self.rfile.read(length)))
        except (ValueError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
            return
        PROFILE.write_text(render_profile(profile), encoding="utf-8")
        self._json(200, {"ok": True, "path": str(PROFILE.relative_to(ROOT))})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8796)
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{args.port}/"
    print(f"Wind tuner on {url}  (Ctrl+C to stop)")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
