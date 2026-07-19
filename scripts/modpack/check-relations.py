#!/usr/bin/env python3
"""Guard: every modpack Include must be declared as a dependency of the mod.

Dungeon Train ships a CurseForge *modpack* (``modpack/modpack.config.json``) and a *mod*
(``.github/workflows/release.yml``). Any mod the pack bundles as an
**Include** (an ``optional_mods`` manifest file entry) must ALSO be declared in the mod's
own CurseForge relations (``release.yml`` -> ``curseforge-dependencies``), so the mod page
advertises the very companion the pack installs. PR #390 added AppleSkin as an Include but
forgot the mod relation — this guard makes that class of drift fail in CI instead of
shipping silently.

The expected relation type is per-entry: ``optional`` by default (a recommended companion),
or ``required`` for entries carrying ``"dependency_type": "required"`` — the un-bundled
sibling mods AIN/AIS/PlayerMob/EnderChestPersistence, which DT hard-depends on. See
``expected_dependency_type``.

This is intentionally one-directional: an Include implies a mod optional dependency, but a
mod optional dependency need NOT be a pack Include (e.g. mouse-tweaks / jade are declared
relations that the pack doesn't bundle). So we only check ``optional_mods`` -> relations.

Stdlib only (no PyYAML) so it runs on a bare runner. The ``curseforge-dependencies`` value
is a YAML literal block scalar of ``slug(type)`` lines, which we extract by indentation.

Run: python3 scripts/modpack/check-relations.py   (exit 0 = consistent, 1 = drift)
"""
import argparse
import json
import re
import sys
from pathlib import Path

# scripts/modpack/check-relations.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"
DEFAULT_RELEASE_YML = REPO_ROOT / ".github" / "workflows" / "release.yml"

# A single mc-publish relation, e.g. "appleskin(optional)" or "sable(required)".
_RELATION_RE = re.compile(r"^([a-z0-9][a-z0-9-]*)\(([a-z-]+)\)$")


def extract_block_scalar(text: str, key: str) -> list[str]:
    """Return the non-blank content lines of the YAML literal block ``key: |``.

    Finds ``<indent><key>: |`` then collects every following line indented deeper than the
    key, stopping at the first non-blank line indented at or below the key (i.e. the block
    has ended). Blank lines inside the block are skipped. Returned lines are stripped, e.g.
    ``["sable(required)", "appleskin(optional)", ...]``.
    """
    key_re = re.compile(rf"^(\s*){re.escape(key)}:\s*\|")
    lines = text.splitlines()
    start = None
    key_indent = 0
    for i, line in enumerate(lines):
        m = key_re.match(line)
        if m:
            key_indent = len(m.group(1))
            start = i + 1
            break
    if start is None:
        raise ValueError(f"block scalar '{key}: |' not found")

    out: list[str] = []
    for line in lines[start:]:
        if not line.strip():
            continue  # tolerate blank lines, keep scanning
        indent = len(line) - len(line.lstrip())
        if indent <= key_indent:
            break  # dedented back to the mapping -> block ended
        out.append(line.strip())
    return out


def parse_relations(content_lines: list[str]) -> dict[str, str]:
    """Parse ``slug(type)`` relation lines into a ``{slug: type}`` dict."""
    relations: dict[str, str] = {}
    for raw in content_lines:
        m = _RELATION_RE.match(raw)
        if not m:
            raise ValueError(f"unparseable relation line: {raw!r}")
        relations[m.group(1)] = m.group(2)
    return relations


def expected_dependency_type(opt: dict) -> str:
    """Return the relation type ``opt`` must have in ``curseforge-dependencies``.

    Defaults to ``optional`` — a bundled companion the mod merely recommends. Sibling mods
    that DT genuinely hard-depends on (the un-bundled AIN/AIS/PlayerMob/EnderChestPersistence)
    carry ``"dependency_type": "required"`` instead.

    This deliberately does NOT key off the entry's ``required`` flag: that flag means
    "enabled by default in the pack", which is a different question. AppleSkin is
    ``required: true`` (ships switched on) yet is correctly only an *optional* dependency
    of the mod, because DT runs fine without it.
    """
    return opt.get("dependency_type", "optional")


def find_drift(config: dict, relations: dict[str, str]) -> list[str]:
    """Return human-readable errors for Includes not declared as mod dependencies."""
    errors: list[str] = []
    for i, opt in enumerate(config.get("optional_mods", [])):
        name = opt.get("name", f"optional_mods[{i}]")
        slug = opt.get("slug")
        if not slug:
            errors.append(
                f"{name}: optional_mods entry has no 'slug' — add its CurseForge slug so the "
                f"Include can be checked against the mod's curseforge-dependencies."
            )
            continue
        expected = expected_dependency_type(opt)
        dep_type = relations.get(slug)
        if dep_type is None:
            errors.append(
                f"{name} ({slug}): shipped as a modpack Include but NOT declared in release.yml "
                f"curseforge-dependencies. Add '{slug}({expected})'."
            )
        elif dep_type != expected:
            errors.append(
                f"{name} ({slug}): a shipped Include must be declared '{expected}', but "
                f"curseforge-dependencies has '{slug}({dep_type})'."
            )
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--release-yml", type=Path, default=DEFAULT_RELEASE_YML)
    args = parser.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8"))
    release_text = args.release_yml.read_text(encoding="utf-8")
    relations = parse_relations(extract_block_scalar(release_text, "curseforge-dependencies"))

    errors = find_drift(config, relations)
    if errors:
        print(
            "Modpack/mod dependency drift — every Include must be a mod optional dependency:",
            file=sys.stderr,
        )
        for e in errors:
            print(f"  x {e}", file=sys.stderr)
        return 1

    mods = config.get("optional_mods", [])
    n_required = sum(1 for o in mods if expected_dependency_type(o) == "required")
    print(
        f"OK: all {len(mods)} modpack Include(s) are declared as mod dependencies "
        f"({n_required} required, {len(mods) - n_required} optional)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
