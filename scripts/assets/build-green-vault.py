#!/usr/bin/env python3
"""Build the green vault's assets: textures, block models, the vault blockstate and the key.

The green vault is vanilla's vault with DT's extra ``green`` property (``block/VaultGreen``). Its
look is vanilla's trial-vault textures recoloured green, and the Green Trial
Key is vanilla's trial key turned the same way: the orange accents and the blue body both go green.
Greys are left alone: only coloured pixels turn.

    python3 scripts/assets/build-green-vault.py              # write everything
    python3 scripts/assets/build-green-vault.py --check      # JSON only, fail if stale (no jar needed)
    python3 scripts/assets/build-green-vault.py --jar <client.jar>

The textures are read from the vanilla 1.21.1 client jar (the dev cache's copy by default) and
written as committed PNGs. The JSON is written from the rules below, so ``--check`` needs no jar.
Every ``green=false`` variant of ``assets/minecraft/blockstates/vault.json`` is exactly vanilla.
"""
from __future__ import annotations

import argparse
import colorsys
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets"
DT_TEXTURES = ASSETS / "dungeontrain/textures"
DT_MODELS = ASSETS / "dungeontrain/models"
VAULT_BLOCKSTATE = ASSETS / "minecraft/blockstates/vault.json"
DEFAULT_JAR = Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"

# Every coloured pixel takes a green hue, keeping its saturation and brightness: the orange accents
# (and the trial key's copper) become bright green, the blue body a deep green.
ACCENT_HUE = 120.0 / 360.0
BODY_HUE = 135.0 / 360.0
# Hues below this (or wrapping past red) are vanilla's warm accents; the rest is the cool body.
WARM_HUE_MAX = 70.0 / 360.0
# Pixels this grey or greyer keep their colour: the vault's steel body stays steel.
MIN_SATURATION = 0.18

VAULT_TEXTURES = (
    "vault_bottom", "vault_top", "vault_top_ejecting",
    "vault_front_off", "vault_front_on", "vault_front_ejecting",
    "vault_side_off", "vault_side_on",
)

FACINGS = {"east": 90, "north": 0, "south": 180, "west": 270}
STATES = ("active", "ejecting", "inactive", "unlocking")
# vault_state -> (vanilla model stem, textures as front/side/top/bottom stems)
MODELS = {
    "inactive": ("vault", ("front_off", "side_off", "top", "bottom")),
    "active": ("vault_active", ("front_on", "side_on", "top", "bottom")),
    "unlocking": ("vault_unlocking", ("front_ejecting", "side_on", "top", "bottom")),
    "ejecting": ("vault_ejecting_reward", ("front_ejecting", "side_on", "top_ejecting", "bottom")),
}


def green_model_stem(vanilla_stem: str) -> str:
    return vanilla_stem.replace("vault", "vault_green", 1)


def blockstate() -> dict:
    variants: dict[str, dict] = {}
    for facing, y in FACINGS.items():
        for green in ("false", "true"):
            for ominous in ("false", "true"):
                for state in STATES:
                    stem = MODELS[state][0]
                    if green == "true":
                        # A green vault is never ominous, but every state still needs a model.
                        model = f"dungeontrain:block/{green_model_stem(stem)}"
                    elif ominous == "true":
                        model = f"minecraft:block/{stem}_ominous"
                    else:
                        model = f"minecraft:block/{stem}"
                    variant: dict = {"model": model}
                    if y:
                        variant["y"] = y
                    key = f"facing={facing},green={green},ominous={ominous},vault_state={state}"
                    variants[key] = variant
    return {"variants": variants}


def block_models() -> dict[Path, dict]:
    out = {}
    for stem, (front, side, top, bottom) in MODELS.values():
        out[DT_MODELS / "block" / f"{green_model_stem(stem)}.json"] = {
            "parent": "minecraft:block/template_vault",
            "textures": {
                "bottom": f"dungeontrain:block/vault_green_{bottom}",
                "front": f"dungeontrain:block/vault_green_{front}",
                "side": f"dungeontrain:block/vault_green_{side}",
                "top": f"dungeontrain:block/vault_green_{top}",
            },
        }
    return out


def item_models() -> dict[Path, dict]:
    return {
        DT_MODELS / "item/green_trial_key.json": {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": "dungeontrain:item/green_trial_key"},
        }
    }


def json_outputs() -> dict[Path, str]:
    files = {VAULT_BLOCKSTATE: blockstate(), **block_models(), **item_models()}
    return {path: json.dumps(data, indent=2) + "\n" for path, data in files.items()}


def turn_green(png: bytes):
    from PIL import Image

    img = Image.open(io.BytesIO(png)).convert("RGBA")
    pixels = img.load()
    for x in range(img.width):
        for y in range(img.height):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s < MIN_SATURATION:
                continue
            warm = h <= WARM_HUE_MAX or h >= 330.0 / 360.0
            nr, ng, nb = colorsys.hsv_to_rgb(ACCENT_HUE if warm else BODY_HUE, s, v)
            pixels[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return img


def write_textures(jar: Path) -> None:
    try:
        import PIL  # noqa: F401
    except ImportError:
        sys.exit("Pillow is required: pip install pillow")
    if not jar.is_file():
        sys.exit(f"vanilla client jar not found: {jar} (pass --jar)")
    with zipfile.ZipFile(jar) as z:
        for name in VAULT_TEXTURES:
            src = z.read(f"assets/minecraft/textures/block/{name}.png")
            dest = DT_TEXTURES / "block" / f"{name.replace('vault', 'vault_green', 1)}.png"
            dest.parent.mkdir(parents=True, exist_ok=True)
            turn_green(src).save(dest)
        key = turn_green(z.read("assets/minecraft/textures/item/trial_key.png"))
        dest = DT_TEXTURES / "item/green_trial_key.png"
        dest.parent.mkdir(parents=True, exist_ok=True)
        key.save(dest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if the committed JSON is stale")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR, help="vanilla 1.21.1 client jar")
    args = parser.parse_args()

    outputs = json_outputs()
    if args.check:
        stale = [p for p, text in outputs.items() if not p.is_file() or p.read_text() != text]
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}", file=sys.stderr)
        if stale:
            print("run: python3 scripts/assets/build-green-vault.py", file=sys.stderr)
        return 1 if stale else 0

    for path, text in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    write_textures(args.jar)
    print(f"wrote {len(outputs)} JSON files and {len(VAULT_TEXTURES) + 1} textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
