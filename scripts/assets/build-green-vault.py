#!/usr/bin/env python3
"""Build the green vault's assets: textures, block models, the vault blockstate and the key.

The green vault is vanilla's vault with DT's extra ``green`` property (``block/VaultGreen``). It
looks like the normal trial vault except for two things:

* its lights are green: the eyes and the light bar, the orange pixels where the ominous vault puts
  its own colour (the face and body stay the normal vault's blue); and
* its top carries the Dungeon Train logo, a ring of four segments, drawn in the top's own blues
  (``draw_logo``), around the cage opening when the vault ejects.

The Dungeon Trial Key is vanilla's trial key turned green (all its coloured pixels). The vault's
flames are swapped in code (``mixin/VaultClientParticlesMixin``).

    python3 scripts/assets/build-green-vault.py              # write everything
    python3 scripts/assets/build-green-vault.py --check      # JSON only, fail if stale (no jar needed)
    python3 scripts/assets/build-green-vault.py --jar <client.jar>

The textures are read from the vanilla 1.21.1 client jar (the dev cache's copy by default) and
written as committed PNGs; faces the green vault shares with the normal one use vanilla's own
texture. The JSON is written from the rules below, so ``--check`` needs no jar. Every
``green=false`` variant of ``assets/minecraft/blockstates/vault.json`` is exactly vanilla.
"""
from __future__ import annotations

import argparse
import colorsys
import io
import json
import math
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets"
DT_TEXTURES = ASSETS / "dungeontrain/textures"
DT_MODELS = ASSETS / "dungeontrain/models"
VAULT_BLOCKSTATE = ASSETS / "minecraft/blockstates/vault.json"
DEFAULT_JAR = Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"

# A pixel that turns takes this green hue, keeping its saturation and brightness.
GREEN_HUE = 120.0 / 360.0
# The vault's lights are its warm (orange) pixels: hues up to this, or wrapping past red.
WARM_HUE_MAX = 70.0 / 360.0
# Pixels this grey or greyer are never lights: the vault's steel and the key's head stay as they are.
MIN_SATURATION = 0.3

# Textures the green vault changes: the lit faces get green lights, the tops the logo.
LIT_TEXTURES = ("vault_front_off", "vault_front_on", "vault_front_ejecting", "vault_side_on")
LOGO_TEXTURES = ("vault_top", "vault_top_ejecting")
# Faces identical to the normal vault: the models point at vanilla's texture.
SHARED_FACES = frozenset({"side_off", "bottom"})

# The Dungeon Train logo in the top's 12x12 panel (pixels 2..13): a ring of four segments split by
# gaps tilted like the logo's, lit from the top-left. Radii and gap width are in pixels.
PANEL = range(2, 14)
CENTRE = 7.5
LOGO_OUTER = 5.6
LOGO_INNER = 2.6
LOGO_INNER_EJECTING = 3.6  # wider, so the ring frames the opening the reward comes out of
LOGO_GAP = 1.0
LOGO_TILT = 30.0
# The top's own blues, darkest to brightest, and the cage interior's darks.
DARKEST, DARK, MID, LIGHT, HIGHLIGHT = "#101d25", "#182c39", "#2a4455", "#406278", "#6b8799"
HOLE_LIT, HOLE = "#251f2e", "#120f1b"

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


def face_texture(face: str) -> str:
    if face in SHARED_FACES:
        return f"minecraft:block/vault_{face}"
    return f"dungeontrain:block/vault_green_{face}"


def block_models() -> dict[Path, dict]:
    out = {}
    for stem, (front, side, top, bottom) in MODELS.values():
        out[DT_MODELS / "block" / f"{green_model_stem(stem)}.json"] = {
            "parent": "minecraft:block/template_vault",
            "textures": {
                "bottom": face_texture(bottom),
                "front": face_texture(front),
                "side": face_texture(side),
                "top": face_texture(top),
            },
        }
    return out


def item_models() -> dict[Path, dict]:
    return {
        DT_MODELS / "item/dungeon_trial_key.json": {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": "dungeontrain:item/dungeon_trial_key"},
        }
    }


def json_outputs() -> dict[Path, str]:
    files = {VAULT_BLOCKSTATE: blockstate(), **block_models(), **item_models()}
    return {path: json.dumps(data, indent=2) + "\n" for path, data in files.items()}


def rgba(hex_colour: str) -> tuple[int, int, int, int]:
    return int(hex_colour[1:3], 16), int(hex_colour[3:5], 16), int(hex_colour[5:7], 16), 255


def is_light(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, a = pixel
    h, s, _ = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return a > 0 and s >= MIN_SATURATION and (h <= WARM_HUE_MAX or h >= 330.0 / 360.0)


def green(pixel: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    r, g, b, a = pixel
    _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    nr, ng, nb = colorsys.hsv_to_rgb(GREEN_HUE, s, v)
    return round(nr * 255), round(ng * 255), round(nb * 255), a


def green_lights(png: bytes, ominous_png: bytes | None = None):
    """``png`` with its lights turned green; with ``ominous_png``, only lights the ominous vault recolours."""
    from PIL import Image

    img = Image.open(io.BytesIO(png)).convert("RGBA")
    pixels = img.load()
    ominous = Image.open(io.BytesIO(ominous_png)).convert("RGBA").load() if ominous_png else None
    for x in range(img.width):
        for y in range(img.height):
            pixel = pixels[x, y]
            if is_light(pixel) and (ominous is None or ominous[x, y] != pixel):
                pixels[x, y] = green(pixel)
    return img


def logo_pixel(x: int, y: int, inner: float, clear_corners: bool) -> str | None:
    """The logo's colour at panel pixel ``(x, y)``, or None to keep the vanilla pixel."""
    dx, dy = x - CENTRE, y - CENTRE
    r = math.hypot(dx, dy)
    if r > LOGO_OUTER + 0.5:
        return DARKEST if clear_corners else None
    if r < inner - 0.5:
        return HOLE_LIT if dx + dy < 0 else HOLE
    angle = (math.degrees(math.atan2(dy, dx)) - LOGO_TILT) % 90
    from_gap = min(angle, 90 - angle) * math.pi / 180 * r  # pixels to the nearest gap line
    if from_gap < LOGO_GAP / 2:
        return DARKEST
    light = -(dx + dy) / max(r, 0.01)  # +1 facing the top-left light
    if from_gap < LOGO_GAP / 2 + 0.9 and r > inner + 0.4:  # a segment's bevelled end
        return HIGHLIGHT if light > -0.3 else LIGHT
    if r > LOGO_OUTER - 0.6:  # outer rim
        return HIGHLIGHT if light > 0.4 else LIGHT if light > -0.4 else MID
    if r < inner + 0.6:  # inner wall, in shadow
        return DARK
    return LIGHT if light > 0.2 else MID


def draw_logo(png: bytes, inner: float, clear_corners: bool):
    from PIL import Image

    img = Image.open(io.BytesIO(png)).convert("RGBA")
    pixels = img.load()
    for x in PANEL:
        for y in PANEL:
            colour = logo_pixel(x, y, inner, clear_corners)
            if colour is not None:
                pixels[x, y] = rgba(colour)
    return img


def write_textures(jar: Path) -> int:
    try:
        import PIL  # noqa: F401
    except ImportError:
        sys.exit("Pillow is required: pip install pillow")
    if not jar.is_file():
        sys.exit(f"vanilla client jar not found: {jar} (pass --jar)")
    block_dir = DT_TEXTURES / "block"
    block_dir.mkdir(parents=True, exist_ok=True)

    def vanilla(name: str) -> bytes:
        return z.read(f"assets/minecraft/textures/{name}.png")

    def dest(name: str) -> Path:
        return block_dir / f"{name.replace('vault', 'vault_green', 1)}.png"

    with zipfile.ZipFile(jar) as z:
        for name in LIT_TEXTURES:
            green_lights(vanilla(f"block/{name}"), vanilla(f"block/{name}_ominous")).save(dest(name))
        # Closed, the panel's corners go dark so the ring stands out against vanilla's ribs; open,
        # vanilla's corner pieces stay around it.
        draw_logo(vanilla("block/vault_top"), LOGO_INNER, True).save(dest("vault_top"))
        draw_logo(vanilla("block/vault_top_ejecting"), LOGO_INNER_EJECTING, False).save(dest("vault_top_ejecting"))
        for face in SHARED_FACES:
            dest(f"vault_{face}").unlink(missing_ok=True)
        key_dir = DT_TEXTURES / "item"
        key_dir.mkdir(parents=True, exist_ok=True)
        green_lights(vanilla("item/trial_key")).save(key_dir / "dungeon_trial_key.png")
    return len(LIT_TEXTURES) + len(LOGO_TEXTURES) + 1


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
    textures = write_textures(args.jar)
    print(f"wrote {len(outputs)} JSON files and {textures} textures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
