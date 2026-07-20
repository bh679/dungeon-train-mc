#!/usr/bin/env python3
"""Render a CurseForge modpack ``manifest.json`` for Dungeon Train.

A CurseForge modpack manifest lists the Minecraft version, the modloader, and an
explicit set of CurseForge mod files (by ``projectID`` + ``fileID``). Dungeon Train
jarJars only DiscordPresence + joml-primitives *inside* its own jar; the sibling mods
AIN/AIS/PlayerMob/EnderChestPersistence are un-bundled required downloads (so their own
project pages get credited), which means the pack must list them explicitly:

  * Dungeon Train — project from ``modpack.config.json``, file ID passed in per release
    (the freshly uploaded CurseForge file, surfaced by mc-publish in ``release.yml``).
  * Sable — an un-bundled runtime dep, *pinned* in ``modpack.config.json`` to the
    exact version DT is built against (PolyForm Shield forbids bundling it).
  * Each ``optional_mods`` entry — including the four siblings, which carry
    ``required: true`` so the pack ships them switched ON (a CurseForge ``required:false``
    entry ships a mod *disabled*, which for a hard dependency would break the pack).

The Minecraft + NeoForge versions are read from ``gradle.properties`` so they never drift
from the shipped jar. Read-only and side-effect free except for the rendered manifest,
which goes to stdout (or ``--output``).

Usage:
  python3 scripts/modpack/build-manifest.py --dt-file-id 8123456 --version 0.293.0
  python3 scripts/modpack/build-manifest.py --dt-file-id 8123456 --version v0.293.0 \\
      --output /tmp/manifest.json
"""
import argparse
import json
import sys
from pathlib import Path

# scripts/modpack/build-manifest.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"
DEFAULT_GRADLE_PROPERTIES = REPO_ROOT / "gradle.properties"


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


def load_config(path: Path) -> dict:
    """Load and validate ``modpack.config.json``."""
    if not path.is_file():
        raise FileNotFoundError(f"modpack config not found at {path}")
    config = json.loads(path.read_text(encoding="utf-8"))
    required = ("name", "author", "dt_project_id", "sable")
    missing = [k for k in required if k not in config]
    if missing:
        raise ValueError(f"{path} is missing required keys: {', '.join(missing)}")
    sable_required = ("project_id", "file_id", "version")
    sable_missing = [k for k in sable_required if k not in config["sable"]]
    if sable_missing:
        raise ValueError(f"{path} sable block is missing keys: {', '.join(sable_missing)}")
    # optional_mods is optional; when present each entry must carry project_id + file_id.
    # Each becomes a CurseForge manifest file whose "required" flag is taken from the entry's
    # own "required" field (default False): True => bundled & ENABLED by default; False =>
    # bundled but DISABLED by default (CurseForge's opt-in "Include"). See modpack/README.md.
    for i, opt in enumerate(config.get("optional_mods", [])):
        opt_missing = [k for k in ("project_id", "file_id") if k not in opt]
        if opt_missing:
            raise ValueError(
                f"{path} optional_mods[{i}] is missing keys: {', '.join(opt_missing)}"
            )
        if "required" in opt and not isinstance(opt["required"], bool):
            raise ValueError(
                f"{path} optional_mods[{i}] 'required' must be a boolean, got {opt['required']!r}"
            )
    return config


def build_manifest(
    *, dt_file_id: int, version: str, config: dict, gradle_props: dict[str, str]
) -> dict:
    """Assemble the CurseForge modpack manifest dict."""
    mc_version = gradle_props.get("minecraft_version")
    neo_version = gradle_props.get("neo_version")
    if not mc_version:
        raise ValueError("minecraft_version missing from gradle.properties")
    if not neo_version:
        raise ValueError("neo_version missing from gradle.properties")
    if dt_file_id <= 0:
        raise ValueError(f"dt_file_id must be a positive CurseForge file ID, got {dt_file_id}")

    # Tolerate a leading "v" so the caller can pass the release tag verbatim.
    pack_version = version[1:] if version.startswith("v") else version
    if not pack_version:
        raise ValueError("version must be non-empty")

    sable = config["sable"]
    files = [
        {"projectID": int(config["dt_project_id"]), "fileID": dt_file_id, "required": True},
        {"projectID": int(sable["project_id"]), "fileID": int(sable["file_id"]), "required": True},
    ]
    # Additional bundled mods, each carrying its own "required" flag (default False):
    #   required=True  → installed & ENABLED by default (QoL/perf/cosmetic companions:
    #                    AppleSkin, FerriteCore, ModernFix, Advancement Plaques).
    #   required=False → bundled but DISABLED by default; the CurseForge launcher offers them
    #                    as opt-in at install (Mouse Tweaks, Jade, Distant Horizons, Tectonic,
    #                    Lithostitched). NOTE: in the CurseForge app required=False means the
    #                    mod ships OFF — see modpack/README.md. Pins maintained like Sable.
    for opt in config.get("optional_mods", []):
        files.append(
            {
                "projectID": int(opt["project_id"]),
                "fileID": int(opt["file_id"]),
                "required": bool(opt.get("required", False)),
            }
        )
    return {
        "minecraft": {
            "version": mc_version,
            "modLoaders": [{"id": f"neoforge-{neo_version}", "primary": True}],
        },
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": config["name"],
        "version": pack_version,
        "author": config["author"],
        "files": files,
        "overrides": "overrides",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dt-file-id", type=int, required=True,
        help="CurseForge file ID of the Dungeon Train jar uploaded by this release.",
    )
    parser.add_argument(
        "--version", required=True,
        help="Pack version (the release version, e.g. 0.293.0; a leading 'v' is stripped).",
    )
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--gradle-properties", type=Path, default=DEFAULT_GRADLE_PROPERTIES)
    parser.add_argument(
        "--output", type=Path, default=None,
        help="Write manifest here instead of stdout.",
    )
    args = parser.parse_args(argv)

    config = load_config(args.config)
    gradle_props = parse_gradle_properties(args.gradle_properties)
    manifest = build_manifest(
        dt_file_id=args.dt_file_id,
        version=args.version,
        config=config,
        gradle_props=gradle_props,
    )

    rendered = json.dumps(manifest, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
        print(f"Wrote modpack manifest -> {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
