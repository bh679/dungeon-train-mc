#!/usr/bin/env python3
"""Render a Modrinth modpack index (``modrinth.index.json``) for Dungeon Train.

This is the Modrinth sibling of ``build-manifest.py`` (CurseForge). A Modrinth modpack
(``.mrpack``) is a zip of ``modrinth.index.json`` + ``overrides/``. Unlike CurseForge —
which references mods by ``projectID`` + ``fileID`` — Modrinth references each file by its
**download URL + sha1 + sha512 + fileSize**. Those are resolved here from the Modrinth API
at build time from the *pinned Modrinth version id* of each mod (``modpack.config.json``),
exactly mirroring the builds the CurseForge pack ships.

Dungeon Train jarJars only DiscordPresence + joml-primitives *inside* its own jar; the sibling
mods AIN/AIS/PlayerMob/EnderChestPersistence are un-bundled required downloads (so their own
project pages get credited), so the pack lists:

  * Dungeon Train — Modrinth version id passed in per release (``--dt-version``); the freshly
    uploaded Modrinth version, surfaced by mc-publish (``modrinth-version``) in ``release.yml``.
  * Sable — an un-bundled runtime dep, *pinned* in ``modpack.config.json`` to the exact
    version DT is built against. Sable is on Modrinth so it is referenced by URL (no bundling,
    so its PolyForm-Shield no-redistribution clause is not engaged).
  * Each ``optional_mods`` entry — pinned by ``modrinth_version``.

The Minecraft + NeoForge versions are read from ``gradle.properties`` so they never drift from
the shipped jar. The only side effect is the rendered index, written to stdout (or ``--output``).

Per-file ``env`` (``client``/``server`` each ``required``|``optional``|``unsupported``) replaces
CurseForge's single ``required`` flag. See ``compute_env`` for the mapping; in short: the pack
mirrors the CurseForge pack (every bundled mod is present in the player's single-player-capable
*client* instance), and only the *server* side is set ``unsupported`` for genuinely client-only
mods (so e.g. AmbientSounds' ~84 MB of audio is not shipped to dedicated servers).
(DT + Sable are always ``required`` on both sides.)

Usage:
  python3 scripts/modpack/build-mrpack.py --dt-version AbCdEf12 --version 0.293.0
  python3 scripts/modpack/build-mrpack.py --dt-version AbCdEf12 --version v0.293.0 \\
      --output modrinth.index.json
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# scripts/modpack/build-mrpack.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"
DEFAULT_GRADLE_PROPERTIES = REPO_ROOT / "gradle.properties"

MODRINTH_API = "https://api.modrinth.com/v2"
# Modrinth asks for a descriptive User-Agent (project/version + contact).
USER_AGENT = "bh679/dungeon-train-mc modpack builder (brennan@brennanhatton.com)"


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
    """Load and validate ``modpack.config.json`` for Modrinth-pack rendering."""
    if not path.is_file():
        raise FileNotFoundError(f"modpack config not found at {path}")
    config = json.loads(path.read_text(encoding="utf-8"))
    required = ("name", "author", "sable")
    missing = [k for k in required if k not in config]
    if missing:
        raise ValueError(f"{path} is missing required keys: {', '.join(missing)}")
    sable_missing = [k for k in ("modrinth_project", "modrinth_version") if k not in config["sable"]]
    if sable_missing:
        raise ValueError(
            f"{path} sable block is missing Modrinth keys: {', '.join(sable_missing)}"
        )
    for i, opt in enumerate(config.get("optional_mods", [])):
        opt_missing = [k for k in ("modrinth_project", "modrinth_version") if k not in opt]
        if opt_missing:
            raise ValueError(
                f"{path} optional_mods[{i}] ({opt.get('name', '?')}) is missing Modrinth keys: "
                f"{', '.join(opt_missing)}"
            )
        if "required" in opt and not isinstance(opt["required"], bool):
            raise ValueError(
                f"{path} optional_mods[{i}] 'required' must be a boolean, got {opt['required']!r}"
            )
    return config


def _http_get_json(url: str, *, retries: int = 3, backoff: float = 2.0) -> dict:
    """GET ``url`` and parse JSON, with simple retry/backoff on transient errors."""
    last_err: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError) as err:
            last_err = err
            # 404 is terminal (bad version id) — don't burn retries on it.
            if isinstance(err, urllib.error.HTTPError) and err.code == 404:
                break
            if attempt < retries:
                time.sleep(backoff * attempt)
    raise RuntimeError(f"failed to GET {url}: {last_err}")


def compute_env(*, required: bool, client_side: str, server_side: str) -> dict[str, str]:
    """Map (our bundled/opt-in choice, mod's supported sides) -> Modrinth modpack env.

    ``desired`` is ``required`` for a bundled-enabled mod (config ``required: true``) and
    ``optional`` for a bundled-but-opt-in mod (config ``required: false``).

    * **client** is always ``desired`` — never ``unsupported``. The pack mirrors the CurseForge
      pack, which installs every bundled mod into the player's (single-player-capable) instance.
      We deliberately ignore the mod's own ``client_side`` here: several bundled libraries declare
      ``client_side=unsupported`` (e.g. Lithostitched, a worldgen lib) yet ARE needed by the
      integrated server in single-player, so excluding them would break the one-click opt-ins that
      depend on them (Tectonic needs Lithostitched). None of the curated mods crash on a client.
    * **server** respects ``server_side=unsupported`` so genuinely client-only mods (AmbientSounds'
      ~84 MB of audio, the inventory/HUD QoL mods) are skipped on dedicated servers rather than
      shipped uselessly; otherwise it is ``desired``.
    """
    desired = "required" if required else "optional"
    _ = client_side  # intentionally unused — see docstring (client is always present)
    return {
        "client": desired,
        "server": "unsupported" if server_side == "unsupported" else desired,
    }


def resolve_version(version_id: str, *, fetch=_http_get_json) -> dict:
    """Resolve one Modrinth ``version_id`` to the fields the index needs.

    Returns ``{filename, url, sha1, sha512, size, client_side, server_side}``. Fetches the
    version (primary file URL/hashes/size) and its project (client_side/server_side).
    """
    ver = fetch(f"{MODRINTH_API}/version/{version_id}")
    files = ver.get("files") or []
    if not files:
        raise ValueError(f"Modrinth version {version_id} has no files")
    primary = next((f for f in files if f.get("primary")), files[0])
    hashes = primary.get("hashes") or {}
    for h in ("sha1", "sha512"):
        if not hashes.get(h):
            raise ValueError(f"Modrinth version {version_id} file is missing {h} hash")
    project_id = ver.get("project_id")
    project = fetch(f"{MODRINTH_API}/project/{project_id}") if project_id else {}
    return {
        "filename": primary["filename"],
        "url": primary["url"],
        "sha1": hashes["sha1"],
        "sha512": hashes["sha512"],
        "size": int(primary["size"]),
        # Default to a both-sides mod when the project omits the fields.
        "client_side": project.get("client_side", "required"),
        "server_side": project.get("server_side", "required"),
    }


def resolve_files(config: dict, dt_version_id: str, *, fetch=_http_get_json) -> list[dict]:
    """Resolve every pinned mod (DT + Sable + optional_mods) to a descriptor for ``build_index``.

    Each descriptor: ``{name, required, filename, url, sha1, sha512, size, client_side,
    server_side}``. DT + Sable are forced ``required=True``; optional mods carry their config flag.
    """
    pins = [
        {"name": config.get("name", "Dungeon Train"), "version": dt_version_id, "required": True},
        {"name": "Sable", "version": config["sable"]["modrinth_version"], "required": True},
    ]
    for opt in config.get("optional_mods", []):
        pins.append(
            {
                "name": opt.get("name", "?"),
                "version": opt["modrinth_version"],
                "required": bool(opt.get("required", False)),
            }
        )
    resolved: list[dict] = []
    for pin in pins:
        meta = resolve_version(pin["version"], fetch=fetch)
        resolved.append({"name": pin["name"], "required": pin["required"], **meta})
    return resolved


def build_index(
    *, resolved_files: list[dict], version: str, config: dict, gradle_props: dict[str, str]
) -> dict:
    """Assemble the Modrinth modpack index dict (pure; no network)."""
    mc_version = gradle_props.get("minecraft_version")
    neo_version = gradle_props.get("neo_version")
    if not mc_version:
        raise ValueError("minecraft_version missing from gradle.properties")
    if not neo_version:
        raise ValueError("neo_version missing from gradle.properties")

    # Tolerate a leading "v" so the caller can pass the release tag verbatim.
    pack_version = version[1:] if version.startswith("v") else version
    if not pack_version:
        raise ValueError("version must be non-empty")
    if not resolved_files:
        raise ValueError("resolved_files is empty — nothing to put in the modpack")

    files = []
    seen_paths: set[str] = set()
    for rf in resolved_files:
        path = f"mods/{rf['filename']}"
        if path in seen_paths:
            raise ValueError(f"duplicate file path in modpack: {path}")
        seen_paths.add(path)
        files.append(
            {
                "path": path,
                "hashes": {"sha1": rf["sha1"], "sha512": rf["sha512"]},
                "env": compute_env(
                    required=rf["required"],
                    client_side=rf.get("client_side", "required"),
                    server_side=rf.get("server_side", "required"),
                ),
                "downloads": [rf["url"]],
                "fileSize": int(rf["size"]),
            }
        )

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": pack_version,
        "name": config["name"],
        "files": files,
        "dependencies": {
            "minecraft": mc_version,
            "neoforge": neo_version,
        },
    }
    summary = config.get("summary")
    if summary:
        index["summary"] = summary
    return index


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dt-version", default=None,
        help="Modrinth version id of the Dungeon Train jar uploaded by this release.",
    )
    parser.add_argument(
        "--version", default=None,
        help="Pack version (the release version, e.g. 0.293.0; a leading 'v' is stripped).",
    )
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--gradle-properties", type=Path, default=DEFAULT_GRADLE_PROPERTIES)
    parser.add_argument(
        "--output", type=Path, default=None,
        help="Write index here instead of stdout.",
    )
    parser.add_argument(
        "--check-config", action="store_true",
        help="Validate the config has Modrinth pins (project + version) on every mod, then exit. "
             "No network and no --dt-version/--version needed; for CI.",
    )
    args = parser.parse_args(argv)

    config = load_config(args.config)

    if args.check_config:
        n = len(config.get("optional_mods", []))
        print(f"OK: sable + all {n} optional_mods carry modrinth_project + modrinth_version.")
        return 0

    if not args.dt_version or not args.version:
        parser.error("--dt-version and --version are required (unless --check-config)")

    gradle_props = parse_gradle_properties(args.gradle_properties)
    resolved = resolve_files(config, args.dt_version)
    index = build_index(
        resolved_files=resolved,
        version=args.version,
        config=config,
        gradle_props=gradle_props,
    )

    rendered = json.dumps(index, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
        print(f"Wrote Modrinth modpack index -> {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
