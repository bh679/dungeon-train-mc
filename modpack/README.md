# Dungeon Train — CurseForge modpack

This directory is the source for the **Dungeon Train** CurseForge _modpack_
([project 1556213](https://www.curseforge.com/minecraft/modpacks/dungeon-train)),
which is distinct from the **mod** ([project 1527512](https://www.curseforge.com/minecraft/mc-mods/dungeon-train)).

The modpack is published automatically — you don't run anything by hand.

## What's in the pack

A CurseForge modpack is a `.zip` of a `manifest.json` (Minecraft version + modloader +
an explicit list of CurseForge mod files) plus an `overrides/` folder. Dungeon Train
bundles **AIN, AIS, PlayerMob, Discord Presence and joml-primitives inside its own jar**
via NeoForge jarJar, so the pack needs only **two required entries** — plus one on-by-default
optional add-on (AppleSkin):

| Mod | CF project | Notes |
|---|---|---|
| Dungeon Train | `1527512` | The file ID changes every release — injected at build time. |
| Sable | `1312371` | The only un-bundled runtime dep (PolyForm Shield forbids redistribution). **Pinned** — see below. |
| AppleSkin | `248787` | On-by-default optional add-on (`optional_mods` → `required:false` manifest file = CurseForge "Include"). References AppleSkin's own CF file — not embedded, not redistributed. **Pinned** file ID — see below. |

…plus NeoForge as the modloader (`neoforge-<neo_version>`) and the Minecraft version,
both read from `gradle.properties`.

### Add-ons

Two flavours:

**On-by-default Include (shipped in the pack, player can deselect at install):**
- **AppleSkin** — food saturation / hunger overlay. Shipped as an `optional_mods` entry →
  `required:false` manifest file, which CurseForge surfaces as an **"Include"**. It references
  AppleSkin's own CurseForge file (not embedded in DT's jar, not redistributed); the launcher
  installs it by default and players can untick it. **Pinned** file ID — see "AppleSkin pin" below.

**Opt-in relations (declared only, not shipped — players install them themselves):**
Declared as CurseForge **`optionalDependency` relations** (see the table below); they appear
under the pack's "Relations". Nothing here is force-installed.

- **Distant Horizons** — LOD render distance. **Use 2.x** (3.0.x crashes on world entry).
- **Tectonic** — terrain generator. Needs **Compatible Terrain** ON in DT settings to take
  effect (without it DT uses its own raised-floor terrain; DH is render-layer and works
  regardless). Installing Tectonic via CurseForge automatically pulls in its **Lithostitched**
  dependency, so the pack doesn't list Lithostitched itself.
- **Mouse Tweaks** — inventory QoL (shift-drag / scroll-to-move). Relation only.

### Declared dependencies (CurseForge "Relations")

The siblings are jarJar'd inside DT, so they must **not** be separate `files` entries (that
would double-load them and break NeoForge). Instead the upload declares them as CurseForge
**relations** — mirroring the mod's own `curseforge-dependencies` in `release.yml` — sourced
from `curseforge_relations` in `modpack.config.json`:

| Slug | Relation | Why |
|---|---|---|
| `sable` | `requiredDependency` | Un-bundled runtime dep (also a `files` entry). |
| `adventure-item-names` | `embeddedLibrary` | jarJar'd inside DT. |
| `adventure-item-stats` | `embeddedLibrary` | jarJar'd inside DT. |
| `interactive-player-mobs` | `embeddedLibrary` | jarJar'd inside DT. |
| `distant-horizons` | `optionalDependency` | Opt-in add-on — relation only (not bundled). |
| `tectonic` | `optionalDependency` | Opt-in add-on — relation only; pulls Lithostitched on install. |
| `mouse-tweaks` | `optionalDependency` | Opt-in add-on — relation only (not shipped). |

Keep this list in sync with the mod's own `curseforge-dependencies` in `release.yml` — which declares `distant-horizons(optional)` + `tectonic(optional)` + `mouse-tweaks(optional)`. (Lithostitched is Tectonic's dependency, not DT's, and CurseForge resolves it automatically — so it appears in neither. AppleSkin is a pack *file*, not a relation, so it is **not** in this list — CurseForge auto-creates its "Include" relation from the manifest.)

## How it deploys (15 min after every mod release)

```
release.yml (real release OR auto-release cascade tick)
  └─ mc-publish uploads the DT jar to CurseForge  → file ID
  └─ dispatches release-modpack.yml with that file ID
        └─ waits 15 min   (lets CurseForge approve the DT file first)
        └─ scripts/modpack/build-manifest.py   → manifest.json
        └─ zip  manifest.json + overrides/      → dungeon-train-<version>.zip
        └─ scripts/modpack/publish-curseforge.sh → uploads to project 1556213
```

The 15-minute wait is the `delay_minutes` input on
[`release-modpack.yml`](../.github/workflows/release-modpack.yml) (default `15`).
Every mod release triggers it — including the ~22 quiet auto-release cascade ticks.

## ⚠️ Sable-pin coupling

`modpack.config.json` pins Sable to the **exact** version Dungeon Train is built and
tested against (`sable_version` in `gradle.properties`). When you bump `sable_version`,
**also update `modpack.config.json` → `sable.file_id`** to the matching CurseForge file:

1. Open <https://www.curseforge.com/minecraft/mc-mods/sable/files/all> and filter to the
   NeoForge build for the new `sable_version` (e.g. `sable-neoforge-1.21.1-1.2.1.jar`).
2. Copy the numeric file ID from its URL (`/files/<id>`).
3. Set both `sable.version` and `sable.file_id` in `modpack.config.json`.

If these drift, the pack ships an old Sable against a newer DT.

## AppleSkin pin

AppleSkin ships as an on-by-default Include, pinned by file ID in `modpack.config.json` →
`optional_mods[].file_id`. Unlike Sable it has **no** DT-version coupling, so it only needs a
refresh when you want to ship a newer AppleSkin:

1. Open <https://www.curseforge.com/minecraft/mc-mods/appleskin/files/all>, filter to the
   NeoForge 1.21.1 build, and copy its numeric file ID from the URL.
2. Set `optional_mods[].file_id` in `modpack.config.json`.

A stale pin just ships an older AppleSkin — harmless, but worth keeping current.

## Files

| File | Purpose |
|---|---|
| `modpack.config.json` | Editable config: pack name/author, DT project ID, the pinned Sable project/file/version, `optional_mods` (on-by-default Includes — AppleSkin), and `curseforge_relations` (opt-in relations — DH/Tectonic/Mouse Tweaks). |
| `overrides/` | Copied into the player's instance on install. Empty for now. |
| `../scripts/modpack/build-manifest.py` | Renders `manifest.json` from this config + `gradle.properties` + the release's DT file ID. |
| `../scripts/modpack/publish-curseforge.sh` | Zips + uploads to CurseForge using the same `CURSEFORGE_TOKEN`. |

## Local test (no upload)

```bash
python3 scripts/modpack/build-manifest.py --dt-file-id 9999999 --version 0.0.0 | python3 -m json.tool
DRY_RUN=1 CURSEFORGE_MODPACK_PROJECT_ID=1556213 \
  scripts/modpack/publish-curseforge.sh --manifest /tmp/manifest.json --tag v0.0.0
```

> Modrinth modpack support is planned later (a `.mrpack` is a different format —
> `modrinth.index.json` + overrides, referenced by download URL + hash). The
> config/builder split here is structured so a `build-mrpack.py` sibling can be added
> without reworking the pipeline.
