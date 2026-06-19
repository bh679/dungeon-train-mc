# Dungeon Train — CurseForge modpack

This directory is the source for the **Dungeon Train** CurseForge _modpack_
([project 1556213](https://www.curseforge.com/minecraft/modpacks/dungeon-train)),
which is distinct from the **mod** ([project 1527512](https://www.curseforge.com/minecraft/mc-mods/dungeon-train)).

The modpack is published automatically — you don't run anything by hand.

## What's in the pack

A CurseForge modpack is a `.zip` of a `manifest.json` (Minecraft version + modloader +
an explicit list of CurseForge mod files) plus an `overrides/` folder. Dungeon Train
bundles **AIN, AIS, PlayerMob, Discord Presence, ender-chest-persistence and joml-primitives
inside its own jar** via NeoForge jarJar, so those never appear as pack entries. Everything
else is a manifest file with a `required` flag (see "Enabled vs disabled by default" below):

| Mod | CF project | In pack | Notes |
|---|---|---|---|
| Dungeon Train | `1527512` | **required** | The file ID changes every release — injected at build time. |
| Sable | `1312371` | **required** | The only un-bundled runtime dep (PolyForm Shield forbids redistribution). **Pinned** — see below. |
| AppleSkin | `248787` | **enabled** | Food saturation / hunger overlay. **Pinned** file ID. |
| FerriteCore | `429235` | **enabled** | Memory-usage reducer (data-structure dedup) — no render/physics/chunk hooks, safe with Sable. **Pinned**. |
| ModernFix | `790626` | **enabled** | Launch-time / world-load / memory optimiser. **Pinned**. |
| Advancement Plaques | `499826` | **enabled** | Replaces vanilla advancement toasts with fancy plaques. Client-side toast render only — safe with Sable. Requires **Iceberg**. **Pinned**. |
| Iceberg | `520110` | **enabled** (library) | Advancement Plaques' required dependency (`[1.2.2,)`). Inert UI library — enabled so AP loads on a default install. **Pinned**. |
| Lithostitched | `936015` | **enabled** (library) | Tectonic's required dependency (`[1.6.0,)`). Inert worldgen library — enabled so enabling Tectonic stays one-click (no separate lib to toggle). **Pinned**. |
| Mouse Tweaks | `60089` | off (opt-in) | Inventory QoL (shift-drag / scroll-to-move). **Pinned**. |
| Jade | `324717` | off (opt-in) | Block/item tooltip HUD. **Known limitation:** tooltips don't render for blocks **on the moving train** (Sable sub-level). **Pinned** (15.10.5 — the build Sable's bundled Jade compat is verified against). |
| Distant Horizons | `508933` | off (opt-in) | LOD render distance. **Use 2.x** — 3.0.x crashes the JVM on DT world entry. **Pinned** to a 2.x file. |
| Tectonic | `686836` | off (opt-in) | Terrain generator. Needs **Compatible Terrain** ON in DT settings to take effect; its **Lithostitched** dependency ships enabled (above). **Pinned**. |

…plus NeoForge as the modloader (`neoforge-<neo_version>`) and the Minecraft version,
both read from `gradle.properties`.

## Enabled vs disabled by default

⚠️ **In the CurseForge app, a manifest file's `required` flag is the ONLY control over
default state, and there is no "optional but enabled-by-default" option:**

| `"required"` | At install | Result |
|---|---|---|
| `true`  | installed & **enabled** | On out of the box. The player can still disable it in the app afterward, but it is **not** shown as an opt-out checkbox at install. |
| `false` | listed **unchecked** in the optional-mods picker | **Ships OFF.** The player opts in. (CurseForge calls this an "Include".) |

This is the opposite of a common misconception that `required:false` ships on-by-default —
it does **not** (confirmed: [MultiMC #2000](https://github.com/MultiMC/Launcher/issues/2000)).
So a companion we want **on by default must be `required:true`**, and an opt-in companion is
`required:false`.

Each non-core mod is an entry in `modpack.config.json` → `optional_mods[]` carrying its own
`"required"` boolean. [`build-manifest.py`](../scripts/modpack/build-manifest.py) copies that
flag straight into the manifest:

- **Enabled by default (`required:true`)** — AppleSkin, FerriteCore, ModernFix, Advancement
  Plaques (QoL / perf / cosmetic companions the pack turns on for everyone), plus their inert
  library deps **Iceberg** (Advancement Plaques) and **Lithostitched** (Tectonic). The libraries
  ship enabled so their dependent loads on a default install (Iceberg — AP is on) and so enabling
  an opt-in stays one-click (Lithostitched — Tectonic is off, but its lib is already present).
- **Bundled but off by default (`required:false`)** — Mouse Tweaks, Jade, Distant Horizons,
  Tectonic. Shipped in the pack so a player can flip them on with one click, but inert until they
  do. (DT itself + Sable are hardcoded `required:true` in the builder.)

## Declared dependencies (CurseForge "Relations")

The jarJar'd siblings are bundled inside DT, so they must **not** be separate `files` entries
(that would double-load them and break NeoForge). Instead the upload declares them as CurseForge
**relations** — sourced from `curseforge_relations` in `modpack.config.json`:

| Slug | Relation | Why |
|---|---|---|
| `sable` | `requiredDependency` | Un-bundled runtime dep (also a `files` entry). |
| `adventure-item-names` | `embeddedLibrary` | jarJar'd inside DT. |
| `adventure-item-stats` | `embeddedLibrary` | jarJar'd inside DT. |
| `interactive-player-mobs` | `embeddedLibrary` | jarJar'd inside DT. |

Everything in `optional_mods[]` (AppleSkin, FerriteCore, ModernFix, Advancement Plaques, Iceberg,
Lithostitched, Mouse Tweaks, Jade, Distant Horizons, Tectonic) is a manifest **file**, so
CurseForge auto-creates its relation from the manifest — these must therefore **not** be repeated
in `curseforge_relations`. (Iceberg is bundled as Advancement Plaques' library dependency;
Lithostitched as Tectonic's.)

### Bundled mods must be mod dependencies

**Invariant:** every mod the pack bundles (`modpack.config.json` → `optional_mods`) must **also**
be declared as an `<slug>(optional)` dependency of the *mod* in `release.yml` →
`curseforge-dependencies`. The pack is "the mod plus its recommended companions", so anything it
bundles is advertised as an optional dependency on the mod's own page too — regardless of whether
the pack ships it enabled (`required:true`) or off (`required:false`); the mod's relationship to
the companion is "optional" either way.

This is enforced in CI by **`scripts/modpack/check-relations.py`** (the `modpack-checks` job in
[`build.yml`](../.github/workflows/build.yml), on every PR). It cross-references each
`optional_mods` entry's `slug` against the `curseforge-dependencies` block and fails the build if
a bundled mod is missing its `<slug>(optional)` relation — the gap that shipped AppleSkin
undeclared in PR #390. So each `optional_mods` entry **must carry a `slug`** (its CurseForge URL
slug). The library deps are bundled too, so they are likewise declared `iceberg(optional)` (for
Advancement Plaques) and `lithostitched(optional)` (for Tectonic).

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
   NeoForge build for the new `sable_version` (e.g. `sable-neoforge-1.21.1-2.0.2.jar`).
2. Copy the numeric file ID from its URL (`/files/<id>`).
3. Set both `sable.version` and `sable.file_id` in `modpack.config.json`.

If these drift, the pack ships an old Sable against a newer DT.

## Companion-mod pins

Every `optional_mods` entry is pinned by `file_id`. Unlike Sable none of them have a
DT-version coupling, so a pin only needs a refresh when you want to ship a newer build:

1. Open `https://www.curseforge.com/minecraft/mc-mods/<slug>/files/all`, filter to the
   **NeoForge 1.21.1** build, and copy its numeric file ID from the URL.
2. Set that entry's `file_id` in `modpack.config.json`.

A stale pin just ships an older companion — harmless, but worth keeping current. Two caveats:

- **Distant Horizons must stay on 2.x.** DH 3.0.x crashes the JVM on DT world entry (under both
  G1 and generational ZGC). Always pick a `DistantHorizons-2.x…-1.21.1-…` file.
- **Advancement Plaques ↔ Iceberg.** AP requires Iceberg `[1.2.2,)` (not jarJar'd inside AP) —
  keep the bundled Iceberg at or above that. Both ship `required:true` so AP works on a default install.
- **Tectonic ↔ Lithostitched.** Tectonic requires Lithostitched `[1.6.0,)`; keep the bundled
  Lithostitched file at or above that. Bumping Tectonic? Re-check its `mods.toml` dependency range.

## Files

| File | Purpose |
|---|---|
| `modpack.config.json` | Editable config: pack name/author, DT project ID, the pinned Sable project/file/version, `optional_mods` (every non-core bundled mod, each with a `slug` for the consistency guard and a `required` flag — `true` = enabled by default, `false` = shipped-but-off opt-in), and `curseforge_relations` (sable + the jarJar'd siblings). |
| `overrides/` | Copied into the player's instance on install. Empty for now. |
| `../scripts/modpack/build-manifest.py` | Renders `manifest.json` from this config + `gradle.properties` + the release's DT file ID. |
| `../scripts/modpack/check-relations.py` | CI guard: every `optional_mods` entry must also be an `<slug>(optional)` dependency of the mod in `release.yml`. Run by the `modpack-checks` job. |
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
