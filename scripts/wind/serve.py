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

CURVE_NAMES = ("base", "flutter", "gustsPerMin", "gustStrength", "gustSeconds", "gustVeer", "wander",
               "wanderSeconds")
WEIGHT_FIELDS = ("refMass", "exponent", "maxBoost", "grip")
MULT_FIELDS = ("player", "mob", "item", "projectile", "xp", "other")
MAX_BODY = 256 * 1024

#: Vanilla sounds the wind uses (client/wind/WindSound.java), for the tuner's sound preview. These are Mojang
#: assets: never committed, read at request time from the Minecraft asset cache gradle already downloaded.
SOUNDS = {
    "elytra_loop": "minecraft/sounds/item/elytra/elytra_loop.ogg",
    "idle_air1": "minecraft/sounds/mob/breeze/idle_air1.ogg",
    "idle_air2": "minecraft/sounds/mob/breeze/idle_air2.ogg",
    "idle_air3": "minecraft/sounds/mob/breeze/idle_air3.ogg",
    "idle_air4": "minecraft/sounds/mob/breeze/idle_air4.ogg",
}
ASSET_ROOTS = [Path.home() / ".gradle/caches/neoformruntime/assets", Path.home() / ".minecraft/assets"]
#: Asset index of Minecraft 1.21.1 first, then whatever else is there.
PREFERRED_INDEX = "17.json"


def sound_bytes(name: str) -> bytes | None:
    """The .ogg for a wind sound from the local asset cache, or None when it isn't there."""
    key = SOUNDS.get(name)
    if key is None:
        return None
    for root in ASSET_ROOTS:
        indexes = sorted((root / "indexes").glob("*.json"), key=lambda f: f.name != PREFERRED_INDEX)
        for index in indexes:
            try:
                objects = json.loads(index.read_text(encoding="utf-8")).get("objects", {})
            except (OSError, ValueError):
                continue
            entry = objects.get(key)
            if not entry:
                continue
            h = entry["hash"]
            path = root / "objects" / h[:2] / h
            if path.is_file():
                return path.read_bytes()
    return None


def _number(obj: dict, key: str, where: str) -> float:
    value = obj.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{where}.{key} must be a number")
    if value != value or value in (float("inf"), float("-inf")):
        raise ValueError(f"{where}.{key} must be finite")
    return float(value)


def _curve(raw: object, name: str) -> dict:
    """A clean curve: {"kind": "log", scale, ref} or {"kind": "approach", start, max, halfLife}."""
    if not isinstance(raw, dict):
        raise ValueError(f"curves.{name} must be an object")
    kind = raw.get("kind")
    where = f"curves.{name}"
    if kind == "log":
        ref = _number(raw, "ref", where)
        if ref <= 0:
            raise ValueError(f"{where}.ref must be > 0")
        return {"kind": "log", "scale": _number(raw, "scale", where), "ref": ref}
    if kind == "approach":
        half = _number(raw, "halfLife", where)
        if half <= 0:
            raise ValueError(f"{where}.halfLife must be > 0")
        return {"kind": "approach", "start": _number(raw, "start", where),
                "max": _number(raw, "max", where), "halfLife": half}
    raise ValueError(f'{where}.kind must be "log" or "approach"')


def validate_profile(profile: object) -> dict:
    """Return a clean copy of the profile, or raise ValueError naming the first bad field."""
    if not isinstance(profile, dict):
        raise ValueError("profile must be an object")
    curves = profile.get("curves")
    if not isinstance(curves, dict):
        raise ValueError("profile.curves must be an object")
    mult = profile.get("multipliers")
    if not isinstance(mult, dict):
        raise ValueError("profile.multipliers must be an object")
    return {
        "enabled": bool(profile.get("enabled", True)),
        "headingDeg": _number(profile, "headingDeg", "profile"),
        "sneakFactor": _number(profile, "sneakFactor", "profile"),
        "airFactor": _number(profile, "airFactor", "profile") if "airFactor" in profile else 1.0,
        "visualLeadTicks": int(_number(profile, "visualLeadTicks", "profile")),
        "multipliers": {k: _number(mult, k, "multipliers") for k in MULT_FIELDS},
        "weight": {k: _number(profile.get("weight") or {}, k, "weight") for k in WEIGHT_FIELDS},
        "curves": {name: _curve(curves.get(name), name) for name in CURVE_NAMES},
    }


def render_profile(profile: dict) -> str:
    """Same layout as the committed file: one line per curve so diffs stay readable."""
    head = {k: v for k, v in profile.items() if k != "curves"}
    lines = ["{"]
    for key, value in head.items():
        lines.append(f'  "{key}": {json.dumps(value)},')
    lines.append('  "curves": {')
    rows = [f'    "{name}": {json.dumps(curve)}' for name, curve in profile["curves"].items()]
    lines.append(",\n".join(rows))
    lines.append("  }")
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
        elif route.startswith("/api/sound/"):
            data = sound_bytes(route.rsplit("/", 1)[-1])
            if data is None:
                self._json(404, {"error": "sound not in the local Minecraft asset cache (run ./gradlew runClient once)"})
            else:
                self._send(200, data, "audio/ogg")
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
