#!/usr/bin/env python3
"""Guard: every modpack entry names the REAL modIds its jar loads, so the whitelist can be generated.

Every mod the modpack ships is approved for fair play automatically — the ``generateApprovedMods``
Gradle task reads each entry's ``mod_ids`` into the baked whitelist (``approved_mods.json``). That
only works if ``mod_ids`` are what ``ModList.get().getMods()`` reports, NOT store slugs: Iris ships
as ``iris`` (slug ``irisshaders``), Moonlight as ``moonlight`` (slug ``selene``), Advanced
Shulkerboxes as ``shulkerbox``. A slug in the whitelist matches nothing, and every player running
that mod silently lands in Free Play. The 2026-09 relay review found 13 such ids.

So the ids are read from the jar itself: the pinned file's ``META-INF/neoforge.mods.toml``
``[[mods]]`` entries, plus the same for every jar it embeds under ``META-INF/jarjar/`` (Sable
brings Veil and Sable Companion that way — NeoForge loads them as mods, so players report them).

Opt out of auto-approval with ``"whitelist": false`` on an entry (Brennan's call, asked at Gate 3
whenever an entry is added). Such an entry needs no ``mod_ids``.

Modes:
  (default)   offline: every entry (except whitelist:false) has a non-empty, well-formed mod_ids.
  --verify    network: download each pinned jar and require mod_ids == the jar's modIds.
  --fill      network: write mod_ids for entries that lack them (use when adding a mod).

Jars come from the Modrinth pin (``modrinth_version``) or, for ``curseforge_only`` entries, the
CurseForge file (``project_id`` + ``file_id``). Downloads are cached under ``--cache``.

Run: python3 scripts/modpack/check-mod-ids.py [--verify | --fill]   (exit 0 = clean, 1 = drift)
"""
import argparse
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# scripts/modpack/check-mod-ids.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"
DEFAULT_CACHE = Path.home() / ".cache" / "dungeontrain-modpack-jars"

# FML's own modId rule. A hyphen means a store slug was pasted in.
MOD_ID = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
TOML_NAMES = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
USER_AGENT = "bh679/dungeon-train-mc check-mod-ids (github.com/bh679/dungeon-train-mc)"
RETRIES = 3


def entries(config):
    """(label, entry) for sable + every optional_mods entry — the mods the pack puts on disk."""
    out = [("Sable", config["sable"])]
    out += [(o.get("name") or o.get("slug") or "?", o) for o in config.get("optional_mods", [])]
    return out


def whitelisted(entry):
    return entry.get("whitelist", True) is not False


def toml_mod_ids(text):
    """modIds declared by the ``[[mods]]`` tables of a mods.toml (dependency tables ignored)."""
    ids, in_mods = [], False
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("["):
            in_mods = line.replace(" ", "") == "[[mods]]"
            continue
        m = re.match(r'modId\s*=\s*["\']([^"\']+)["\']', line)
        if in_mods and m:
            ids.append(m.group(1))
    return ids


def jar_mod_ids(data):
    """Every modId a jar loads: its own [[mods]] plus those of each jar-in-jar, recursively."""
    ids = []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        for toml in TOML_NAMES:
            if toml in names:
                ids += toml_mod_ids(z.read(toml).decode("utf-8", "replace"))
                break
        for name in sorted(names):
            if name.startswith("META-INF/jarjar/") and name.endswith(".jar"):
                ids += jar_mod_ids(z.read(name))
    return sorted(set(ids))


def check_presence(config):
    """Offline problems: missing, empty or malformed mod_ids. Returns a list of messages."""
    problems = []
    for label, entry in entries(config):
        if not whitelisted(entry):
            continue
        ids = entry.get("mod_ids")
        if not isinstance(ids, list) or not ids:
            problems.append(f"{label}: no mod_ids (run check-mod-ids.py --fill, or set \"whitelist\": false)")
            continue
        bad = [i for i in ids if not isinstance(i, str) or not MOD_ID.match(i)]
        if bad:
            problems.append(f"{label}: mod_ids {bad} are not modIds (slug pasted in?)")
    return problems


def fetch(url):
    last = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except (urllib.error.URLError, TimeoutError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code < 500:
                raise
            last = e
            time.sleep(2 * (attempt + 1))
    raise last


def download_url(entry):
    if entry.get("modrinth_version"):
        meta = json.loads(fetch(f"https://api.modrinth.com/v2/version/{entry['modrinth_version']}"))
        files = meta.get("files") or []
        primary = next((f for f in files if f.get("primary")), files[0] if files else None)
        if primary:
            return primary["url"]
    if entry.get("project_id") and entry.get("file_id"):
        return (f"https://www.curseforge.com/api/v1/mods/{entry['project_id']}"
                f"/files/{entry['file_id']}/download")
    raise ValueError("no modrinth_version and no project_id/file_id to download")


def cached_jar(entry, cache):
    key = entry.get("modrinth_version") or f"cf-{entry.get('project_id')}-{entry.get('file_id')}"
    path = cache / f"{key}.jar"
    if not path.exists():
        cache.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".part")
        tmp.write_bytes(fetch(download_url(entry)))
        tmp.replace(path)
    return path.read_bytes()


def actual_ids(label, entry, cache):
    ids = jar_mod_ids(cached_jar(entry, cache))
    if not ids:
        raise ValueError(f"{label}: pinned jar declares no [[mods]] modId")
    return ids


def verify(config, cache):
    problems = []
    for label, entry in entries(config):
        if not whitelisted(entry):
            continue
        try:
            want = actual_ids(label, entry, cache)
        except Exception as e:  # report every entry, not just the first failure
            problems.append(f"{label}: could not read pinned jar: {e}")
            continue
        have = sorted(set(entry.get("mod_ids") or []))
        if have != want:
            problems.append(f"{label}: mod_ids {have} != jar {want}")
    return problems


def fill(config, cache):
    """Set mod_ids (inserted after slug) on whitelisted entries lacking them. Returns labels filled."""
    filled = []
    for label, entry in entries(config):
        if not whitelisted(entry) or entry.get("mod_ids"):
            continue
        ids = actual_ids(label, entry, cache)
        rebuilt = {}
        for k, v in entry.items():
            rebuilt[k] = v
            if k == "slug":
                rebuilt["mod_ids"] = ids
        rebuilt.setdefault("mod_ids", ids)
        entry.clear()
        entry.update(rebuilt)
        filled.append(f"{label} -> {ids}")
    return filled


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--verify", action="store_true", help="download pinned jars and compare")
    mode.add_argument("--fill", action="store_true", help="write missing mod_ids from the jars")
    args = parser.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8"))

    if args.fill:
        filled = fill(config, args.cache)
        args.config.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        for line in filled:
            print(f"filled: {line}")
        print(f"OK: filled {len(filled)} entries.")
        return 0

    problems = check_presence(config)
    if args.verify and not problems:
        problems = verify(config, args.cache)
    if problems:
        print("FAIL: modpack mod_ids drift — the generated whitelist would be wrong:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    n = sum(1 for _, e in entries(config) if whitelisted(e))
    skipped = [label for label, e in entries(config) if not whitelisted(e)]
    print(f"OK: {n} modpack entries carry {'jar-verified ' if args.verify else ''}mod_ids.")
    if skipped:
        print(f"INFO: whitelist:false (not auto-approved): {', '.join(skipped)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
