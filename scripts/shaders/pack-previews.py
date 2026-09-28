#!/usr/bin/env python3
"""Turn a preview capture run into the Shaders menu's shipped preview textures.

Input:  run/screenshots/preview-<family>.png, one per pack, written by
        `scripts/shaders/sweep-all.sh --preview <world>`.
Output: src/main/resources/assets/dungeontrain/textures/gui/shaders/<pack id>.png at 854x480.

The capture is named for the shader family `ShaderCompat` reports; the menu keys its texture on the
manifest's pack id. They mostly agree and deliberately do not always — the family is stable across a
pack's version bumps, which is what the compatibility matrix wants, while the manifest id names a
particular listing. FAMILY_TO_ID is where the two are reconciled, and a capture with no mapping is
reported rather than silently dropped.

Every frame is checked for the two ways a preview run lies: a shot that is a duplicate of another
pack's (macOS stops rendering an occluded window, so captures go stale), and a shot that is nearly
black (a pack that failed to compile leaves Iris with no pack at all). Both look plausible.

Usage:
    python3 scripts/shaders/pack-previews.py [--dry-run]
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: python3 -m pip install pillow")

REPO = Path(__file__).resolve().parents[2]
SHOTS = REPO / "run/screenshots"
OUT_DIR = REPO / "src/main/resources/assets/dungeontrain/textures/gui/shaders"
MANIFEST = REPO / "src/main/resources/assets/dungeontrain/shader_menu/packs.json"

# Matches PREVIEW_W/PREVIEW_H in ShaderMenuScreen — the page contain-fits against these.
WIDTH, HEIGHT = 854, 480

# The vanilla control's asset. It is not in packs.json — it is the "Shaders off" row, which is a
# state rather than a pack — so it is carried here and checked separately.
VANILLA_ID = "vanilla"

# ShaderCompat.Pack id -> manifest pack id ("none" is ShaderCompat's id for no pack at all).
FAMILY_TO_ID = {
    "none": VANILLA_ID,
    "bsl": "bsl",
    "bliss": "bliss",
    "complementary_reimagined": "complementary-reimagined",
    "complementary_unbound": "complementary-unbound",
    "hysteria": "hysteria",
    "insanity": "insanity",
    "makeup_ultra_fast": "makeup-ultra-fast",
    "sildurs": "sildurs-enhanced-default",
    "spooklementary": "spooklementary",
}

# Below this mean luma the frame is almost certainly a pack that failed to load rather than a dark
# scene. The morning preview site is a lit overworld shot; the genuinely dark scenes are the bands,
# which this site never visits.
MIN_MEAN_LUMA = 25


def crop_to_aspect(img):
    """Centre-crop to 16:9 before scaling, so nothing is stretched."""
    want = WIDTH / HEIGHT
    have = img.width / img.height
    if abs(have - want) < 1e-3:
        return img
    if have > want:
        new_w = round(img.height * want)
        left = (img.width - new_w) // 2
        return img.crop((left, 0, left + new_w, img.height))
    new_h = round(img.width / want)
    top = (img.height - new_h) // 2
    return img.crop((0, top, img.width, top + new_h))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    args = ap.parse_args()

    known = {p["id"] for p in json.loads(MANIFEST.read_text(encoding="utf-8"))["packs"]}
    shots = sorted(SHOTS.glob("preview-*.png"))
    if not shots:
        sys.exit(f"no preview captures in {SHOTS} — run sweep-all.sh --preview first")

    seen_hashes = {}
    written = []
    problems = []

    for shot in shots:
        family = shot.stem[len("preview-"):]
        pack_id = FAMILY_TO_ID.get(family)
        if pack_id is None:
            problems.append(f"{shot.name}: no manifest id for shader family '{family}'")
            continue
        if pack_id not in known and pack_id != VANILLA_ID:
            problems.append(f"{shot.name}: '{pack_id}' is not in packs.json")
            continue

        digest = hashlib.sha256(shot.read_bytes()).hexdigest()
        if digest in seen_hashes:
            problems.append(f"{shot.name}: byte-identical to {seen_hashes[digest]} — STALE CAPTURE, "
                            "the game window was probably occluded; re-run that pack")
            continue
        seen_hashes[digest] = shot.name

        img = Image.open(shot).convert("RGB")
        luma = sum(img.convert("L").resize((64, 36)).getdata()) / (64 * 36)
        if luma < MIN_MEAN_LUMA:
            problems.append(f"{shot.name}: mean luma {luma:.1f} — the pack probably failed to load "
                            "(check for 'Failed to create shader rendering pipeline' in the log)")
            continue

        out = OUT_DIR / f"{pack_id}.png"
        if not args.dry_run:
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            crop_to_aspect(img).resize((WIDTH, HEIGHT), Image.LANCZOS).save(out, optimize=True)
        written.append((pack_id, luma, out))
        print(f"{pack_id:26} luma {luma:5.1f}  -> {out.relative_to(REPO)}")

    missing = (known | {VANILLA_ID}) - {p for p, _, _ in written}
    if missing:
        problems.append("no preview captured for: " + ", ".join(sorted(missing)))

    print(f"\n{len(written)}/{len(known) + 1} previews {'checked' if args.dry_run else 'written'} "
          f"({len(known)} packs + the vanilla control)")
    for problem in problems:
        print(f"  ! {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
