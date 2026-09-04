#!/usr/bin/env python3
"""Resolve the supported Iris shader packs against Modrinth and write the shipped manifest.

The nine packs are the ones that load and behave correctly under Dungeon Train's band and
carriage atmosphere systems — see ``docs/shaders/compat-matrix.md``. Their exact builds are
pinned here by version string, so a pack releasing a new version never silently changes what
the in-game Shaders page offers; re-running this script is the deliberate way to move a pin.

Two outputs, and both are wanted:

* ``src/main/resources/assets/dungeontrain/shader_menu/packs.json`` — the manifest the client reads.
  Download URL, SHA-512 and size come straight from Modrinth so the client can verify what it
  fetched, and the project page rides along as the fallback when a download fails.
* ``run/shaderpacks/`` (with ``--download``) — the zips themselves, which is what the preview
  capture sweep needs. Nothing here is ever committed; the packs are all-rights-reserved or
  custom-licensed and the mod ships none of them.

Usage:
    python3 scripts/shaders/fetch-packs.py [--download] [--out PATH]
"""

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "src/main/resources/assets/dungeontrain/shader_menu/packs.json"
PACK_DIR = REPO / "run/shaderpacks"

API = "https://api.modrinth.com/v2"
UA = "bh679/dungeon-train-mc (shader manifest generator)"

# (id, Modrinth project slug, exact version_number to pin, display name)
# `id` is ours: it keys the preview texture and the analytics target, and never changes even
# if the pack renames itself.
PACKS = [
    ("bsl",                       "bsl-shaders",                      "10.1.3", "BSL"),
    ("bliss",                     "bliss-shader",                     "2.1.2",  "Bliss"),
    ("complementary-reimagined",  "complementary-reimagined",         "r5.8.1", "Complementary Reimagined"),
    ("complementary-unbound",     "complementary-unbound",            "r5.8.1", "Complementary Unbound"),
    ("hysteria",                  "hysteria-shaders",                 "1.2.1",  "Hysteria"),
    ("insanity",                  "insanity-shader",                  "1.650",  "Insanity"),
    ("makeup-ultra-fast",         "makeup-ultra-fast-shaders",        "9.5d",   "MakeUp Ultra Fast"),
    ("sildurs-enhanced-default",  "sildurs-enhanced-default-shaders", "1.19",   "Sildur's Enhanced Default"),
    ("spooklementary",            "spooklementary",                   "2.0.4",  "Spooklementary"),
]

# Sildur's ships Fancy and Fast under one version number; the compat matrix measured Fancy.
FILENAME_MUST_CONTAIN = {"sildurs-enhanced-default": "Fancy"}


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def pick_version(slug, pin, pack_id):
    versions = get_json(f"{API}/project/{slug}/version")
    matches = [v for v in versions if pin in v["version_number"]]
    if not matches:
        raise SystemExit(f"{slug}: no version matching {pin!r} (newest is "
                         f"{versions[0]['version_number'] if versions else 'none'})")
    needle = FILENAME_MUST_CONTAIN.get(pack_id)
    for version in matches:
        for file in version["files"]:
            if needle is None or needle in file["filename"]:
                return version, file
    raise SystemExit(f"{slug}: version {pin} has no file containing {needle!r}")


def authors(slug):
    members = get_json(f"{API}/project/{slug}/members")
    owners = [m for m in members if m.get("role", "").lower() == "owner"] or members
    return ", ".join(m["user"]["username"] for m in owners) or "unknown"


def download(url, dest, sha512):
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    digest = hashlib.sha512()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp, part.open("wb") as out:
        while chunk := resp.read(65536):
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest() != sha512:
        part.unlink(missing_ok=True)
        raise SystemExit(f"{dest.name}: SHA-512 mismatch, refusing to keep the file")
    part.replace(dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true",
                    help=f"also fetch the zips into {PACK_DIR} for the preview sweep")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    entries = []
    for pack_id, slug, pin, name in PACKS:
        try:
            version, file = pick_version(slug, pin, pack_id)
        except urllib.error.URLError as err:
            raise SystemExit(f"{slug}: Modrinth unreachable ({err})")
        sha512 = file["hashes"]["sha512"]
        entries.append({
            "id": pack_id,
            "name": name,
            "version": version["version_number"],
            "author": authors(slug),
            "filename": file["filename"],
            "url": file["url"],
            "sha512": sha512,
            "size": file["size"],
            "page": f"https://modrinth.com/shader/{slug}",
        })
        print(f"{name:28} {version['version_number']:8} {file['filename']}")
        if args.download:
            dest = PACK_DIR / file["filename"]
            if dest.exists() and hashlib.sha512(dest.read_bytes()).hexdigest() == sha512:
                print(f"{'':28} already downloaded")
            else:
                download(file["url"], dest, sha512)
                print(f"{'':28} downloaded -> {dest}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"packs": entries}, indent=2) + "\n", encoding="utf-8")
    print(f"\nwrote {len(entries)} packs to {args.out.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
