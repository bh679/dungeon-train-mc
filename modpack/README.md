# Dungeon Train — modpacks (CurseForge + Modrinth)

This directory is the source for the **Dungeon Train** _modpack_, published to two platforms
from one config:

- **CurseForge** ([project 1556213](https://www.curseforge.com/minecraft/modpacks/dungeon-train))
- **Modrinth** ([project `bEFyz3ji`](https://modrinth.com/modpack/dungeon-train-pack-a-lore-rich-roguelite-adventure))

Both are distinct from the **mod** ([CurseForge 1527512](https://www.curseforge.com/minecraft/mc-mods/dungeon-train) /
[Modrinth `dungeon-train`](https://modrinth.com/mod/dungeon-train)).

The modpacks are published automatically — you don't run anything by hand. `modpack.config.json`
is the single source of truth for both; the only platform-specific part is how each pins a mod
file (CurseForge by `project_id` + `file_id`; Modrinth by `modrinth_project` + `modrinth_version`).

## What's in the pack

A CurseForge modpack is a `.zip` of a `manifest.json` (Minecraft version + modloader +
an explicit list of CurseForge mod files) plus an `overrides/` folder. Dungeon Train
bundles only **Discord Presence and joml-primitives inside its own jar** via NeoForge jarJar,
so those never appear as pack entries. The sibling mods **AIN, AIS, PlayerMob and
EnderChestPersistence are un-bundled required downloads** — DT declares them as hard
dependencies so each mod's own project page gets credited for the install — which means the
pack must list them explicitly. Everything else is a manifest file with a `required` flag
(see "Enabled vs disabled by default" below):

| Mod | CF project | In pack | Notes |
|---|---|---|---|
| Dungeon Train | `1527512` | **required** | The file ID changes every release — injected at build time. |
| Sable | `1312371` | **required** | Un-bundled runtime dep (PolyForm Shield forbids redistribution). **Pinned** — see below. |
| Adventure Item Names | `1546573` | **enabled** | Sibling mod, un-bundled hard dep. **Pinned**; floor `adventureitemnames_min_version`. |
| Adventure Item Stats | `1554362` | **enabled** | Sibling mod, un-bundled hard dep. **Pinned**; floor `adventureitemstats_min_version`. |
| Interactive Player Mobs | `1559379` | **enabled** | Sibling mod, un-bundled hard dep. **Pinned**; floor `playermob_min_version`. |
| Ender Chest Persistence | `1579341` | **enabled** | Sibling mod, un-bundled hard dep. **Pinned**; floor `enderchestpersistence_min_version`. |
| AppleSkin | `248787` | **enabled** | Food saturation / hunger overlay. **Pinned** file ID. |
| FerriteCore | `429235` | **enabled** | Memory-usage reducer (data-structure dedup) — no render/physics/chunk hooks, safe with Sable. **Pinned**. |
| ModernFix | `790626` | **enabled** | Launch-time / world-load / memory optimiser. **Pinned**. |
| Sodium | `394468` | **enabled** | Rendering optimiser (FPS). Must be the modern **0.8.x** line — Sable's `neoforge.mods.toml` declares Sodium **incompatible below `0.8.12-alpha.2+mc1.21.1`**; it renders sub-levels through Sodium's modern pipeline. **Pinned** (`0.8.12` stable). |
| Iris Shaders | `455508` | **enabled** | Shader loader, shipped **on with NO shaderpack** — free until a player drops a pack into `shaderpacks/`. **Beta build** (`1.8.14-beta.1`) — the only Iris compatible with Sodium 0.8.x on 1.21.1. Requires **Sodium** (shipped enabled, above). Shaders-on-train ride on Sable's `compatibility.iris.*` mixins. **Pinned**; Iris↔Sodium version-locked. |
| AmbientSounds | `254284` | **enabled** | Immersive ambient / environmental sound engine. Client-side audio only — no render/physics/chunk hooks, safe with Sable. Requires **CreativeCore**. **Pinned**. |
| CreativeCore | `257814` | **enabled** (library) | AmbientSounds' required dependency. Inert library — enabled so AmbientSounds loads on a default install. **Pinned**. |
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

- **Enabled by default, and mandatory (`required:true`)** — the four sibling mods **Adventure
  Item Names**, **Adventure Item Stats**, **Interactive Player Mobs** and **Ender Chest
  Persistence**. These are not companions: DT declares them as hard dependencies and will not
  load without them, so shipping any of them `required:false` (i.e. switched OFF) would break
  the pack outright.
- **Enabled by default (`required:true`)** — AppleSkin, FerriteCore, ModernFix, **Sodium**
  (rendering perf), **Iris** (shader loader, shipped with no shaderpack so it's perf-neutral until a
  player adds one — Iris requires Sodium, which ships enabled above, CreativeCore-style), AmbientSounds,
  Advancement Plaques (QoL / perf / cosmetic companions the pack turns on for everyone), **Kinetic
  Hosting Integration** (partner banner on the multiplayer menu), plus their inert library deps
  **CreativeCore** (AmbientSounds), **Iceberg** (Advancement Plaques) and **Lithostitched**
  (Tectonic). The libraries ship enabled so their dependent loads on a default install
  (CreativeCore — AmbientSounds is on; Iceberg — AP is on) and so enabling an opt-in stays
  one-click (Lithostitched — Tectonic is off, but its lib is already present).
- **Bundled but off by default (`required:false`)** — Mouse Tweaks, Jade, Distant Horizons,
  Tectonic. Shipped in the pack so a player can flip them on with one click, but inert until they
  do. (DT itself + Sable are hardcoded `required:true` in the builder.)

## Declared dependencies (CurseForge "Relations")

Mods jarJar'd inside DT must **not** be separate `files` entries (that would double-load them
and break NeoForge). Discord Presence is the only remaining such mod, and it needs no relation
at all. The upload declares relations from `curseforge_relations` in `modpack.config.json`:

| Slug | Relation | Why |
|---|---|---|
| `sable` | `requiredDependency` | Un-bundled runtime dep (also a `files` entry). |

The sibling mods used to appear here as `embeddedLibrary`. They no longer do: now that they are
un-bundled, each is a manifest `files` entry, and CurseForge auto-creates the relation from the
manifest — so listing them here too would duplicate it (see the rule in the paragraph below).

Everything in `optional_mods[]` (AppleSkin, FerriteCore, ModernFix, Advancement Plaques, Iceberg,
Lithostitched, Mouse Tweaks, Jade, Distant Horizons, Tectonic) is a manifest **file**, so
CurseForge auto-creates its relation from the manifest — these must therefore **not** be repeated
in `curseforge_relations`. (Iceberg is bundled as Advancement Plaques' library dependency;
Lithostitched as Tectonic's.)

### Bundled mods must be mod dependencies

**Invariant:** every mod the pack bundles (`modpack.config.json` → `optional_mods`) must **also**
be declared as a dependency of the *mod* in `release.yml` → `curseforge-dependencies`. The pack
is "the mod plus its recommended companions", so anything it bundles is advertised on the mod's
own page too.

The declared type is **`optional` by default** — regardless of whether the pack ships the mod
enabled (`required:true`) or off (`required:false`), the mod's relationship to a companion is
"optional" either way, because DT runs fine without it.

The exception is the four sibling mods, which DT genuinely cannot run without. They carry
`"dependency_type": "required"` in `modpack.config.json` and are declared `<slug>(required)` in
`release.yml`. That `required` declaration is what makes the CurseForge and Modrinth apps
auto-install them — the whole point of un-bundling.

Note the two flags mean different things and must not be conflated:

| Field | Question it answers |
|---|---|
| `required` | Does the **pack** ship this mod switched on? (AppleSkin: yes) |
| `dependency_type` | Does the **mod** refuse to load without it? (AppleSkin: no) |

[`check-relations.py`](../scripts/modpack/check-relations.py) enforces this per-entry.

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
tested against (`sable_version` in `gradle.properties`). On top of that, the mod itself
**hard-locks** Sable: DT's `neoforge.mods.toml` declares the Sable dependency as
`versionRange="[${sable_mod_version}]"` — an exact-match range — so NeoForge refuses to load
DT against any Sable but the tested one (a swapped or drifted Sable fails at load with a clear
"requires Sable" error instead of silently running against an untested physics engine).

When you bump `sable_version`, **update all four** version fields:

1. `gradle.properties` → `sable_mod_version`: the **bare modId version** Sable declares in its
   own `neoforge.mods.toml` (e.g. `2.0.2` — no `+mc…` suffix). This is what DT's exact lock
   renders to.
2. CurseForge: open <https://www.curseforge.com/minecraft/mc-mods/sable/files/all>, filter to the
   NeoForge build for the new `sable_version` (e.g. `sable-neoforge-1.21.1-2.0.2.jar`), copy the
   numeric file ID from its URL (`/files/<id>`), and set `sable.file_id`.
3. Modrinth: open <https://modrinth.com/mod/sable/versions>, filter to the matching NeoForge 1.21.1
   build, copy the version id from its URL (`/version/<id>`), and set `sable.modrinth_version`.
4. Set `sable.version` (in `modpack.config.json`) to the new `sable_version`.

If these drift, a pack ships an old Sable against a newer DT — or DT refuses to load. CI's
`scripts/modpack/check-pins.py` (in the `modpack-checks` job) enforces the version chain:
`sable_mod_version` must equal `sable_version`'s leading semver, and `modpack.config.json` →
`sable.version` must equal `sable_version`. (It can't derive the CurseForge `file_id` /
Modrinth `modrinth_version` from a version string, so those stay human-maintained.)

## Sibling-mod floors

The same script also guards the four un-bundled siblings, but with a **minimum**, not equality:

    modpack.config.json optional_mods[].version  >=  gradle.properties <gradle_property>

Each sibling entry names the floor it answers to via `gradle_property` (e.g.
`playermob_min_version`). Equality would be wrong here — the auto-release cascade rewrites the
sibling `<mod>_version` values every tick, while the modpack pin and the floor move on a slower,
human cadence. What must hold is simply that the pack never ships a sibling *older* than the
version DT declares it needs, which would make the pack fail to load.

Raising a floor in `gradle.properties` therefore obliges you to refresh that mod's
`version` + `file_id` + `modrinth_version` here, or CI fails.

### Lag warning (advisory)

The same script also **warns** — without failing — when a modpack pin is behind the version DT
builds against:

| Condition | Meaning | Severity |
|---|---|---|
| pin < `<mod>_min_version` | the pack ships a build DT refuses to load | **error** |
| pin < `<mod>_version` | the pack ships older than DT builds against, so modpack players miss whatever changed | **warning** |

It exists because un-bundling changed what a sibling version bump means. While the siblings were
jarJar'd, bumping `<mod>_version` shipped that build to *everyone*. Now modpack players get exactly
the pinned build, so a bump can be announced as shipped while the pack still serves the old one —
which is what happened when AdventureItemStats went to 0.7.0 for an armor-cap fix.

It is a warning rather than an error on purpose: the auto-release cascade bumps `<mod>_version`
~22 times per release cycle, and a guard that failed on each of those would be noise everyone
learns to ignore. Act on it when the bump carried something players were told about.

## Companion-mod pins

Every `optional_mods` entry is pinned for both platforms (`file_id` for CurseForge,
`modrinth_version` for Modrinth). Apart from the siblings above, none have a DT-version coupling,
so a pin only needs a refresh when you want to ship a newer build:

1. CurseForge: open `https://www.curseforge.com/minecraft/mc-mods/<slug>/files/all`, filter to the
   **NeoForge 1.21.1** build, copy its numeric file ID from the URL, set the entry's `file_id`.
2. Modrinth: open `https://modrinth.com/mod/<modrinth_project>/versions`, filter to **NeoForge
   1.21.1**, copy the version id from the URL (`/version/<id>`), set the entry's `modrinth_version`.

Keep the two in sync so both packs ship the same build. A stale pin just ships an older companion
— harmless, but worth keeping current. Caveats:

- **Distant Horizons must stay on 2.x.** DH 3.0.x crashes the JVM on DT world entry (under both
  G1 and generational ZGC). Always pick a `DistantHorizons-2.x…-1.21.1-…` file.
- **AmbientSounds ↔ CreativeCore.** AmbientSounds requires CreativeCore (not jarJar'd inside it) —
  keep the bundled CreativeCore current when bumping AmbientSounds. Both ship `required:true` so
  AmbientSounds works on a default install. (AmbientSounds' jar is large — ~81 MB of bundled audio.)
- **Advancement Plaques ↔ Iceberg.** AP requires Iceberg `[1.2.2,)` (not jarJar'd inside AP) —
  keep the bundled Iceberg at or above that. Both ship `required:true` so AP works on a default install.
- **Tectonic ↔ Lithostitched.** Tectonic requires Lithostitched `[1.6.0,)`; keep the bundled
  Lithostitched file at or above that. Bumping Tectonic? Re-check its `mods.toml` dependency range.

## Files

| File | Purpose |
|---|---|
| `modpack.config.json` | Editable config (drives **both** packs): pack name/author, DT project IDs, the pinned Sable project/file/version + `modrinth_project`/`modrinth_version`, `optional_mods` (every non-core bundled mod, each with a `slug` for the consistency guard, a `required` flag — `true` = enabled by default, `false` = shipped-but-off opt-in — and a `modrinth_project`/`modrinth_version` pin; the four sibling mods additionally carry `dependency_type: required` plus `version` + `gradle_property` for the floor guard), and `curseforge_relations` (sable only). |
| `overrides/` | Config files copied verbatim into the player's instance on install (shared by both packs). Currently ships `config/smoothswapping.json` (tuned Smooth Swapping) and `config/khi.toml` (the Kinetic Hosting affiliate URL + banner text, so every install gets the partner link pre-filled), plus the localization compat packs — see below. |
| `overrides/resourcepacks/DungeonTrain-zh_cn-compat.zip` | zh_cn translations for the bundled **companion** mods (Jade, Tectonic, Distant Horizons, Controlling, ModernFix, CreativeCore, Sable). Dungeon Train's own namespaces (+ AIN/PlayerMob/DiscordPresence) ship their zh_cn `lang/` inside the mod jar, so they're not in here. **Auto-enabled by a client-side one-shot** in the mod (`CompanionResourcePackAutoEnabler`) — it selects this pack the first time it's found and writes a marker so it never fights a player who later disables it. This replaces a shipped `options.txt` (which a launcher would copy wholesale and reset the player's other options); the hook only ever touches the resource-pack selection. |
| `../scripts/modpack/build-manifest.py` | CurseForge: renders `manifest.json` from this config + `gradle.properties` + the release's DT file ID. |
| `../scripts/modpack/build-mrpack.py` | Modrinth: renders `modrinth.index.json` from this config + `gradle.properties` + the release's DT Modrinth version (resolving each pin's URL/hashes from the Modrinth API). `--check-config` validates pins with no network (CI). |
| `../scripts/modpack/check-relations.py` | CI guard: every `optional_mods` entry must also be an `<slug>(optional)` dependency of the mod in `release.yml`. Run by the `modpack-checks` job. |
| `../scripts/modpack/publish-curseforge.sh` | Zips + uploads the CurseForge pack using `CURSEFORGE_TOKEN`. |
| `../scripts/modpack/publish-modrinth.sh` | Zips the `.mrpack` + uploads to Modrinth using `MODRINTH_TOKEN`. |

## Local test (no upload)

CurseForge:
```bash
python3 scripts/modpack/build-manifest.py --dt-file-id 9999999 --version 0.0.0 | python3 -m json.tool
DRY_RUN=1 CURSEFORGE_MODPACK_PROJECT_ID=1556213 \
  scripts/modpack/publish-curseforge.sh --manifest /tmp/manifest.json --tag v0.0.0
```

Modrinth (`--dt-version` is any real DT Modrinth version id; resolving the pins hits the Modrinth API):
```bash
python3 scripts/modpack/build-mrpack.py --dt-version <dt-modrinth-version-id> --version 0.0.0 \
  --output /tmp/modrinth.index.json
DRY_RUN=1 MODRINTH_MODPACK_PROJECT_ID=bEFyz3ji \
  scripts/modpack/publish-modrinth.sh --index /tmp/modrinth.index.json --tag v0.0.0
```

## Modrinth modpack (`.mrpack`)

Modrinth publishes from the **same `modpack.config.json`** but the format differs from CurseForge:
a `.mrpack` is a zip of `modrinth.index.json` + `overrides/`, and each mod is referenced by its
**download URL + sha1 + sha512 + fileSize** (not a `project_id`/`file_id`). Those fields are
resolved at build time from the Modrinth API, from each mod's pinned `modrinth_version`. Because
every bundled mod — including Sable — is on Modrinth, no *mod jars* are bundled into `overrides/`;
only config files and companion-localization resourcepacks are (see the `overrides/` rows above).

```
release.yml (real release OR auto-release cascade tick)
  └─ mc-publish uploads the DT jar to Modrinth  → modrinth-version id
  └─ dispatches release-modpack-modrinth.yml with that version id  (NO approval wait)
        └─ scripts/modpack/build-mrpack.py   → modrinth.index.json
        └─ zip  modrinth.index.json + overrides/  → dungeon-train-<version>.mrpack
        └─ scripts/modpack/publish-modrinth.sh → uploads to project bEFyz3ji
```

Unlike CurseForge there is **no 15-minute wait** — Modrinth versions are available the instant
they upload, so the `.mrpack` can reference the freshly uploaded DT version right away.

### Modrinth pins
Each `optional_mods` entry (and `sable`) carries `modrinth_project` (slug) + `modrinth_version`
(the version id), pinned to the **same build the CurseForge pack ships**. The DT mod is the only
un-pinned reference: its Modrinth version id is passed in per release. Refresh a pin the same way
as a CurseForge pin — open `https://modrinth.com/mod/<slug>/versions`, filter to **NeoForge
1.21.1**, and copy the version id from the version URL (`/version/<id>`). Two slugs differ from
their CurseForge slug: **FerriteCore** is `ferrite-core` and **Distant Horizons** is
`distanthorizons` on Modrinth.

CI guards the pins: `build-mrpack.py --check-config` (in the `modpack-checks` job) fails the build
if any mod is missing its `modrinth_project`/`modrinth_version`, so a new companion can't silently
drop out of the Modrinth pack.

### `env` (client / server)
Modrinth files carry a per-file `env` (`client`/`server` ∈ `required|optional|unsupported`)
instead of CurseForge's single `required` flag. `build-mrpack.py` derives it
([`compute_env`](../scripts/modpack/build-mrpack.py)):

- **client** is always `required` (bundled-enabled) or `optional` (opt-in) — never `unsupported`.
  The pack mirrors CurseForge (every bundled mod is in the player's single-player-capable instance).
  We deliberately ignore a mod's own `client_side=unsupported`: some libraries (e.g. **Lithostitched**,
  a worldgen lib) declare it yet are needed by the integrated server in single-player, and dropping
  them would break the one-click opt-ins that depend on them (Tectonic needs Lithostitched).
- **server** respects `server_side=unsupported`, so genuinely client-only mods (AmbientSounds'
  ~84 MB of audio, the inventory/HUD QoL mods) are skipped on dedicated servers.

### First publish
The Modrinth project starts as a **draft** (it 404s on the public API until it has a published
version). The first release publishes its first version, which enters Modrinth's **modpack review
queue** — the pack won't be publicly listed until Modrinth staff approve it. Subsequent releases
publish without re-review.
