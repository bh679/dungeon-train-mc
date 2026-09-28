#!/usr/bin/env python3
"""Local book editor — a vanilla-styled book preview you can type in, backed by the files on disk.

    python3 scripts/books-editor/serve.py          # → http://127.0.0.1:8795

Serves the static editor in ``web/`` plus a small JSON API over the three corpora under
``src/main/resources/data/dungeontrain/narratives`` (see corpus.py). Writes go straight to those
files, so an edit lands in ``git diff`` like any other change — review and commit it as normal.

Deliberately dependency-free (stdlib only) and bound to 127.0.0.1: this is a dev tool that edits your
working tree, and nothing about it should be reachable from the network.

Vanilla textures (``book.png`` and the page arrows) are NOT committed to this repo — they are Mojang
assets. They are resolved at request time from the loom ``client-extra.jar`` already in your gradle
cache, or from a dp-relay checkout if you have one; with neither, the page falls back to a CSS
parchment and CSS arrows and everything still works.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import re
import webbrowser
import zipfile
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import corpus

WEB_DIR = Path(__file__).resolve().parent / "web"

#: Vanilla textures we serve, mapped to their path inside client-extra.jar.
JAR_ASSETS = {
    "book.png": "assets/minecraft/textures/gui/book.png",
    "page_forward.png": "assets/minecraft/textures/gui/sprites/widget/page_forward.png",
    "page_forward_highlighted.png": "assets/minecraft/textures/gui/sprites/widget/page_forward_highlighted.png",
    "page_backward.png": "assets/minecraft/textures/gui/sprites/widget/page_backward.png",
    "page_backward_highlighted.png": "assets/minecraft/textures/gui/sprites/widget/page_backward_highlighted.png",
}

#: Assets that only exist in a dp-relay checkout (Monocraft is OFL-licensed; book.png is a fallback
#: source for the frame when there is no gradle cache to extract from).
RELAY_ASSETS = {
    "Monocraft.ttf": "web/assets/Monocraft.ttf",
    "book.png": "web/assets/book.png",
}

RELAY_ROOT = Path.home() / "Projects" / "dp-relay"


@lru_cache(maxsize=1)
def client_extra_jar(root: Path) -> Path | None:
    """The loom-cached vanilla client assets for the MC version this repo builds against."""
    version = None
    props = (root / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^minecraft_version=(.+)$", props, re.M)
    if match:
        version = match.group(1).strip()
    if not version:
        return None
    cache = Path.home() / ".gradle" / "caches" / "fabric-loom" / version
    if not cache.is_dir():
        return None
    jars = sorted(cache.glob("*/*/client-extra.jar"))
    return jars[0] if jars else None


def load_asset(root: Path, name: str) -> bytes | None:
    return (asset_with_source(root, name) or (None, None))[0]


def asset_with_source(root: Path, name: str):
    """Vanilla texture / font bytes plus WHERE they came from ('jar' or 'relay'), or None.

    The source matters to the page: the jar's `book.png` is the raw 256×256 sheet with the book art
    at (20,1)-(166,181), while dp-relay ships that region already cropped to 146×180. The CSS needs
    to know which one it is pointing at.
    """
    if name in JAR_ASSETS:
        jar = client_extra_jar(root)
        if jar and jar.is_file():
            try:
                with zipfile.ZipFile(jar) as zf:
                    return zf.read(JAR_ASSETS[name]), "jar"
            except (KeyError, zipfile.BadZipFile):
                pass
    relay = RELAY_ASSETS.get(name)
    if relay:
        path = RELAY_ROOT / relay
        if path.is_file():
            return path.read_bytes(), "relay"
    return None


def available_assets(root: Path) -> dict:
    """{asset name: source} for everything we can serve — the page keys its geometry off this."""
    found = {}
    for name in sorted(set(JAR_ASSETS) | set(RELAY_ASSETS)):
        resolved = asset_with_source(root, name)
        if resolved:
            found[name] = resolved[1]
    return found


class Handler(BaseHTTPRequestHandler):
    server_version = "DTBookEditor/1.0"
    root: Path = Path(".")

    # --- plumbing ------------------------------------------------------------

    def log_message(self, fmt, *args):  # quieter than the default per-request noise
        if not self.path.startswith(("/js/", "/css/", "/assets/")):
            super().log_message(fmt, *args)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        # Never cache: the whole point is that the page reflects the files as they are right now.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, payload, status: int = 200) -> None:
        self._send(status, json.dumps(payload).encode("utf-8"), "application/json; charset=utf-8")

    def _error(self, exc: Exception, status: int = 400) -> None:
        self._json({"error": str(exc)}, status)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            raise corpus.CorpusError("empty request body")
        return json.loads(self.rfile.read(length))

    @property
    def query(self) -> dict:
        return {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}

    def _need(self, key: str) -> str:
        value = self.query.get(key)
        if not value:
            raise corpus.CorpusError(f"missing '{key}'")
        return value

    # --- routes --------------------------------------------------------------

    def do_GET(self):  # noqa: N802 (stdlib naming)
        path = urlparse(self.path).path
        try:
            if path.startswith("/api/"):
                return self._api_get(path)
            if path.startswith("/assets/"):
                return self._asset(path[len("/assets/"):])
            return self._static(path)
        except corpus.CorpusError as exc:
            return self._error(exc)
        except Exception as exc:  # a dev tool: show the fault rather than dying silently
            return self._error(exc, 500)

    def do_PUT(self):  # noqa: N802
        try:
            if urlparse(self.path).path != "/api/book":
                return self._json({"error": "not found"}, 404)
            payload = self._body()
            result = corpus.write_book(
                self.root, self._need("path"), payload["data"],
                payload.get("style") or corpus.DEFAULT_STYLE,
                expected_mtime=payload.get("mtime"))
            return self._json(result)
        except corpus.CorpusError as exc:
            return self._error(exc, 409 if "changed on disk" in str(exc) else 400)
        except Exception as exc:
            return self._error(exc, 500)

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        try:
            payload = self._body()
            if path == "/api/create":
                return self._json(self._create(payload))
            if path == "/api/move":
                return self._json(self._move(payload))
            return self._json({"error": "not found"}, 404)
        except corpus.CorpusError as exc:
            return self._error(exc)
        except Exception as exc:
            return self._error(exc, 500)

    def _api_get(self, path: str):
        if path == "/api/corpus":
            return self._json({
                "books": corpus.list_books(self.root),
                "contexts": corpus.STARTING_CONTEXTS,
                "assets": available_assets(self.root),
                "repo": str(self.root),
            })
        if path == "/api/book":
            return self._json(corpus.read_book(self.root, self._need("path")))
        if path == "/api/locale":
            return self._json({
                "locale": self._need("locale"),
                "data": corpus.read_localized(self.root, self._need("path"), self._need("locale")),
            })
        return self._json({"error": "not found"}, 404)

    def _create(self, payload: dict) -> dict:
        rel = payload.get("path") or ""
        target = corpus.resolve(self.root, rel)
        if target.exists():
            raise corpus.CorpusError(f"{rel} already exists")
        return corpus.write_book(self.root, rel, payload["data"],
                                 payload.get("style") or corpus.DEFAULT_STYLE)

    def _move(self, payload: dict) -> dict:
        """Move a starting book between context folders — the folder IS its lifecycle routing."""
        source = corpus.resolve(self.root, payload.get("from") or "")
        target = corpus.resolve(self.root, payload.get("to") or "")
        if not source.is_file():
            raise corpus.CorpusError(f"no such book: {payload.get('from')}")
        if target.exists():
            raise corpus.CorpusError(f"{payload.get('to')} already exists")
        target.parent.mkdir(parents=True, exist_ok=True)
        source.rename(target)
        return {"path": payload["to"], "mtime": corpus.mtime_of(target)}

    # --- static + assets ------------------------------------------------------

    def _asset(self, name: str):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name or ""):
            return self._json({"error": "bad asset"}, 400)
        data = load_asset(self.root, name)
        if data is None:
            return self._json({"error": f"{name} unavailable"}, 404)
        return self._send(200, data, mimetypes.guess_type(name)[0] or "application/octet-stream")

    def _static(self, path: str):
        rel = "index.html" if path in ("/", "") else path.lstrip("/")
        target = (WEB_DIR / rel).resolve()
        if WEB_DIR.resolve() not in target.parents or not target.is_file():
            return self._json({"error": "not found"}, 404)
        return self._send(200, target.read_bytes(),
                          mimetypes.guess_type(target.name)[0] or "text/plain; charset=utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8795)
    parser.add_argument("--repo", type=Path, default=None, help="mod repo root (default: this checkout)")
    parser.add_argument("--no-open", action="store_true", help="don't open a browser")
    args = parser.parse_args()

    Handler.root = corpus.repo_root(args.repo) if args.repo else corpus.repo_root()
    url = f"http://127.0.0.1:{args.port}/"
    books = corpus.list_books(Handler.root)
    print(f"Dungeon Train book editor — {len(books)} books in {corpus.narratives_dir(Handler.root)}")
    missing = set(JAR_ASSETS) - set(available_assets(Handler.root))
    if missing:
        print(f"  vanilla textures unavailable ({', '.join(sorted(missing))}) — using the CSS fallback")
    print(f"  serving {url}   (ctrl-c to stop)")
    if not args.no_open:
        webbrowser.open(url)
    try:
        ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    except OSError as exc:
        raise SystemExit(f"could not listen on port {args.port} ({exc}) — try --port <other>")


if __name__ == "__main__":
    main()
