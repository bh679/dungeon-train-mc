#!/usr/bin/env python3
"""Shared sibling-mod helpers for the auto-release cascade.

Both should-fire.py (cadence decision) and apply-change.py (tick application)
need to know which sibling mods are behind their upstream GitHub releases.
This module is the single source of truth for that comparison.

Public surface:
  SIBLING_MODS — declared order of siblings (AIN first, then AIS, then PMOB).
  parse_semver(s) — strict X.Y.Z parse, returns (int,int,int) or None.
  read_sibling_version(key) — read X.Y.Z from gradle.properties or None.
  write_sibling_version(key, v) — rewrite the X.Y.Z line in-place.
  fetch_sibling_releases(repo, override_env) — list of version strings.
  pending_sibling_update(mode) — name of the first mode-applicable sibling
      with a newer release, or None. Errors fall through to None.

Mode constants and parse_mode() are also here so both scripts share the
same case-insensitive parsing rules.

Env overrides:
  GRADLE_PROPERTIES_FILE  default: gradle.properties
  AIN_RELEASES_OVERRIDE   default: unset; JSON array of {tagName: "v..."}
  AIS_RELEASES_OVERRIDE   default: unset; same shape
  PMOB_RELEASES_OVERRIDE  default: unset; same shape
  AUTO_RELEASE_MODE       default: always (case-insensitive)
"""
import json
import os
import subprocess
import sys

GRADLE_PROPERTIES_FILE = os.environ.get("GRADLE_PROPERTIES_FILE", "gradle.properties")

SIBLING_MODS = (
    ("AIN", "adventureitemnames_version", "bh679/adventureitemnames-mc",
     "AIN_RELEASES_OVERRIDE", "SKIP_AIN"),
    ("AIS", "adventureitemstats_version", "bh679/adventureitemstats-mc",
     "AIS_RELEASES_OVERRIDE", "SKIP_AIS"),
    ("PMOB", "playermob_version", "bh679/playermob-mc",
     "PMOB_RELEASES_OVERRIDE", "SKIP_PMOB"),
)

MODE_ALWAYS = "always"
MODE_WITH_CONTENT = "with-content"
MODE_AIN = "ain"
MODE_AIS = "ais"
VALID_MODES = {MODE_ALWAYS, MODE_WITH_CONTENT, MODE_AIN, MODE_AIS}


def parse_mode():
    raw = os.environ.get("AUTO_RELEASE_MODE", "")
    normalized = raw.strip().lower()
    if normalized in VALID_MODES:
        return normalized
    return MODE_ALWAYS


def parse_semver(s):
    if not isinstance(s, str):
        return None
    parts = s.split(".")
    if len(parts) != 3:
        return None
    try:
        return tuple(int(p) for p in parts)
    except ValueError:
        return None


def read_sibling_version(version_key):
    try:
        with open(GRADLE_PROPERTIES_FILE) as f:
            for line in f:
                if line.startswith(version_key + "="):
                    return line[len(version_key) + 1:].strip()
    except FileNotFoundError:
        return None
    return None


def write_sibling_version(version_key, new_version):
    with open(GRADLE_PROPERTIES_FILE) as f:
        lines = f.readlines()
    found = False
    for i, line in enumerate(lines):
        if line.startswith(version_key + "="):
            suffix = "\n" if line.endswith("\n") else ""
            lines[i] = f"{version_key}={new_version}{suffix}"
            found = True
            break
    if not found:
        print(f"::error::{GRADLE_PROPERTIES_FILE}: {version_key} line not found", file=sys.stderr)
        sys.exit(1)
    with open(GRADLE_PROPERTIES_FILE, "w") as f:
        f.writelines(lines)


def fetch_sibling_releases(repo, override_env):
    override = os.environ.get(override_env)
    if override is not None:
        try:
            data = json.loads(override)
        except json.JSONDecodeError as e:
            print(f"::warning::{override_env} not valid JSON: {e}", file=sys.stderr)
            return []
    else:
        try:
            result = subprocess.run(
                ["gh", "release", "list", "--repo", repo,
                 "--json", "tagName", "-L", "200"],
                capture_output=True, text=True, timeout=30, check=True,
            )
        except (subprocess.SubprocessError, FileNotFoundError) as e:
            print(f"::warning::gh release list failed for {repo}: {e}", file=sys.stderr)
            return []
        try:
            data = json.loads(result.stdout)
        except json.JSONDecodeError as e:
            print(f"::warning::gh release list returned invalid JSON: {e}", file=sys.stderr)
            return []

    versions = []
    for entry in data:
        tag = entry.get("tagName", "")
        if tag.startswith("v"):
            tag = tag[1:]
        if parse_semver(tag) is not None:
            versions.append(tag)
    return versions


def pending_sibling_update(mode):
    """Return the name of the first mode-applicable sibling that DT is behind on, else None.

    Mode gating mirrors apply-change.py:
      mode=ain — only AIN considered
      mode=ais — only AIS considered
      mode=always or with-content — all siblings considered, in declared order

    Failures (missing gradle.properties, malformed version, network error,
    parse error) fall through to None so a transient issue never falsely
    pins the cascade to phase A.
    """
    for name, version_key, repo, override_env, _skip in SIBLING_MODS:
        if mode == MODE_AIN and name != "AIN":
            continue
        if mode == MODE_AIS and name != "AIS":
            continue
        current = read_sibling_version(version_key)
        if current is None:
            continue
        current_tuple = parse_semver(current)
        if current_tuple is None:
            continue
        for v in fetch_sibling_releases(repo, override_env):
            parsed = parse_semver(v)
            if parsed is not None and parsed > current_tuple:
                return name
    return None
