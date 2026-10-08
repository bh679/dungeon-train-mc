"""Item icons and tab backgrounds for the advancement editor, read straight out of the game jars.

The Minecraft client jar is the one NeoForm already extracted for this workspace
(``~/.gradle/caches/neoformruntime/artifacts/minecraft_<version>_client.jar``); mod textures come from
the Gradle module cache. Nothing is downloaded. Block items are drawn as the isometric cube the
inventory shows; a few entity-rendered items (chests, heads) are rebuilt from their entity skins.
An icon that cannot be found renders as a magenta square, and ``missing`` lists it.
"""

from __future__ import annotations

import base64
import io
import re
import zipfile
from pathlib import Path

from PIL import Image

GRADLE = Path.home() / ".gradle" / "caches"

#: Flat (2D) item renders for block items whose inventory icon is the block texture itself.
FLAT_BLOCK_ITEMS = {"oak_sapling", "dandelion", "poppy", "oxeye_daisy", "rail", "powered_rail",
                    "pointed_dripstone", "iron_bars", "crimson_fungus", "wither_rose"}
#: Entity-rendered heads: skin texture under assets/minecraft/textures/.
HEADS = {"player_head": "entity/player/wide/steve", "zombie_head": "entity/zombie/zombie",
         "skeleton_skull": "entity/skeleton/skeleton", "creeper_head": "entity/creeper/creeper",
         "wither_skeleton_skull": "entity/skeleton/wither_skeleton", "dragon_head": "entity/enderdragon/dragon"}
#: Items whose texture file is named differently from the item.
ITEM_ALIASES = {"crossbow": "crossbow_standby", "compass": "compass_00", "clock": "clock_00",
                "recovery_compass": "recovery_compass_00", "white_banner": "white_dye", "yellow_banner": "yellow_dye",
                "red_bed": "red_dye", "white_bed": "white_dye", "shield": "iron_chestplate"}


def find_client_jar(version: str) -> Path | None:
    candidates = [GRADLE / "neoformruntime" / "artifacts" / f"minecraft_{version}_client.jar"]
    candidates += sorted((GRADLE / "fabric-loom" / version).glob("*/*/client-extra.jar"))
    return next((p for p in candidates if p.is_file()), None)


def _mod_jar(pattern: str) -> zipfile.ZipFile | None:
    jars = sorted(GRADLE.glob(f"modules-2/files-2.1/{pattern}"))
    return zipfile.ZipFile(jars[-1]) if jars else None


def png_uri(img: Image.Image) -> str:
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def square(img: Image.Image) -> Image.Image:
    w, h = img.size
    return img.crop((0, 0, w, w)) if h > w else img


def _shade(img: Image.Image, k: float) -> Image.Image:
    r, g, b, a = img.split()
    return Image.merge("RGBA", [c.point(lambda v: int(v * k)) for c in (r, g, b)] + [a])


def _face(out: Image.Image, tex: Image.Image, origin, u_axis, v_axis, shade: float) -> None:
    """Draw ``tex`` as the parallelogram origin + u*U + v*V (u, v in texels) onto ``out``."""
    tex = square(tex).resize((16, 16), Image.NEAREST)
    (a, b), (c, d) = u_axis, v_axis
    det = a * d - b * c
    ia, ib, ic, id_ = d / det, -c / det, -b / det, a / det
    ox, oy = origin
    data = (ia, ib, -(ia * ox + ib * oy), ic, id_, -(ic * ox + id_ * oy))
    face = tex.transform(out.size, Image.AFFINE, data, resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))
    out.alpha_composite(_shade(face, shade) if shade < 1 else face)


def cube(top: Image.Image, left: Image.Image, right: Image.Image) -> Image.Image:
    """The inventory's isometric block, 32×32 (2× the 16px GUI icon)."""
    out = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    s = 1 / 16
    _face(out, top, (16, 1.5), (14 * s, 7 * s), (-14 * s, 7 * s), 1.0)
    _face(out, left, (2, 8.5), (14 * s, 7 * s), (0, 16 * s), 0.8)
    _face(out, right, (16, 15.5), (14 * s, -7 * s), (0, 16 * s), 0.6)
    return out


def flat(img: Image.Image) -> Image.Image:
    return square(img).resize((32, 32), Image.NEAREST)


def tint(img: Image.Image, rgb) -> Image.Image:
    r, g, b, a = img.split()
    return Image.merge("RGBA", [c.point(lambda v, k=k: v * k // 255) for c, k in zip((r, g, b), rgb)] + [a])


class Textures:
    def __init__(self, mc_version: str):
        jar = find_client_jar(mc_version)
        if jar is None:
            raise SystemExit(f"No Minecraft {mc_version} client jar in {GRADLE} — run ./gradlew build once first.")
        self.mc = zipfile.ZipFile(jar)
        self.names = set(self.mc.namelist())
        self.mods = {
            "exposure": _mod_jar("maven.modrinth/exposure/*/*/exposure-*.jar"),
            "exposure_polaroid": _mod_jar("maven.modrinth/exposure-polaroid/*/*/exposure-polaroid-*.jar"),
            "ediblebackpacks": _mod_jar("bh679/ediblebackpacks/*/*/ediblebackpacks-*.jar"),
        }
        self.missing: list[str] = []
        self._icons: dict[str, str] = {}

    # --- raw reads -----------------------------------------------------------

    def mc_img(self, path: str) -> Image.Image | None:
        if path in self.names:
            return Image.open(io.BytesIO(self.mc.read(path))).convert("RGBA")
        return None

    def mc_json(self, path: str):
        import json
        return json.loads(self.mc.read(path))

    def block(self, name: str, *suffixes: str) -> Image.Image | None:
        for s in suffixes:
            img = self.mc_img(f"assets/minecraft/textures/block/{name}{s}.png")
            if img is not None:
                return img
        return None

    def _mod_item(self, ns: str, name: str) -> Image.Image | None:
        jar = self.mods.get(ns)
        if jar is None:
            return None
        if ns == "exposure_polaroid" and name == "instant_camera":
            name = "instant_camera_gui"
        try:
            return Image.open(io.BytesIO(jar.read(f"assets/{ns}/textures/item/{name}.png"))).convert("RGBA")
        except KeyError:
            return None

    # --- icons ---------------------------------------------------------------

    def icon(self, item_id: str) -> str:
        """Data URI of the 32×32 inventory icon for ``namespace:item``."""
        if item_id not in self._icons:
            img = self._render(item_id)
            if img is None:
                self.missing.append(item_id)
                img = Image.new("RGBA", (32, 32), (180, 60, 200, 255))
            self._icons[item_id] = png_uri(img)
        return self._icons[item_id]

    def _entity_cube(self, path: str, top, left, right) -> Image.Image | None:
        sk = self.mc_img(f"assets/minecraft/textures/{path}.png")
        return cube(sk.crop(top), sk.crop(left), sk.crop(right)) if sk else None

    def _render(self, item_id: str) -> Image.Image | None:
        ns, name = item_id.split(":", 1)
        if ns == "dungeontrain":
            # DT's found photo and random books reuse their base item's look.
            return self._render("exposure:photograph" if name == "found_photograph" else "minecraft:written_book")
        if ns != "minecraft":
            img = self._mod_item(ns, name)
            return flat(img) if img else None
        if name in HEADS:
            return self._entity_cube(HEADS[name], (8, 0, 16, 8), (8, 8, 16, 16), (0, 8, 8, 16))
        if name in ("chest", "trapped_chest", "ender_chest"):
            path = "entity/chest/ender" if name == "ender_chest" else "entity/chest/normal"
            return self._entity_cube(path, (14, 0, 28, 14), (14, 14, 28, 28), (0, 14, 14, 28))
        if name == "decorated_pot":
            side = self.mc_img("assets/minecraft/textures/entity/decorated_pot/decorated_pot_side.png")
            return flat(side.crop((1, 0, 15, 14))) if side else None
        if name == "crafter":
            return cube(self.block("crafter", "_top"), self.block("crafter", "_north"), self.block("crafter", "_east"))
        if name == "respawn_anchor":
            side = self.block("respawn_anchor", "_side0")
            return cube(self.block("respawn_anchor", "_top_off"), side, side)
        if name == "grass_block":
            return cube(tint(self.block("grass_block", "_top"), (124, 189, 107)),
                        self.block("grass_block", "_side"), self.block("grass_block", "_side"))
        if name == "lectern":
            return cube(self.block("lectern", "_top"), self.block("lectern", "_front"), self.block("lectern", "_sides"))
        if name == "bookshelf":
            side = self.block("bookshelf", "")
            return cube(self.block("oak_planks", ""), side, side)
        if name == "sunflower":
            return flat(self.block("sunflower", "_front"))
        item = self.mc_img(f"assets/minecraft/textures/item/{ITEM_ALIASES.get(name, name)}.png")
        if item is not None:
            return flat(item)
        base = re.sub(r"_(slab|stairs|wall)$", "", name)
        base = {"stone_brick": "stone_bricks"}.get(base, base)
        if name in FLAT_BLOCK_ITEMS:
            tex = self.block(base, "", "_tip")
            return flat(tex) if tex else None
        top = self.block(base, "_top", "", "_front")
        side = self.block(base, "_side", "", "_front")
        front = self.block(base, "_front", "_side", "")
        return cube(top, front or side, side) if top and side else None

    # --- backgrounds ---------------------------------------------------------

    def background(self, resource: str) -> str | None:
        """Data URI of a background texture given as ``ns:textures/....png``."""
        ns, path = resource.split(":", 1)
        img = self.mc_img(f"assets/{ns}/{path}")
        return png_uri(square(img)) if img else None

    def block_backgrounds(self) -> dict[str, str]:
        """Every opaque, square, non-animated 16px block texture — the "+" background picker."""
        out = {}
        for n in sorted(self.names):
            m = re.fullmatch(r"assets/minecraft/textures/block/([a-z0-9_]+)\.png", n)
            if not m or n + ".mcmeta" in self.names:
                continue
            img = self.mc_img(n)
            if img.size != (16, 16) or img.getextrema()[3][0] < 255:
                continue
            out[f"minecraft:textures/block/{m.group(1)}.png"] = png_uri(img)
        return out
