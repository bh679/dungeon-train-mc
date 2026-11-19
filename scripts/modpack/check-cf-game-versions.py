#!/usr/bin/env python3
"""Guard: every CurseForge file the modpack pins is a Minecraft 1.21.1 NeoForge build.

A CurseForge project often publishes one file per loader and Minecraft version of the same
release. PlayerMob 0.121.0 is seven files, and ``file_id`` 9615248 (the 26.2 NeoForge one) was
pinned where 9615243 (1.21.1) was meant. Every CurseForge pack from v0.1835.0 to v0.1840.0
then refused to load ("playermob requires minecraft 26.2"). Nothing caught it:
``check-mod-ids.py --verify`` opens the Modrinth jar whenever an entry has one, so the
CurseForge ``file_id`` is never looked at, and the Modrinth pin was the right build.

So this reads each pinned file's record and requires its game versions to include
``REQUIRED_GAME_VERSION`` and ``REQUIRED_LOADER``.

Source: api.curseforge.com with ``CURSEFORGE_API_KEY`` (authoritative). Without the key it
falls back to the cfwidget mirror, which can only confirm what it lists: a pinned file missing
from it is a warning, not a failure (the mirror lags and does not always list every file).
Entries with ``modrinth_only`` never reach the CurseForge pack and are skipped.

Run: python3 scripts/modpack/check-cf-game-versions.py [--config path]
     (exit 0 = clean, 1 = a wrong pin, 2 = CurseForge could not be read)
"""
import argparse
import json
import sys
from pathlib import Path

import cf_api

# scripts/modpack/check-cf-game-versions.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"

REQUIRED_GAME_VERSION = "1.21.1"
REQUIRED_LOADER = "NeoForge"


def pinned_files(config: dict) -> list[tuple[str, int, int]]:
    """(label, project_id, file_id) for every entry the CurseForge pack puts on disk."""
    out = []
    for entry in [config["sable"], *config.get("optional_mods", [])]:
        if entry.get("modrinth_only"):
            continue
        pid, fid = entry.get("project_id"), entry.get("file_id")
        if pid and fid:
            out.append((entry.get("name") or entry.get("slug") or "Sable", int(pid), int(fid)))
    return out


def problem_with(versions: list[str]) -> str | None:
    """Why a file with these CurseForge game versions can't ship, or None if it can."""
    missing = [v for v in (REQUIRED_GAME_VERSION, REQUIRED_LOADER) if v not in versions]
    if not missing:
        return None
    return f"is for {', '.join(versions) or 'no listed versions'} (missing {', '.join(missing)})"


def check(files: list[tuple[str, int, int]], lookup) -> tuple[list[str], list[str]]:
    """Errors and warnings for the pinned files.

    ``lookup(project_id, file_id)`` returns the file's game versions, or None when the source
    does not list it (only the non-authoritative mirror may say that).
    """
    errors, warnings = [], []
    for label, pid, fid in files:
        versions = lookup(pid, fid)
        if versions is None:
            warnings.append(f"{label}: file {fid} of project {pid} not listed by the mirror; unchecked")
            continue
        problem = problem_with(versions)
        if problem:
            errors.append(f"{label}: CurseForge file {fid} (project {pid}) {problem}")
    return errors, warnings


def api_lookup(pid: int, fid: int) -> list[str]:
    data = cf_api.get_json(f"{cf_api.CF_API}/mods/{pid}/files/{fid}", headers=cf_api.api_headers())
    return list((data.get("data") or {}).get("gameVersions") or [])


def mirror_lookup():
    """A lookup over api.cfwidget.com, one request per project."""
    cache: dict[int, dict[int, list[str]]] = {}

    def lookup(pid: int, fid: int) -> list[str] | None:
        if pid not in cache:
            data = cf_api.get_json(f"{cf_api.CFWIDGET_API}/{pid}")
            cache[pid] = {f["id"]: list(f.get("versions") or []) for f in data.get("files") or []}
        return cache[pid].get(fid)

    return lookup


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8"))
    files = pinned_files(config)
    authoritative = cf_api.is_authoritative()
    try:
        errors, warnings = check(files, api_lookup if authoritative else mirror_lookup())
    except (RuntimeError, cf_api.AuthError) as exc:
        print(f"ERROR: could not read CurseForge: {exc}", file=sys.stderr)
        return 2

    for warn in warnings:
        print(f"WARNING: {warn}", file=sys.stderr)
    if errors:
        print(f"CurseForge pin check FAILED (need {REQUIRED_GAME_VERSION} {REQUIRED_LOADER}):",
              file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    source = "api.curseforge.com" if authoritative else "cfwidget mirror (no CURSEFORGE_API_KEY)"
    print(f"OK: {len(files) - len(warnings)} of {len(files)} CurseForge pins are "
          f"{REQUIRED_GAME_VERSION} {REQUIRED_LOADER} files ({source}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
