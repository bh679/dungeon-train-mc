#!/usr/bin/env python3
"""Bake every approved mod's published-file hashes into the whitelist that ships in the jar.

The hash-verified whitelist (``cheat/ModFileScanner`` + the relay's ``/approved-mods/verify``)
judges an installed jar by its SHA-1, not by the mod id written inside it. The jar bakes the
hashes it already knows — the modpack's pinned files (``modpack.config.json`` ``sha1``) and the
jars we embed — so a stock install passes offline. This script extends that to the WHOLE
whitelist: for every approved id it finds the mod's Modrinth (and, with a key, CurseForge)
project and records the sha1 of every published 1.21.1 NeoForge file, so a player running any
approved mod at any version passes the file check on first launch, before the relay has been
asked.

Finding the project without a hand-kept mapping: ids and store slugs differ (``iris`` is
``irisshaders``, ``moonlight`` is ``selene``), so each Modrinth search hit's 1.21.1 NeoForge
primary file is downloaded (into the same cache ``check-mod-ids.py`` uses) and its
``mods.toml`` read — the project is accepted only when the ids it really loads include the
approved id. CurseForge is tried the same way when ``CURSEFORGE_API_KEY`` is set (the upload
token does not read).

Output, committed: ``src/main/whitelist/approved_hashes.json``::

    { "generatedAt": "2026-10-11",
      "slugs": { "farmersdelight": "farmers-delight", ... },   # hand hints, kept across runs
      "byMod": { "<id>": { "platform": "modrinth", "project": "<id>", "slug": "<slug>",
                           "name": "<title>", "sha1": ["<40 hex>", ...] }, ... },
      "unmatched": ["<id>", ...] }

``generateApprovedMods`` (gradle/approved-mods.gradle) merges ``byMod[*].sha1`` into the baked
``hashes``. ``unmatched`` ids stay approved by id and verify live through the relay.

Modes:
  (default)   network: resolve every approved id not yet matched (unmatched ones are retried;
              matched ones only with --refresh), rewrite the JSON, print coverage.
  --check     offline (CI): every id in the generated approved_mods.json is in byMod or
              unmatched — a newly approved id fails until this script has been run.

Run: python3 scripts/modpack/pull-approved-hashes.py [--refresh] [--only id,id] [--check]
Needs the generated whitelist: ./gradlew generateApprovedMods first (the check reads it too).
"""
import argparse
import datetime as dt
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib import import_module  # noqa: E402
_cmi = import_module("check-mod-ids")  # the package-fingerprint twin lives there (shared with --fill)
jar_packages = _cmi.jar_packages
GENERATED = REPO_ROOT / "build" / "generated" / "sources" / "approvedMods" / "assets" / "dungeontrain" / "cheat" / "approved_mods.json"
OUTPUT = REPO_ROOT / "src" / "main" / "whitelist" / "approved_hashes.json"
MODPACK_CONFIG = REPO_ROOT / "modpack" / "modpack.config.json"
CACHE = Path.home() / ".cache" / "dungeontrain-modpack-jars"

MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://api.curseforge.com/v1"
USER_AGENT = "bh679/dungeon-train-mc pull-approved-hashes (github.com/bh679/dungeon-train-mc)"
GAME_VERSION = "1.21.1"
CF_NEOFORGE_LOADER = 6
CF_MINECRAFT_CLASS = 6
SHA1 = re.compile(r"^[0-9a-f]{40}$")
TOML_NAMES = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
# Ids the jar judges by id alone (ModFileScanner.EXEMPT_IDS) plus Sinytra's prefix rule: nothing to bake.
SKIP_IDS = {"minecraft", "neoforge", "forge", "dungeontrain"}
SKIP_PREFIXES = ("fabric_",)
CANDIDATES = 5
RETRIES = 3


# ---- http ------------------------------------------------------------------------------------

def fetch(url, headers=None, timeout=120):
    last = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code < 500 and e.code != 429:
                raise
            last = e
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
        time.sleep(2 * (attempt + 1))
    raise last


def get_json(url, headers=None):
    body = fetch(url, headers)
    return None if body is None else json.loads(body)


def cf_headers():
    key = os.environ.get("CURSEFORGE_API_KEY")
    return {"x-api-key": key, "Accept": "application/json"} if key else None


# ---- jars ------------------------------------------------------------------------------------

def toml_mod_ids(text):
    ids, in_mods = [], False
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("["):
            in_mods = line.replace(" ", "") == "[[mods]]"
            continue
        m = re.match(r'modId\s*=\s*["\']([^"\']+)["\']', line)
        if in_mods and m:
            ids.append(m.group(1).lower())
    return ids


def jar_mod_ids(data):
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


def cached(key, url):
    path = CACHE / f"{key}.jar"
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        data = fetch(url)
        if data is None:
            return None
        tmp = path.with_suffix(".part")
        tmp.write_bytes(data)
        tmp.replace(path)
    return path.read_bytes()


# ---- modrinth --------------------------------------------------------------------------------

def modrinth_versions(project_id):
    q = urllib.parse.urlencode({"loaders": '["neoforge"]', "game_versions": f'["{GAME_VERSION}"]'})
    return get_json(f"{MODRINTH}/project/{project_id}/version?{q}") or []


def modrinth_candidates(mod_id, hint=None):
    """Projects that might be mod_id, likeliest first: a hand-given slug (the output file's
    ``slugs`` map, for ids whose slug cannot be guessed — ``farmersdelight`` is ``farmers-delight``),
    the slug spelled like the id (direct lookup — Modrinth's text search does not match
    ``cloth_config``), then search hits for the id spelled with underscores, hyphens and spaces.
    A wrong hint is harmless: the project is accepted only when its jar really loads mod_id."""
    seen = {}
    for slug in dict.fromkeys([hint, mod_id, mod_id.replace("_", "-"), mod_id.replace("_", "")]):
        if not slug:
            continue
        proj = get_json(f"{MODRINTH}/project/{urllib.parse.quote(slug)}")
        if proj and proj.get("project_type") == "mod":
            seen[proj["id"]] = {"project_id": proj["id"], "slug": proj.get("slug"), "title": proj.get("title")}
    facets = json.dumps([["categories:neoforge"], [f"versions:{GAME_VERSION}"], ["project_type:mod"]])
    for query in dict.fromkeys([mod_id, mod_id.replace("_", "-"), mod_id.replace("_", " ")]):
        q = urllib.parse.urlencode({"query": query, "facets": facets, "limit": CANDIDATES})
        for h in (get_json(f"{MODRINTH}/search?{q}") or {}).get("hits") or []:
            seen.setdefault(h["project_id"], h)
    return list(seen.values())


def match_modrinth(mod_id, hint=None):
    """{platform, project, slug, name, sha1[]} for the Modrinth project whose jar loads mod_id, or None."""
    for hit in modrinth_candidates(mod_id, hint):
        pid = hit.get("project_id")
        versions = modrinth_versions(pid)
        if not versions:
            continue
        files = [f for v in versions for f in (v.get("files") or [])]
        primary = next((f for v in versions for f in (v.get("files") or []) if f.get("primary")), files[0] if files else None)
        if not primary:
            continue
        try:
            data = cached(f"mr-{primary['hashes']['sha1']}", primary["url"])
        except Exception as e:  # one bad download must not sink the id
            print(f"  ! {mod_id}: could not download {hit.get('slug')}: {e}", file=sys.stderr)
            continue
        if data is None or mod_id not in jar_mod_ids(data):
            continue
        sha1s = sorted({f["hashes"]["sha1"] for f in files if f.get("hashes", {}).get("sha1") and SHA1.match(f["hashes"]["sha1"])})
        return {"platform": "modrinth", "project": pid, "slug": hit.get("slug"), "name": hit.get("title"),
                "sha1": sha1s, "packages": jar_packages(data)}
    return None


# ---- curseforge ------------------------------------------------------------------------------

def match_curseforge(mod_id):
    headers = cf_headers()
    if not headers:
        return None
    q = urllib.parse.urlencode({"gameId": 432, "classId": CF_MINECRAFT_CLASS, "searchFilter": mod_id,
                                "gameVersion": GAME_VERSION, "modLoaderType": CF_NEOFORGE_LOADER, "pageSize": CANDIDATES})
    hits = ((get_json(f"{CURSEFORGE}/mods/search?{q}", headers) or {}).get("data")) or []
    for hit in hits:
        cid = hit.get("id")
        fq = urllib.parse.urlencode({"gameVersion": GAME_VERSION, "modLoaderType": CF_NEOFORGE_LOADER, "pageSize": 50})
        files = ((get_json(f"{CURSEFORGE}/mods/{cid}/files?{fq}", headers) or {}).get("data")) or []
        if not files:
            continue
        newest = max(files, key=lambda f: f.get("fileDate", ""))
        url = newest.get("downloadUrl") or f"https://www.curseforge.com/api/v1/mods/{cid}/files/{newest['id']}/download"
        try:
            data = cached(f"cf-{cid}-{newest['id']}", url)
        except Exception as e:
            print(f"  ! {mod_id}: could not download {hit.get('slug')}: {e}", file=sys.stderr)
            continue
        if data is None or mod_id not in jar_mod_ids(data):
            continue
        sha1s = sorted({h["value"].lower() for f in files for h in (f.get("hashes") or []) if h.get("algo") == 1 and SHA1.match(str(h.get("value", "")).lower())})
        return {"platform": "curseforge", "project": str(cid), "slug": hit.get("slug"), "name": hit.get("name"),
                "sha1": sha1s, "packages": jar_packages(data)}
    return None


def merge_platforms(mr, cf):
    """One record carrying BOTH stores' files. The CurseForge app installs CurseForge's upload,
    which is often but not always byte-identical to Modrinth's, so a mod matched on Modrinth still
    gets its CurseForge hashes (and vice versa). Packages come from whichever jar was read."""
    if not mr and not cf:
        return None
    if not mr or not cf:
        return mr or cf
    rec = dict(mr)
    rec["sha1"] = sorted(set(mr["sha1"]) | set(cf["sha1"]))
    rec["packages"] = sorted(set(mr.get("packages", [])) | set(cf.get("packages", [])))[:PKG_MAX]
    rec["curseforge"] = {"project": cf["project"], "slug": cf["slug"]}
    return rec


PKG_MAX = 64


# ---- the whitelist ---------------------------------------------------------------------------

def approved_ids():
    if not GENERATED.exists():
        sys.exit(f"missing {GENERATED} — run ./gradlew generateApprovedMods first")
    data = json.loads(GENERATED.read_text(encoding="utf-8"))
    ids = []
    for group in data.get("groups", {}).values():
        ids += [i.lower() for i in group.get("ids", [])]
    return sorted(set(i for i in ids if i not in SKIP_IDS and not i.startswith(SKIP_PREFIXES)))


def pinned_ids(packages=False):
    """Ids whose file the modpack already pins (hash — or, with packages=True, package fingerprint —
    comes from modpack.config.json)."""
    if not MODPACK_CONFIG.exists():
        return set()
    cfg = json.loads(MODPACK_CONFIG.read_text(encoding="utf-8"))
    out = set()
    for entry in [cfg.get("sable", {})] + cfg.get("optional_mods", []):
        if (isinstance(entry.get("packages"), list) if packages else entry.get("sha1")):
            out.update(i.lower() for i in entry.get("mod_ids", []))
    return out


# Ids embedded in Dungeon Train's own jar get their package knowledge from the jarJar jars at
# build time (gradle/approved-mods.gradle), so --check does not call them blind.
EMBEDDED_IDS_HINT = {"keeptrim", "dungeonbackup", "sable_fence_trapdoor_fix", "streamdetect", "dpibypassdetect",
                     "pigmanvillagers", "lostcityterrainfit", "discordpresence", "ediblebackpacks"}


def load_output():
    if OUTPUT.exists():
        return json.loads(OUTPUT.read_text(encoding="utf-8"))
    return {"generatedAt": None, "byMod": {}, "unmatched": []}


def save_output(doc):
    doc = {
        "comment": "GENERATED by scripts/modpack/pull-approved-hashes.py — every published 1.21.1 NeoForge file hash of each approved mod. Re-run after approving a mod; do not edit.",
        "generatedAt": dt.date.today().isoformat(),
        "slugs": {k: doc.get("slugs", {})[k] for k in sorted(doc.get("slugs", {}))},
        "byMod": {k: doc["byMod"][k] for k in sorted(doc["byMod"])},
        "unmatched": sorted(set(doc["unmatched"])),
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def check():
    ids = set(approved_ids())
    doc = load_output()
    covered = set(doc.get("byMod", {})) | set(doc.get("unmatched", []))
    missing = sorted(ids - covered)
    if missing:
        print("FAIL: approved ids without a hash record (run scripts/modpack/pull-approved-hashes.py):", file=sys.stderr)
        for m in missing:
            print(f"  - {m}", file=sys.stderr)
        return 1
    # Package knowledge is what keeps an honest player out of Free Play when a hash is unknown, so
    # an approved id with none anywhere (pulled, pinned, or embedded in our jar) is a release risk.
    known = {k for k, v in doc.get("byMod", {}).items() if isinstance(v.get("packages"), list)} | pinned_ids(packages=True)
    blind = sorted(i for i in ids if i not in known and i not in EMBEDDED_IDS_HINT)
    if blind:
        print("WARN: approved ids with no package knowledge (unknown-tier until pulled or pinned): " + ", ".join(blind), file=sys.stderr)
    n = sum(len(v.get("sha1", [])) for v in doc.get("byMod", {}).values())
    print(f"OK: {len(doc.get('byMod', {}))} approved ids carry {n} file hashes; {len(doc.get('unmatched', []))} unmatched (id-only).")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true", help="offline: every approved id has a record")
    parser.add_argument("--refresh", action="store_true", help="re-resolve ids already recorded")
    parser.add_argument("--only", help="comma-separated ids to (re)resolve")
    args = parser.parse_args(argv)
    if args.check:
        return check()

    doc = load_output()
    by_mod = dict(doc.get("byMod", {}))
    unmatched = set(doc.get("unmatched", []))
    slugs = dict(doc.get("slugs", {}))  # hand-kept id → Modrinth slug hints, preserved across runs
    ids = approved_ids()
    if args.only:
        wanted = set(i.strip().lower() for i in args.only.split(","))
        ids = [i for i in ids if i in wanted]
    # Unmatched ids are retried every run (a mod may have been published since); matched ones only
    # with --refresh.
    todo = [i for i in ids if args.refresh or args.only or i not in by_mod]
    print(f"{len(ids)} approved ids, {len(todo)} to resolve"
          + ("" if cf_headers() else " (no CURSEFORGE_API_KEY: Modrinth only)"))
    for n, mod_id in enumerate(todo, 1):
        try:
            rec = merge_platforms(match_modrinth(mod_id, slugs.get(mod_id)), match_curseforge(mod_id))
        except Exception as e:
            print(f"  ! {mod_id}: {e}", file=sys.stderr)
            rec = None
        if rec:
            by_mod[mod_id] = rec
            unmatched.discard(mod_id)
            print(f"  [{n}/{len(todo)}] {mod_id}: {rec['platform']} {rec['slug']} — {len(rec['sha1'])} file(s)")
        else:
            by_mod.pop(mod_id, None)
            unmatched.add(mod_id)
            print(f"  [{n}/{len(todo)}] {mod_id}: no match")
    # Drop records for ids no longer approved, so a revoked mod's hashes are not baked.
    keep = set(ids) if not args.only else set(by_mod) | set(unmatched)
    by_mod = {k: v for k, v in by_mod.items() if k in keep}
    unmatched = {u for u in unmatched if u in keep}
    save_output({"byMod": by_mod, "unmatched": sorted(unmatched), "slugs": slugs})
    total = sum(len(v["sha1"]) for v in by_mod.values())
    pinned = pinned_ids()
    print(f"OK: {len(by_mod)} matched ({total} hashes), {len(unmatched)} unmatched"
          f" — of which {len([u for u in unmatched if u in pinned])} are modpack-pinned (hash baked from the config anyway).")
    if unmatched:
        print("unmatched: " + ", ".join(sorted(unmatched)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
