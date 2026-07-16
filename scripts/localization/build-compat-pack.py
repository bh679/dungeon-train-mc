#!/usr/bin/env python3
"""
Build a Dungeon Train companion-mod compat resource pack for one locale.

DT bundles eight companion mods (Sable, DiscordPresence, Jade, Distant Horizons, Tectonic,
ModernFix, Controlling, CreativeCore). This pack ships their lang files for <loc> so their
menus/tooltips render in-language. Each mod's key set is fixed — it is read from the committed
``DungeonTrain-es_es-compat.zip`` (every shipped locale uses the same coverage). For each key we
prefer, in order:

  1. the mod's own upstream ``<loc>.json`` inside its jar (native-authored, best quality);
  2. an agent-supplied gap translation (``--gaps <dir>/<mod>.json``);
  3. omission — a resource-pack lang overlay only overrides the keys it contains, so an omitted
     key simply falls back to the mod's bundled English at runtime (no English is hard-copied in).

Deterministic output (sorted names, fixed timestamps) so re-runs produce byte-identical zips.

Usage:
  build-compat-pack.py <loc> --jars <dir> [--gaps <dir>]
      [--keyset-pack modpack/overrides/resourcepacks/DungeonTrain-es_es-compat.zip]
      [--out modpack/overrides/resourcepacks/DungeonTrain-<loc>-compat.zip]
"""
import argparse
import json
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MODS = ["sable", "discordpresence", "jade", "distanthorizons",
        "tectonic", "modernfix", "controlling", "creativecore"]
DEFAULT_KEYSET_PACK = REPO / "modpack/overrides/resourcepacks/DungeonTrain-es_es-compat.zip"
DEFAULT_OUT_DIR = REPO / "modpack/overrides/resourcepacks"
DESC_MOD_LIST = ("Jade, Tectonic, Distant Horizons, Controlling, "
                 "ModernFix, CreativeCore, Sable, DiscordPresence")
FIXED_TS = (2026, 7, 16, 0, 0, 0)   # reproducible zip entry timestamp

# Native display name / region for the pack.mcmeta language block (extend as batches grow).
LANG_META = {
    "de_de": ("Deutsch (Deutschland)", "Deutschland", "Deutsch (Deutschland)"),
    "fr_fr": ("Français (France)", "France", "Français (France)"),
    "it_it": ("Italiano (Italia)", "Italia", "Italiano (Italia)"),
    "pt_pt": ("Português (Portugal)", "Portugal", "Português (Portugal)"),
    "nl_nl": ("Nederlands (Nederland)", "Nederland", "Nederlands (Nederland)"),
    "pl_pl": ("Polski (Polska)", "Polska", "Polski (Polska)"),
    "ru_ru": ("Русский (Россия)", "Россия", "Русский (Россия)"),
    "ja_jp": ("日本語 (日本)", "日本", "日本語 (日本)"),
    "ko_kr": ("한국어 (대한민국)", "대한민국", "한국어 (대한민국)"),
    "zh_tw": ("繁體中文 (台灣)", "台灣", "繁體中文（台灣）"),
}


def load_json_bytes(raw):
    return json.loads(raw.decode("utf-8"))


def read_keyset(keyset_pack):
    """Per-mod ordered key list, taken from the es_es reference pack."""
    keysets = {}
    with zipfile.ZipFile(keyset_pack) as zf:
        for mod in MODS:
            name = f"assets/{mod}/lang/es_es.json"
            try:
                data = load_json_bytes(zf.read(name))
            except KeyError:
                print(f"  ! reference pack missing {name}", file=sys.stderr)
                keysets[mod] = []
                continue
            keysets[mod] = list(data.keys())
    return keysets


def find_mod_jar(jars_dir, mod):
    """Return the jar under jars_dir that contains this mod's en_us lang file."""
    for jar in sorted(Path(jars_dir).glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                if f"assets/{mod}/lang/en_us.json" in zf.namelist():
                    return jar
        except zipfile.BadZipFile:
            continue
    return None


def read_jar_lang(jar, mod, loc):
    if jar is None:
        return {}, {}
    with zipfile.ZipFile(jar) as zf:
        names = set(zf.namelist())
        en = load_json_bytes(zf.read(f"assets/{mod}/lang/en_us.json")) \
            if f"assets/{mod}/lang/en_us.json" in names else {}
        upstream = load_json_bytes(zf.read(f"assets/{mod}/lang/{loc}.json")) \
            if f"assets/{mod}/lang/{loc}.json" in names else {}
    return en, upstream


def build_mod_lang(mod, keyset, upstream, gaps):
    """Assemble the output <loc> map for one mod, preferring upstream then gaps; omit the rest."""
    out, from_upstream, from_gaps, omitted = {}, 0, 0, 0
    for key in keyset:
        if key in upstream and str(upstream[key]).strip():
            out[key] = upstream[key]
            from_upstream += 1
        elif key in gaps and str(gaps[key]).strip():
            out[key] = gaps[key]
            from_gaps += 1
        else:
            omitted += 1
    return out, from_upstream, from_gaps, omitted


def pack_mcmeta(loc):
    name, region, _ = LANG_META.get(loc, (loc, "", loc))
    display = name
    return {
        "pack": {
            "pack_format": 34,
            "description": f"Dungeon Train — {display} translations for bundled companion "
                           f"mods ({DESC_MOD_LIST})",
        },
        "language": {
            loc: {"name": name, "region": region, "bidirectional": False},
        },
    }


def write_zip(out_path, files):
    """files: dict of arcname -> str. Deterministic (sorted, fixed timestamp)."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname in sorted(files):
            info = zipfile.ZipInfo(arcname, date_time=FIXED_TS)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, files[arcname])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("loc")
    ap.add_argument("--jars", required=True, help="dir containing the 8 companion mod jars")
    ap.add_argument("--gaps", default=None, help="dir with agent gap translations <mod>.json")
    ap.add_argument("--keyset-pack", default=str(DEFAULT_KEYSET_PACK))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    loc = args.loc
    out_path = Path(args.out) if args.out else DEFAULT_OUT_DIR / f"DungeonTrain-{loc}-compat.zip"
    keysets = read_keyset(args.keyset_pack)
    gaps_dir = Path(args.gaps) if args.gaps else None

    files = {"pack.mcmeta": json.dumps(pack_mcmeta(loc), ensure_ascii=False, indent=2)}
    total_keys = total_up = total_gap = total_omit = 0
    print(f"Building compat pack for {loc}:")
    for mod in MODS:
        keyset = keysets.get(mod, [])
        jar = find_mod_jar(args.jars, mod)
        _, upstream = read_jar_lang(jar, mod, loc)
        gaps = {}
        if gaps_dir and (gaps_dir / f"{mod}.json").exists():
            gaps = json.loads((gaps_dir / f"{mod}.json").read_text(encoding="utf-8"))
        out, nu, ng, no = build_mod_lang(mod, keyset, upstream, gaps)
        files[f"assets/{mod}/lang/{loc}.json"] = json.dumps(out, ensure_ascii=False, indent=2)
        total_keys += len(keyset)
        total_up += nu
        total_gap += ng
        total_omit += no
        flag = "" if no == 0 else f"  ⚠ {no} English-fallback"
        print(f"  {mod:16s} {len(keyset):4d} keys | upstream {nu:4d} | gaps {ng:4d} | omit {no:4d}"
              f"  (jar: {jar.name if jar else 'NONE'}){flag}")

    write_zip(out_path, files)
    try:
        shown = out_path.relative_to(REPO)
    except ValueError:
        shown = out_path
    print(f"→ {shown}  "
          f"[{total_up}/{total_keys} upstream, {total_gap} gaps, {total_omit} English-fallback]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
