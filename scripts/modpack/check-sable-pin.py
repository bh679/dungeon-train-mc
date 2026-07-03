#!/usr/bin/env python3
"""Guard the Sable version chain: gradle.properties <-> mod dependency <-> modpack pins.

Dungeon Train is compiled and tested against exactly one Sable build. Three places must agree
on that version, or a modpack ships (or the loader accepts) an untested Sable:

  * ``gradle.properties`` ``sable_version``      — the Modrinth artifact coordinate DT builds
    against (e.g. ``2.0.2+mc1.21.1``). The ``+mc…`` suffix is Modrinth's, not the mod's.
  * ``gradle.properties`` ``sable_mod_version``  — the bare modId version Sable declares in its
    own ``neoforge.mods.toml`` (e.g. ``2.0.2``). DT's ``neoforge.mods.toml`` renders its Sable
    dependency range as ``[${sable_mod_version}]`` — an EXACT lock — so it must equal the
    leading semver of ``sable_version`` (everything before the first ``+``).
  * ``modpack/modpack.config.json`` ``sable.version`` — the human-readable pin both modpacks
    carry; must equal ``sable_version`` verbatim (the ``file_id`` / ``modrinth_version`` next to
    it can't be derived from a version string, so they stay human-maintained — this guard just
    stops the *version* fields from drifting, which is the mistake that ships an old Sable).

This is a stdlib-only CI guard (mirrors ``check-relations.py``). Read-only; on any mismatch it
prints a clear message and exits non-zero.

Usage:
  python3 scripts/modpack/check-sable-pin.py
  python3 scripts/modpack/check-sable-pin.py --gradle-properties path --config path
"""
import argparse
import json
import sys
from pathlib import Path

# scripts/modpack/check-sable-pin.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_GRADLE_PROPERTIES = REPO_ROOT / "gradle.properties"
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"


def parse_gradle_properties(path: Path) -> dict[str, str]:
    """Parse a ``key=value`` ``.properties`` file, skipping comments and blanks."""
    if not path.is_file():
        raise FileNotFoundError(f"gradle.properties not found at {path}")
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        props[key.strip()] = value.strip()
    return props


def semver_of(sable_version: str) -> str:
    """The leading semver of a Modrinth coordinate: strip the first ``+…`` build suffix."""
    return sable_version.split("+", 1)[0]


def check_sable_pin(gradle_props: dict[str, str], config: dict) -> list[str]:
    """Return a list of human-readable mismatch errors (empty == all consistent)."""
    errors: list[str] = []

    sable_version = gradle_props.get("sable_version")
    sable_mod_version = gradle_props.get("sable_mod_version")
    if not sable_version:
        errors.append("gradle.properties is missing sable_version")
    if not sable_mod_version:
        errors.append("gradle.properties is missing sable_mod_version")
    # Can't cross-check further without both gradle values.
    if errors:
        return errors

    expected_mod_version = semver_of(sable_version)
    if sable_mod_version != expected_mod_version:
        errors.append(
            f"sable_mod_version ({sable_mod_version!r}) must equal the leading semver of "
            f"sable_version ({sable_version!r} -> {expected_mod_version!r}). "
            f"DT's neoforge.mods.toml locks Sable to [{sable_mod_version}]; a mismatch would "
            f"reject the Sable the pack ships."
        )

    sable_cfg = config.get("sable")
    if not isinstance(sable_cfg, dict):
        errors.append("modpack.config.json is missing the 'sable' object")
    else:
        cfg_version = sable_cfg.get("version")
        if cfg_version != sable_version:
            errors.append(
                f"modpack.config.json sable.version ({cfg_version!r}) must equal "
                f"gradle.properties sable_version ({sable_version!r}); otherwise the modpack "
                f"pins a different Sable than DT is built against."
            )

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-properties", type=Path, default=DEFAULT_GRADLE_PROPERTIES)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args(argv)

    if not args.config.is_file():
        print(f"ERROR: modpack config not found at {args.config}", file=sys.stderr)
        return 2
    gradle_props = parse_gradle_properties(args.gradle_properties)
    config = json.loads(args.config.read_text(encoding="utf-8"))

    errors = check_sable_pin(gradle_props, config)
    if errors:
        print("Sable version pin check FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(
        f"OK: Sable pinned consistently — sable_version={gradle_props['sable_version']}, "
        f"sable_mod_version={gradle_props['sable_mod_version']}, "
        f"modpack sable.version={config['sable']['version']}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
