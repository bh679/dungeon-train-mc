#!/usr/bin/env python3
"""Shared CurseForge read helpers for the modpack scripts.

Two scripts need to read CurseForge: reconcile.py (did the pack version publish?) and
wait-for-approval.py (did the mod file get approved?). Both face the same two facts, so
the helpers live here rather than being duplicated:

  * CurseForge has no keyless official read API. api.curseforge.com needs
    CURSEFORGE_API_KEY. The upload credential in CURSEFORGE_TOKEN does NOT work for reads.
  * Without that key we fall back to api.cfwidget.com, a public mirror that CACHES. It
    observably lags by many minutes, so it can confirm a thing exists but can never prove
    a thing is absent. Callers must decide what a negative result from it means.

Stdlib only, matching the other scripts here.
"""
import json
import os
import time
import urllib.error
import urllib.request

USER_AGENT = "dungeon-train-modpack-reconcile"

CF_API = "https://api.curseforge.com/v1"
CFWIDGET_API = "https://api.cfwidget.com"


def get_json(url, headers=None, retries=3, backoff=2.0):
    """GET + parse JSON with simple retry/backoff."""
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as exc:
            last = exc
            if attempt < retries:
                time.sleep(backoff * attempt)
    raise RuntimeError(f"GET {url} failed after {retries} attempts: {last}")


def is_authoritative():
    """True when we can read CurseForge's own API rather than the cfwidget mirror.

    Only an authoritative source can prove something is ABSENT. cfwidget caches, and
    observably lags — during the 2026-08-22 live test it still reported 260 files /
    v0.592.0 while curseforge.com already listed 261 / v0.613.0.
    """
    return bool(os.environ.get("CURSEFORGE_API_KEY"))


def api_headers():
    """Headers for an authoritative api.curseforge.com read. Empty when no key is set."""
    key = os.environ.get("CURSEFORGE_API_KEY")
    return {"x-api-key": key, "Accept": "application/json"} if key else {}
