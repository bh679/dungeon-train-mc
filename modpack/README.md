# Dungeon Train — CurseForge modpack

This directory is the source for the **Dungeon Train** CurseForge _modpack_
([project 1556213](https://www.curseforge.com/minecraft/modpacks/dungeon-train)),
which is distinct from the **mod** ([project 1527512](https://www.curseforge.com/minecraft/mc-mods/dungeon-train)).

The modpack is published automatically — you don't run anything by hand.

## What's in the pack

A CurseForge modpack is a `.zip` of a `manifest.json` (Minecraft version + modloader +
an explicit list of CurseForge mod files) plus an `overrides/` folder. Dungeon Train
bundles **AIN, AIS, PlayerMob, Discord Presence and joml-primitives inside its own jar**
via NeoForge jarJar, so the pack needs only **two required entries** — plus three on-by-default
optional add-ons (AppleSkin, FerriteCore, ModernFix):

| Mod | CF project | Notes |
|---|---|---|
| Dungeon Train | `1527512` | The file ID changes every release — injected at build time. |
| Sable | `1312371` | The only un-bundled runtime dep (PolyForm Shield forbids redistribution). **Pinned** — see below. |
| AppleSkin | `248787` | On-by-default optional add-on (`optional_mods` → `required:false` manifest file = CurseForge "Include"). References AppleSkin's own CF file — not embedded, not redistributed. **Pinned** file ID — see below. |
| FerriteCore | `429235` | On-by-default optional add-on (same "Include" mechanism). Memory-usage reducer (data-structure dedup) — no render/physics/chunk hooks, so safe with Sable. References FerriteCore's own CF file — not embedded, not redistributed. **Pinned** file ID — see below. |
| ModernFix | `790626` | On-by-default optional add-on (`optional_mods` → `required:false` manifest file = CurseForge "Include"). Launch-time / world-load / memory optimiser. References ModernFix's own CF file — not embedded, not redistributed. **Pinned** file ID — see below. |

…plus NeoForge as the modloader (`neoforge-<neo_version>`) and the Minecraft version,
both read from `gradle.properties`.

### Add-ons

Two flavours:

**On-by-default Includes (shipped in the pack, player can deselect at install):**
- **AppleSkin** — food saturation / hunger overlay. Shipped as an `optional_mods` entry →
  `required:false` manifest file, which CurseForge surfaces as an **"Include"**. It references
  AppleSkin's own CurseForge file (not embedded in DT's jar, not redistributed); the launcher
  installs it by default and players can untick it. **Pinned** file ID — see "AppleSkin pin" below.
  Because the pack ships it, AppleSkin is **also** declared as an `appleskin(optional)` dependency
  of the *mod* in `release.yml` — see ["Includes must be mod dependencies"](#includes-must-be-mod-dependencies) below.
- **FerriteCore** — memory-usage reducer (deduplicates blockstates / property maps and other
  internal data structures). Same Include mechanism as AppleSkin (`optional_mods` →
  `required:false`); it touches no rendering, physics, or chunk logic, so it is safe alongside
  Sable and a clear win for this content-heavy pack (DT jarJars AIN/AIS/PlayerMob/Discord
  Presence into its own jar). **Pinned** file ID — see "FerriteCore pin" below. Like AppleSkin it
  is **also** declared as a `ferritecore(optional)` dependency of the *mod* in `release.yml`.
- **ModernFix** — launch-time, world-load and memory optimiser (applies many mixins). Shipped the
  same way as AppleSkin: an `optional_mods` entry → `required:false` manifest file (a CurseForge
  **"Include"**) referencing ModernFix's own CurseForge file — not embedded, not redistributed; the
  launcher installs it by default and players can untick it. **Pinned** file ID — see "ModernFix pin"
  below. Also declared as a `modernfix(optional)` dependency of the *mod* in `release.yml` (see
  ["Includes must be mod dependencies"](#includes-must-be-mod-dependencies)). Its optional *dynamic
  resources* feature is off by default, and individual features are togglable in ModernFix's config
  if anything ever conflicts with Sable or DT's mixins.

**Opt-in relations (declared only, not shipped — players install them themselves):**
Declared as CurseForge **`optionalDependency` relations** (see the table below); they appear
under the pack's "Relations". Nothing here is force-installed.

- **Distant Horizons** — LOD render distance. **Use 2.x** (3.0.x crashes on world entry).
- **Tectonic** — terrain generator. Needs **Compatible Terrain** ON in DT settings to take
  effect (without it DT uses its own raised-floor terrain; DH is render-layer and works
  regardless). Installing Tectonic via CurseForge automatically pulls in its **Lithostitched**
  dependency, so the pack doesn't list Lithostitched itself.
- **Mouse Tweaks** — inventory QoL (shift-drag / scroll-to-move). Relation only.
- **Jade** — block & item tooltip HUD ("what am I looking at"). Relation only. **Known
  limitation:** Jade's tooltip doesn't render for blocks **on the moving train** (a Sable
  sub-level limitation); it works normally everywhere off-train.

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
| `jade` | `optionalDependency` | Opt-in add-on — relation only; tooltips don't render on moving-train blocks (Sable sub-level). |

Keep this list aligned with the mod's own `curseforge-dependencies` in `release.yml`, which
declares `appleskin(optional)` + `ferritecore(optional)` + `modernfix(optional)` +
`distant-horizons(optional)` + `tectonic(optional)` + `mouse-tweaks(optional)` + `jade(optional)`.
The two lists are identical **except for the Includes (AppleSkin, FerriteCore, ModernFix)**: on the
*mod* each is a normal `<slug>(optional)` relation, but in the *modpack* they ship as bundled files,
so CurseForge auto-creates their "Include" relation from the manifest — they must therefore **not**
be repeated in `curseforge_relations`. (Lithostitched is Tectonic's dependency, not DT's, and
CurseForge resolves it automatically — so it appears in neither.)

### Includes must be mod dependencies

**Invariant:** every mod the pack ships as an on-by-default **Include** (`modpack.config.json`
→ `optional_mods`) must **also** be declared as an `<slug>(optional)` dependency of the *mod*
in `release.yml` → `curseforge-dependencies`. The pack is "the mod plus its recommended
companions", so anything it force-bundles should be advertised as an optional dependency on the
mod's own page too. (The reverse does **not** hold — a mod optional dependency need not be a pack
Include; e.g. `mouse-tweaks` / `jade` are declared relations the pack doesn't bundle.)

This is enforced in CI by **`scripts/modpack/check-relations.py`** (run from the `modpack-checks`
job in [`build.yml`](../.github/workflows/build.yml) on every PR). It cross-references each
`optional_mods` entry's `slug` against the `curseforge-dependencies` block and fails the build if
an Include is missing its `<slug>(optional)` relation — the gap that shipped AppleSkin undeclared
in PR #390. So each `optional_mods` entry **must carry a `slug`** (its CurseForge URL slug).

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

## FerriteCore pin

FerriteCore ships the same way — an on-by-default Include pinned by file ID in
`modpack.config.json` → its `optional_mods[]` entry. Like AppleSkin it has **no** DT-version
coupling, so refresh only when you want to ship a newer FerriteCore:

1. Open <https://www.curseforge.com/minecraft/mc-mods/ferritecore/files/all>, filter to the
   NeoForge 1.21.1 build, and copy its numeric file ID from the URL.
2. Set that entry's `file_id` in `modpack.config.json`.

A stale pin just ships an older FerriteCore — harmless, but worth keeping current.

## ModernFix pin

ModernFix ships as an on-by-default Include, pinned by file ID in `modpack.config.json` →
`optional_mods[].file_id` (project `790626`). Like AppleSkin it has **no** DT-version coupling, so it
only needs a refresh when you want to ship a newer ModernFix:

1. Open <https://www.curseforge.com/minecraft/mc-mods/modernfix/files/all>, filter to the
   NeoForge 1.21.1 build, and copy its numeric file ID from the URL.
2. Set the ModernFix `optional_mods[].file_id` in `modpack.config.json`.

A stale pin just ships an older ModernFix — harmless, but worth keeping current.
(Current pin: `modernfix-neoforge-5.27.14+mc1.21.1.jar`.)

## Files

| File | Purpose |
|---|---|
| `modpack.config.json` | Editable config: pack name/author, DT project ID, the pinned Sable project/file/version, `optional_mods` (on-by-default Includes — AppleSkin, FerriteCore, ModernFix; each carries a `slug` for the consistency guard), and `curseforge_relations` (opt-in relations — DH/Tectonic/Mouse Tweaks/Jade). |
| `overrides/` | Copied into the player's instance on install. Empty for now. |
| `../scripts/modpack/build-manifest.py` | Renders `manifest.json` from this config + `gradle.properties` + the release's DT file ID. |
| `../scripts/modpack/check-relations.py` | CI guard: every `optional_mods` Include must also be an `<slug>(optional)` dependency of the mod in `release.yml`. Run by the `modpack-checks` job. |
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
