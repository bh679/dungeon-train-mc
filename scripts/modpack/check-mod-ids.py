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

Since the hash-verified whitelist (2026-10) each entry also carries the pinned jar's ``sha1``: the
Gradle task bakes those into ``approved_mods.json`` ``hashes`` so a stock modpack install passes
the file check offline, before the relay has ever been asked. ``--fill`` records it, ``--verify``
checks it; the offline check only requires it where ``mod_ids`` is present.

Modes:
  (default)   offline: every entry (except whitelist:false) has a non-empty, well-formed mod_ids.
  --verify    network: download each pinned jar and require mod_ids == the jar's modIds.
  --fill      network: write mod_ids for entries that lack them (use when adding a mod).

Jars come from the Modrinth pin (``modrinth_version``) or, for ``curseforge_only`` entries, the
CurseForge file (``project_id`` + ``file_id``). Downloads are cached under ``--cache``.

Run: python3 scripts/modpack/check-mod-ids.py [--verify | --fill]   (exit 0 = clean, 1 = drift)
"""
import argparse
import hashlib
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
SHA1 = re.compile(r"^[0-9a-f]{40}$")
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
        sha1 = entry.get("sha1")
        if not isinstance(sha1, str) or not SHA1.match(sha1):
            problems.append(f"{label}: no sha1 (run check-mod-ids.py --fill)")
        if not isinstance(entry.get("packages"), list):
            problems.append(f"{label}: no packages (run check-mod-ids.py --fill)")
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


# ---- package fingerprint (twin of cheat/PackageFingerprint.java; the two must agree) ----------

# Libraries mods commonly shade or that every jar on the classpath shares. A match on one of these
# says nothing about which mod a jar is, so they never count.
PKG_DROP = ("org.apache", "org.slf4j", "org.jetbrains", "org.intellij", "org.objectweb", "org.lwjgl", "org.joml",
            "org.spongepowered", "org.yaml", "org.json", "org.checkerframework", "org.jspecify", "org.codehaus",
            "com.google", "com.mojang", "com.sun", "com.electronwill", "com.fasterxml", "io.netty", "kotlin",
            "kotlinx", "javax", "jakarta", "java", "sun", "net.minecraft", "net.neoforged", "net.minecraftforge",
            "net.fabricmc", "scala", "groovy", "it.unimi", "cpw.mods", "mixin", "META-INF")
# Hosting-style roots where the second segment is the host, not the author: take three segments.
PKG_HOSTS = ("com.github", "io.github", "org.github", "dev.github", "com.gitlab", "io.gitlab")
PKG_MAX = 64


def package_of(entry_name):
    """The package key for one zip entry name, or None when it does not count."""
    if not entry_name.endswith(".class") or entry_name.startswith("META-INF/"):
        return None
    parts = entry_name.split("/")[:-1]
    if len(parts) < 2:
        return None  # default package, or a single-segment package: too generic to mean anything
    key = ".".join(parts[:2])
    if key in PKG_HOSTS and len(parts) >= 3:
        key = ".".join(parts[:3])
    if key.startswith(PKG_DROP) and any(key == d or key.startswith(d + ".") for d in PKG_DROP):
        return None
    if not re.match(r"^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*){1,2}$", key):
        return None
    return key


def jar_packages(data, depth=0):
    """Sorted, capped package fingerprint of a jar's bytes, embedded jars included."""
    out = set()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
        for name in names:
            key = package_of(name)
            if key:
                out.add(key)
        if depth < 2:
            for name in names:
                if name.startswith("META-INF/jarjar/") and name.endswith(".jar"):
                    try:
                        out.update(jar_packages(z.read(name), depth + 1))
                    except zipfile.BadZipFile:
                        pass
    return sorted(out)[:PKG_MAX]


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


def actual_sha1(entry, cache):
    """Lowercase hex SHA-1 of the pinned jar — the file identity the hash whitelist keys on."""
    return hashlib.sha1(cached_jar(entry, cache)).hexdigest()


def actual_packages(entry, cache):
    """The pinned jar's package fingerprint — what the mod's code looks like (hash whitelist, tier 2)."""
    return jar_packages(cached_jar(entry, cache))


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
        sha1 = actual_sha1(entry, cache)
        if entry.get("sha1") != sha1:
            problems.append(f"{label}: sha1 {entry.get('sha1')} != jar {sha1}")
        pk = actual_packages(entry, cache)
        if entry.get("packages") != pk:
            problems.append(f"{label}: packages differ from the jar's ({len(entry.get('packages') or [])} vs {len(pk)})")
    return problems


def fill(config, cache):
    """Set mod_ids and sha1 (inserted after slug) on whitelisted entries lacking them. Returns labels filled."""
    filled = []
    for label, entry in entries(config):
        if not whitelisted(entry):
            continue
        need_ids = not entry.get("mod_ids")
        need_sha = not entry.get("sha1")
        need_pk = not isinstance(entry.get("packages"), list)
        if not need_ids and not need_sha and not need_pk:
            continue
        ids = entry.get("mod_ids") if not need_ids else actual_ids(label, entry, cache)
        sha1 = entry.get("sha1") if not need_sha else actual_sha1(entry, cache)
        packages = entry.get("packages") if not need_pk else actual_packages(entry, cache)
        rebuilt = {}
        for k, v in entry.items():
            if k in ("mod_ids", "sha1", "packages"):
                continue
            rebuilt[k] = v
            if k == "slug":
                rebuilt["mod_ids"] = ids
                rebuilt["sha1"] = sha1
                rebuilt["packages"] = packages
        rebuilt.setdefault("mod_ids", ids)
        rebuilt.setdefault("sha1", sha1)
        rebuilt.setdefault("packages", packages)
        entry.clear()
        entry.update(rebuilt)
        filled.append(f"{label} -> {ids if need_ids else 'ids kept'}, sha1 {sha1[:8]}…")
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
