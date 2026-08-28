#!/usr/bin/env python3
"""Guard: the modpack must never ship a config file the mod holds to its defaults.

``modpack/overrides/`` is copied **verbatim** into every player's instance by both publish
scripts (``publish-curseforge.sh`` / ``publish-modrinth.sh`` — no generation, no filtering).
Dungeon Train separately puts a run into **Free Play** — advancements and global stats stop
persisting — when a config file it holds to its defaults has been changed; see
``src/main/java/games/brennan/dungeontrain/cheat/AisDataIntegrity.java``, which checks
``config/adventureitemstats.properties`` at every server boot.

Ship one of those files in the overrides tree and **every pack user boots into Free Play**,
silently. That is the drift this guard exists to make impossible.

There is a second, related reason to keep this tree small: launchers **replace** ``config/`` on a
pack update rather than merging it. That is how a player lost every Train Editor build and every
advancement in one click — all of it used to live under ``config/``. It now lives at the instance
root (``<instance>/dungeontrain/``; see ``games.brennan.dungeontrain.data.PlayerDataPaths``), and
the invariant is that nothing under ``overrides/`` may share a directory with player data. See
``modpack/README.md`` for the full note.

The rule is a strict allowlist: every file under ``overrides/`` must be named in ``ALLOWED``.
A new override therefore fails CI until someone adds it here — and that edit is the moment a
human confirms the file is not one the mod holds to its defaults. An allowlist (rather than a
denylist of known-dangerous names) also catches config filenames that don't exist yet.

Stdlib only, no network, so it runs on a bare runner.

Run: python3 scripts/modpack/check-overrides.py   (exit 0 = clean, 1 = drift)
"""
import argparse
import sys
from fnmatch import fnmatch
from pathlib import Path

# scripts/modpack/check-overrides.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OVERRIDES = REPO_ROOT / "modpack" / "overrides"

# Every file the pack is allowed to ship, as a path relative to overrides/ (POSIX separators).
# Entries are fnmatch patterns, so the per-locale companion lang packs don't need an edit each
# time a locale is added. ADD TO THIS LIST ONLY after confirming the file is not a config the
# mod holds to its defaults (see INTEGRITY_GOVERNED).
ALLOWED = (
    ".gitkeep",                                     # stripped at build time by the publish scripts
    "*/.gitkeep",
    "config/khi.toml",                              # Kinetic Hosting affiliate URL + banner text
    "config/smoothswapping.json",                   # tuned Smooth Swapping animation
    "resourcepacks/DungeonTrain-*-compat.zip",      # companion-mod lang overlays, one per locale
)

# Config files the mod compares against defaults and treats a deviation as Free Play, keyed to
# the Java guard that owns each. These must NEVER appear in overrides/ — not even holding the
# default values, since the pack has no way to keep them in step with a later default change.
# Source of truth for the live entry: AisDataIntegrity.FILE_NAME. Keep in sync by review.
INTEGRITY_GOVERNED = {
    "adventureitemstats.properties": "AisDataIntegrity (live)",
    "dungeontrain-server.toml": "DtConfigIntegrity (planned)",
    "dungeontrain-common.toml": "DtConfigIntegrity (planned)",
}


# Directories the pack may never write into, whatever the filename. ``overrides/`` is copied
# verbatim into every player's instance, so a file here lands in the folder Dungeon Train keeps the
# player's builds, advancements and backups in — the folder the data was MOVED to precisely because
# ``config/`` is replaced on every pack update. An allowlist entry could not make this safe.
FORBIDDEN_DIRS = ("dungeontrain",)


def in_forbidden_dir(rel_path: str) -> bool:
    """Is this overrides-relative path inside a directory the pack must never write to?"""
    head = rel_path.split("/", 1)[0]
    return head in FORBIDDEN_DIRS


def is_allowed(rel_path: str) -> bool:
    """Is this overrides-relative path covered by an ``ALLOWED`` pattern?"""
    return any(fnmatch(rel_path, pattern) for pattern in ALLOWED)


def collect(overrides_dir: Path) -> list[str]:
    """Every file under ``overrides_dir`` as sorted POSIX paths relative to it.

    A missing directory yields nothing: the publish scripts already tolerate an absent
    overrides tree (they ship an empty one), and "no overrides" is trivially clean.
    """
    if not overrides_dir.is_dir():
        return []
    return sorted(
        p.relative_to(overrides_dir).as_posix() for p in overrides_dir.rglob("*") if p.is_file()
    )


def find_drift(rel_paths: list[str]) -> list[str]:
    """Return human-readable errors for override files that are not allowlisted."""
    errors: list[str] = []
    for rel_path in rel_paths:
        if in_forbidden_dir(rel_path):
            errors.append(
                f"overrides/{rel_path}: the pack must never write into the player-data root. "
                f"<instance>/dungeontrain/ holds builds, advancements, stats and backups, and the "
                f"pack ships overrides/ verbatim — a file here would overwrite a player's own data "
                f"on update. That folder exists BECAUSE config/ gets replaced; do not repeat the "
                f"mistake one level over."
            )
            continue
        if is_allowed(rel_path):
            continue
        guard = INTEGRITY_GOVERNED.get(Path(rel_path).name)
        if guard:
            errors.append(
                f"overrides/{rel_path}: the mod holds this file to its defaults — checked by "
                f"{guard} — so shipping it puts EVERY pack user into Free Play: advancements "
                f"and global stats stop persisting. Do not ship it; remove it from the tree."
            )
        else:
            errors.append(
                f"overrides/{rel_path}: new modpack override, not allowlisted. The pack copies "
                f"this file into every player's instance verbatim — confirm it is not a config "
                f"the mod holds to its defaults (see INTEGRITY_GOVERNED), then add it to ALLOWED "
                f"in scripts/modpack/check-overrides.py."
            )
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    args = parser.parse_args(argv)

    rel_paths = collect(args.overrides)
    errors = find_drift(rel_paths)
    if errors:
        print(
            "Modpack override drift — the pack ships overrides/ verbatim to every player:",
            file=sys.stderr,
        )
        for e in errors:
            print(f"  x {e}", file=sys.stderr)
        return 1

    print(f"OK: all {len(rel_paths)} modpack override file(s) are allowlisted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
