#!/usr/bin/env python3
"""Guard the dependency version pins: gradle.properties <-> mod dependency <-> modpack pins.

Two different contracts are checked here, because Sable and the sibling mods are pinned for
different reasons.

SABLE — exact equality.
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

SIBLING MODS — a floor, not equality.
AIN / AIS / PlayerMob / EnderChestPersistence are un-bundled required downloads. DT declares a
MINIMUM version for each (``<mod>_min_version`` in gradle.properties, rendered into
neoforge.mods.toml as ``[x,)``), and each modpack pins one specific build of it. The condition
for "the pack actually loads" is therefore:

    modpack.config.json optional_mods[].version  >=  gradle.properties <gradle_property>

Equality would be wrong here and would fail constantly: the auto-release cascade bumps the
sibling ``<mod>_version`` values every tick, and the modpack's pin is refreshed on a slower,
human cadence. The floor is what must hold. Entries opt in by carrying a ``gradle_property``
field naming the floor they answer to; entries without one (third-party companions like
AppleSkin) are skipped.

This is a stdlib-only CI guard (mirrors ``check-relations.py``). Read-only; on any mismatch it
prints a clear message and exits non-zero.

Usage:
  python3 scripts/modpack/check-pins.py
  python3 scripts/modpack/check-pins.py --gradle-properties path --config path
"""
import argparse
import json
import sys
from pathlib import Path

# scripts/modpack/check-pins.py -> repo root is two levels up.
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


def parse_semver(value: str) -> tuple[int, ...] | None:
    """Parse ``X.Y.Z`` into a comparable tuple, or None if it isn't strict semver.

    Mirrors ``scripts/auto-release/siblings.py:parse_semver`` — the sibling versions this
    compares are written by that module, so the two must agree on what parses.
    """
    if not isinstance(value, str):
        return None
    parts = semver_of(value).split(".")
    if len(parts) != 3:
        return None
    try:
        return tuple(int(p) for p in parts)
    except ValueError:
        return None


def check_modpack_lag(gradle_props: dict[str, str], config: dict) -> list[str]:
    """Return WARNINGS where the modpacks pin a sibling older than DT builds against.

    This is not an error and must never fail CI. The gap is legitimate and usually temporary:
    the auto-release cascade bumps ``<mod>_version`` ~22 times per release cycle while the
    modpack pin moves on a human cadence, and failing on each of those would make the guard
    noise everyone learns to ignore.

    What it catches is the assumption that bumping a sibling's version ships it to everyone.
    That was true while the siblings were jarJar'd into DT's jar; now that they are external
    downloads, modpack players get exactly the pinned build. PR #796 bumped AdventureItemStats
    to 0.7.0 for an armor-cap fix and announced it as shipped, while both modpacks still pinned
    0.6.0 — this is the check that surfaces that at PR time.

    Contrast with ``check_sibling_floors``, which IS an error: a pin below the floor ships a
    build the mod refuses to load against.
    """
    warnings: list[str] = []
    for i, opt in enumerate(config.get("optional_mods", [])):
        prop_key = opt.get("gradle_property")
        if not prop_key:
            continue
        # "playermob_min_version" -> "playermob_version": the build-against counterpart.
        build_key = prop_key.replace("_min_version", "_version")
        build_raw = gradle_props.get(build_key)
        pinned_raw = opt.get("version")
        if not build_raw or not pinned_raw:
            continue  # missing data is the floor check's job to report

        build = parse_semver(build_raw)
        pinned = parse_semver(pinned_raw)
        if build is None or pinned is None:
            continue

        if pinned < build:
            name = opt.get("name", f"optional_mods[{i}]")
            warnings.append(
                f"{name}: the modpacks pin {pinned_raw} but DT builds against {build_raw} "
                f"({build_key}). Modpack players will NOT get anything added since {pinned_raw}. "
                f"Refresh this entry's version + file_id + modrinth_version if that change was "
                f"meant to reach them."
            )
    return warnings


def check_sibling_floors(gradle_props: dict[str, str], config: dict) -> list[str]:
    """Return errors where a modpack-pinned sibling is OLDER than the floor DT declares.

    Only ``optional_mods`` entries carrying a ``gradle_property`` field participate; that
    field names the ``<mod>_min_version`` key in gradle.properties the pin must clear.
    """
    errors: list[str] = []
    for i, opt in enumerate(config.get("optional_mods", [])):
        prop_key = opt.get("gradle_property")
        if not prop_key:
            continue  # third-party companion — no DT-declared floor to answer to
        name = opt.get("name", f"optional_mods[{i}]")

        floor_raw = gradle_props.get(prop_key)
        if not floor_raw:
            errors.append(
                f"{name}: modpack entry references gradle_property {prop_key!r}, but "
                f"gradle.properties has no such key."
            )
            continue

        pinned_raw = opt.get("version")
        if not pinned_raw:
            errors.append(
                f"{name}: modpack entry has a gradle_property but no 'version' field, so its "
                f"pin can't be checked against the {prop_key} floor."
            )
            continue

        floor = parse_semver(floor_raw)
        pinned = parse_semver(pinned_raw)
        if floor is None or pinned is None:
            errors.append(
                f"{name}: cannot compare versions — {prop_key}={floor_raw!r}, modpack "
                f"version={pinned_raw!r}; both must be strict X.Y.Z."
            )
            continue

        if pinned < floor:
            errors.append(
                f"{name}: the modpack pins {pinned_raw} but DT requires at least {floor_raw} "
                f"({prop_key}). The pack would install a sibling too old for the mod and fail "
                f"to load. Refresh this entry's version + file_id + modrinth_version, or lower "
                f"the floor."
            )

    return errors


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

    errors = check_sable_pin(gradle_props, config) + check_sibling_floors(gradle_props, config)
    if errors:
        print("Dependency pin check FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    # Advisory only — deliberately does not affect the exit code. See check_modpack_lag.
    for warn in check_modpack_lag(gradle_props, config):
        print(f"WARNING: {warn}", file=sys.stderr)

    siblings = [o for o in config.get("optional_mods", []) if o.get("gradle_property")]
    print(
        f"OK: Sable pinned consistently — sable_version={gradle_props['sable_version']}, "
        f"sable_mod_version={gradle_props['sable_mod_version']}, "
        f"modpack sable.version={config['sable']['version']}."
    )
    for opt in siblings:
        print(
            f"OK: {opt['name']} modpack pin {opt['version']} >= floor "
            f"{gradle_props[opt['gradle_property']]} ({opt['gradle_property']})."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
