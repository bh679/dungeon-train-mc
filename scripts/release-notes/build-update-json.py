#!/usr/bin/env python3
"""Regenerate the NeoForge update-check JSON (`update.json` at the repo root).

NeoForge fetches this file (declared via `updateJSONURL` in neoforge.mods.toml,
rendered from `update_json_url` in gradle.properties) at startup and shows an
"Update available" badge on the Mods screen when the installed build is behind
`promos."<mc>-latest"`. Notification only — NeoForge never downloads anything.
Schema: https://docs.neoforged.net/docs/misc/updatechecker/

    {
      "homepage": "https://github.com/bh679/dungeon-train-mc",
      "1.21.1": { "0.485.0": "changelog text" },
      "promos": { "1.21.1-latest": "0.485.0", "1.21.1-recommended": "0.485.0" }
    }

The file must advertise the LAST RELEASED version. `mod_version` in
gradle.properties is always bumped past the release to the next dev version, so
this script is anchored to the release tag instead and is run by release.yml on
every release (manual and the auto-release cascade), then committed to main.

The update is immutable and idempotent: the existing file is read, the released
version is merged into that Minecraft version's changelog map (capped to the most
recent N versions to bound file size), and `promos` for that Minecraft version is
repointed at it. Other Minecraft versions, their promos, and `homepage` are
preserved untouched. Re-running with the same version yields the same file.

Usage:
    build-update-json.py --version 0.485.0 --mc 1.21.1 [--changelog TEXT]
                         [--homepage URL] [--file PATH] [--cap N]

Env overrides (for tests):
    UPDATE_JSON_FILE  default: update.json
"""
import argparse
import os
import sys

# Reuse the ledger module's JSON I/O + semver parse so this file matches the
# rest of the release-notes tooling (indent=2, trailing newline, strict X.Y.Z).
from changelog_io import parse_semver, read_json, write_json

DEFAULT_FILE = os.environ.get("UPDATE_JSON_FILE", "update.json")
DEFAULT_HOMEPAGE = "https://github.com/bh679/dungeon-train-mc"
DEFAULT_CAP = 25


def default_changelog(version: str) -> str:
    """Fallback blurb when no curated changelog is supplied.

    The Mods-screen badge is the point of the notifier; the per-version text is
    cosmetic, so pointing at the full GitHub release notes keeps the CI step
    decoupled from ledger state and timing.
    """
    return f"{DEFAULT_HOMEPAGE}/releases/tag/v{version}"


def _sorted_desc(versions: list[str]) -> list[str]:
    """Version strings newest-first; non-semver keys sort last, stably."""
    return sorted(versions, key=lambda v: parse_semver(v) or (-1, -1, -1), reverse=True)


def build_update_json(
    existing: dict,
    version: str,
    mc: str,
    changelog: str,
    homepage: str,
    cap: int,
) -> dict:
    """Return a NEW update-json dict with `version` merged in for `mc`.

    Pure function — `existing` is never mutated. Only the target Minecraft
    version's changelog map and promos are changed; everything else is copied
    through so multi-MC files stay intact.
    """
    if parse_semver(version) is None:
        raise ValueError(f"--version '{version}' is not a strict X.Y.Z version")
    if not mc:
        raise ValueError("--mc must be a non-empty Minecraft version")
    if cap < 1:
        raise ValueError(f"--cap must be >= 1, got {cap}")

    old_mc_map = existing.get(mc)
    if old_mc_map is not None and not isinstance(old_mc_map, dict):
        raise ValueError(f"existing['{mc}'] must be an object, got {type(old_mc_map).__name__}")

    # Merge, then keep only the most recent `cap` versions, newest-first.
    merged = {**(old_mc_map or {}), version: changelog}
    kept = _sorted_desc(list(merged))[:cap]
    new_mc_map = {v: merged[v] for v in kept}

    old_promos = existing.get("promos")
    if old_promos is not None and not isinstance(old_promos, dict):
        raise ValueError("existing['promos'] must be an object")
    new_promos = {
        **(old_promos or {}),
        f"{mc}-latest": version,
        f"{mc}-recommended": version,
    }

    # Rebuild the top-level object in a stable, readable order: homepage, each
    # Minecraft-version map (target refreshed, others preserved), then promos.
    result: dict = {"homepage": existing.get("homepage") or homepage}
    other_mc_keys = [k for k in existing if k not in ("homepage", "promos", mc)]
    for k in other_mc_keys:
        result[k] = existing[k]
    result[mc] = new_mc_map
    result["promos"] = new_promos
    return result


def load_existing(path: str) -> dict:
    """Load the current update.json, or an empty skeleton if absent/blank."""
    try:
        data = read_json(path)
    except FileNotFoundError:
        return {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return data


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Regenerate NeoForge update.json.")
    parser.add_argument("--version", required=True, help="Released version, e.g. 0.485.0 (leading 'v' allowed)")
    parser.add_argument("--mc", required=True, help="Minecraft version, e.g. 1.21.1")
    parser.add_argument("--changelog", default=None, help="Curated changelog text for this version")
    parser.add_argument("--homepage", default=DEFAULT_HOMEPAGE, help="Homepage URL (used only when the file lacks one)")
    parser.add_argument("--file", default=DEFAULT_FILE, help=f"Path to update.json (default: {DEFAULT_FILE})")
    parser.add_argument("--cap", type=int, default=DEFAULT_CAP, help=f"Max versions kept per MC (default: {DEFAULT_CAP})")
    args = parser.parse_args(argv)

    version = args.version.lstrip("v").strip()
    changelog = args.changelog if args.changelog is not None else default_changelog(version)

    try:
        existing = load_existing(args.file)
        result = build_update_json(existing, version, args.mc, changelog, args.homepage, args.cap)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    write_json(args.file, result)
    print(f"update.json → {args.mc}-latest = {version} ({args.file})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
