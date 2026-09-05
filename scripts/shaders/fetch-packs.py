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

# (id, Modrinth project slug, exact version_number to pin, display name, perf, vanilla_rank,
#  mood_rank, author_rank)
#
# `id` is ours: it keys the preview texture and the analytics target, and never changes even if the
# pack renames itself.
#
# `perf` and `vanilla_rank` are CURATED EDITORIAL JUDGEMENTS, not measurements.
#
# Measuring them was tried first and does not work off the capture run: the sweep client is
# frame-capped, so all nine packs rendered ~1310 frames in the same window and the numbers said
# nothing. A real figure needs an uncapped frame-time harness, which is worth building separately;
# until it exists, a tier a player can act on ("this one is for weak machines") beats a number that
# looks measured and is not. They are ordinary shader-list guidance, sourced from what each pack is
# for — MakeUp is called Ultra Fast because that is its whole design; Sildur's Enhanced Default is
# vanilla with lighting; the Complementary pair are the well-optimised middle; Bliss is a Chocapic
# derivative and Insanity is a stylised heavy pack.
#
# perf:         one of PERF_TIERS below, cheapest first.
# vanilla_rank: 1 = closest to vanilla, 9 = furthest from it.
# mood_rank:    1 = plainest light, 9 = heaviest atmosphere. NOT the inverse of vanilla_rank — a
#               pack can depart a long way from vanilla while staying bright (MakeUp), and one can
#               stay close to it while being dim. Sildur's is vanilla with lighting; Spooklementary
#               is built to be spooky; Insanity is the far end of both.
# author_rank:  Brennan's own running order, top pick first. The page opens on this.
#
# All four are CURATED, not measured — see the note above PERF_TIERS.
PERF_TIERS = ["very-light", "light", "moderate", "heavy"]

PACKS = [
    #  id                          slug                                pin       name                         perf         van mood author
    ("bsl",                       "bsl-shaders",                      "10.1.3", "BSL",                       "moderate",   4,  4,  4),
    ("bliss",                     "bliss-shader",                     "2.1.2",  "Bliss",                     "heavy",      8,  6,  3),
    ("complementary-reimagined",  "complementary-reimagined",         "r5.8.1", "Complementary Reimagined",  "moderate",   3,  3,  1),
    ("complementary-unbound",     "complementary-unbound",            "r5.8.1", "Complementary Unbound",     "moderate",   5,  5,  2),
    ("hysteria",                  "hysteria-shaders",                 "1.2.1",  "Hysteria",                  "heavy",      7,  7,  5),
    ("insanity",                  "insanity-shader",                  "1.650",  "Insanity",                  "heavy",      9,  9,  9),
    ("makeup-ultra-fast",         "makeup-ultra-fast-shaders",        "9.5d",   "MakeUp Ultra Fast",         "very-light", 2,  2,  6),
    ("sildurs-enhanced-default",  "sildurs-enhanced-default-shaders", "1.19",   "Sildur's Enhanced Default", "very-light", 1,  1,  8),
    ("spooklementary",            "spooklementary",                   "2.0.4",  "Spooklementary",            "moderate",   6,  8,  7),
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
    seen = {"vanilla_rank": set(), "mood_rank": set(), "author_rank": set()}
    for pack_id, slug, pin, name, perf, vanilla_rank, mood_rank, author_rank in PACKS:
        if perf not in PERF_TIERS:
            raise SystemExit(f"{pack_id}: unknown performance tier {perf!r}")
        for field, value in (("vanilla_rank", vanilla_rank), ("mood_rank", mood_rank),
                             ("author_rank", author_rank)):
            if value in seen[field]:
                raise SystemExit(f"{pack_id}: {field} {value} is already used — each sort order "
                                 "needs a total order, not a tie")
            seen[field].add(value)
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
            "performance": perf,
            "vanilla_rank": vanilla_rank,
            "mood_rank": mood_rank,
            "author_rank": author_rank,
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
