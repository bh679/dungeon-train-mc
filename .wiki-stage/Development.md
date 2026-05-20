# Development

Building, running, and hacking on the mod.

## Requirements

- **JDK 21** (NeoForge 1.21.1 requires it; configured in `build.gradle` as `JavaLanguageVersion.of(21)`. The wrapper uses `org.gradle.jvmargs=-Xmx3G`.)
- Git (optional — only needed so the build can stamp the current branch into
  the jar; see [Version HUD overlay](#version-hud-overlay) below)

On macOS with Homebrew:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
```

> **Pre-v0.83.0 builds:** Forge 1.20.1 builds shipped on JDK 17. Anything you check out from a tag earlier than `v0.83.0` will need the JDK 17 toolchain.

## Build & run

```bash
./gradlew build           # compile and package the mod jar
./gradlew runClient       # launch dev Minecraft client with the mod loaded
./gradlew runServer       # launch dev dedicated server
./gradlew test            # run JUnit 5 unit tests
./gradlew --stop          # stop the gradle daemon if the dev client hangs
```

`./gradlew build` produces `build/libs/dungeontrain-<mod_version>.jar`.

## Version HUD overlay

**Since:** [v0.7.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.7.0) — [PR #11](https://github.com/bh679/dungeon-train-mc/pull/11)

The client always draws a small watermark in the top-left of the screen:

```
Dungeon Train v<mod_version> (<git_branch>)
```

Purpose: when the dev client is launched repeatedly across different feature
branches, the overlay removes any doubt about which build is actually
loaded in-game.

- Rendered by
  [`VersionHudOverlay`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/client/VersionHudOverlay.java)
  via NeoForge's `RegisterGuiLayersEvent` → `registerBelowAll(...)`. (Renamed from `RegisterGuiOverlaysEvent` in the 1.21.1 migration.)
- Client-only — class-gated via
  `@EventBusSubscriber(value = Dist.CLIENT)`, so it is not loaded on
  dedicated servers.
- Respects F1 (hide HUD). F3 debug draws over the top, as intended.
- Values come from a resource file `dungeontrain_version.properties` inside
  the jar, populated at build time — see next section.

### Build-time branch capture

`build.gradle`'s `processResources` task expands two properties into
`src/main/resources/dungeontrain_version.properties`:

| Key | Source | Example |
|---|---|---|
| `version` | `mod_version` from `gradle.properties` | `0.7.0` |
| `branch` | `git rev-parse --abbrev-ref HEAD` at build time | `dev/my-feature` |

Fallbacks on the branch resolver:

- **Detached HEAD** (tag builds, CI checkouts by SHA) → short SHA from
  `git rev-parse --short HEAD`
- **Git unavailable** → literal `unknown`

The resolver runs at gradle configuration time, so `./gradlew build` on a
fresh branch stamps the correct branch into the jar without any extra
steps.

## Dev TitleScreen shortcut

**Since:** [v0.98.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.98.0) — [PR #137](https://github.com/bh679/dungeon-train-mc/pull/137)
**Updated:** [v0.111.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.111.0) — [PR #151](https://github.com/bh679/dungeon-train-mc/pull/151) — flipped the default: **New World** is now the resting button on dev branches, and holding **Shift** restores the vanilla Singleplayer/world-select button. Pre-v0.111.0 the modifier ran the other way (default = Singleplayer, Shift = New World).
**Updated:** [v0.144.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.144.0) — [PR #185](https://github.com/bh679/dungeon-train-mc/pull/185) — added a dedicated **Train Editor** button that combines New-World + auto-`/dungeontrain editor` for one-click "drop me into the editor" iteration. See [Main menu buttons — Discord + Train Editor](Feature-Main-Menu-Buttons) for the full UX and how the auto-open dispatch works.

On dev branches (anything not `main`), the title screen replaces the vanilla **Singleplayer** button with **New World** — clicking it skips `CreateWorldScreen` entirely and drops straight into a fresh creative-mode world on the Dungeon Train default preset, cheats enabled. Each invocation generates a new `Dev World <ts>` save so rapid iteration never collides on existing folder names. Hold **Shift** at any time to swap back to the vanilla Singleplayer button (existing world list).

- Implemented by [`DevQuickWorldHandler`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/client/DevQuickWorldHandler.java) using NeoForge's `ScreenEvent.Init.Post` (button install — *New World* is wired in `visible=true` and the vanilla *Singleplayer* button is set to `visible=false` at install time) + `ScreenEvent.Render.Pre` (per-frame visibility flip from `Screen.hasShiftDown()`).
- Branch-gated identically to the [Version HUD overlay](#version-hud-overlay) — release jars (built with `branch=main` stamped into `dungeontrain_version.properties`) skip the handler entirely, so the title screen looks vanilla.
- Calls `Minecraft.createWorldOpenFlows().createFreshLevel(...)` with `GameType.CREATIVE`, `Difficulty.NORMAL`, `allowCommands=true`, a random seed, and a `Function<RegistryAccess, WorldDimensions>` that resolves the `dungeontrain:dungeon_train` preset (falls back to `WorldPresets.NORMAL` if it ever fails to resolve).
- Compatible with the existing CreateWorldScreen flow — the regular Singleplayer (Shift held) → Create New World path is unchanged. The shortcut is purely additive.

Use case: when a code change needs in-game verification across many quick test cycles (collision tweaks, render tuning, content edits), the shortcut cuts the test loop from ~10 clicks + typing a world name down to a single click — and since v0.111.0, no Shift modifier needed.

## Bootstrap startup performance

**Since:** [v0.207.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.207.0) — [PR #256](https://github.com/bh679/dungeon-train-mc/pull/256) (progress indicator)
**Extended:** [v0.208.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.208.0) — [PR #257](https://github.com/bh679/dungeon-train-mc/pull/257) (chunk + template cache pre-warming, −60% freeze)

Dungeon Train's eager-fill boot sequence (spawning all carriages within render-distance before the player's first frame renders) used to cause a visible ~8 s "stuck at 100%" freeze after first login. Two rounds of optimisation reduced this dramatically:

### World-load progress indicator ([PR #256](https://github.com/bh679/dungeon-train-mc/pull/256))

Eager-fill was moved from `PlayerJoinEvents.tryPlace` into `ServerStartedEvent` so it runs during the Minecraft loading screen before the client renders its first frame. A new `BootstrapProgress` holder + `LevelLoadingScreen` mixin displays phase text on the loading screen while the bootstrap blocks the server thread:

> **Spawning Dungeon Train…** → **Spawning seed train…** → **Anchoring world spawn…** → **Assembling train: X / N**

### Chunk + template cache pre-warming ([PR #257](https://github.com/bh679/dungeon-train-mc/pull/257))

Two pre-warming passes run before the eager-fill spawn loop:

1. **Chunk pre-warm** — every chunk in the eager-fill footprint is fetched via `level.getChunk(cx, cz, ChunkStatus.FULL, true)` once before the spawn loop. This eliminates synchronous world-gen waits inside `clearSubLevelVolume` / `CarriagePlacer.placeAt` (~1 800 ops per `spawnGroup` call, up to 12 calls on first join).

2. **Template cache pre-warm** — `CarriageTemplateStore`, `CarriagePartTemplateStore`, and `CarriagePlacer`'s half-flatbed pad are all warmed at `ServerStartedEvent` before the seed train spawns. Disk-read cost (5–2 500 ms per unique variant) is paid once at load, not amortised across 14+ spawn calls.

**Combined result: ~60% reduction in first-join bootstrap freeze time.** Per-phase timing (`clear / place / assemble / contents / total` ms) is now logged on each `spawnGroup` INFO line.

## `/new-world` — re-roll a fresh world from inside the current world

**Since:** [v0.181.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.181.0) — [PR #227](https://github.com/bh679/dungeon-train-mc/pull/227)

The `/new-world` client command short-circuits the **death-screen → New World** flow so you can re-roll a world without dying or returning to the title screen first. Useful when iterating on world-gen, spawn placement, or any feature that depends on a fresh seed.

```
/new-world          fresh seed, current preset (default)
/new-world fresh    same as the no-arg form (explicit)
/new-world same     re-roll the SAME seed — useful for reproducing a specific bug
```

Implementation: [`NewWorldCommand`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/client/NewWorldCommand.java) (client-only, registered via `RegisterClientCommandsEvent`) calls into the same `DeathScreenLayoutHandler.launchWorld(Screen, boolean)` entry point the death-screen buttons use. That means the **Sable pre-drain runs identically** to the death-screen path — see [Compatibility § Save hangs on `Saving worlds` after rapid world-switch](Compatibility) for the chunk-map race this avoids.

Refuses to run on multiplayer servers (`mc.getSingleplayerServer() == null` → "/new-world only works in singleplayer.").

This command is **always available**, not branch-gated — it's a thin shortcut over an existing UI path, not a separate code path that would diverge from release behavior. The branch-gated dev-iteration shortcut on the title screen is [Dev TitleScreen shortcut](#dev-titlescreen-shortcut) above.

## Authoring carriage templates that ship with the mod

**Since:** [v0.25.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.25.0) — [PR #33](https://github.com/bh679/dungeon-train-mc/pull/33)

The in-world editor can write directly into `src/main/resources/data/dungeontrain/templates/` so author-built carriages get committed and shipped as the mod's bundled defaults. See the [Features wiki](Features#in-world-carriage-template-editor) for the full feature description; the workflow you'll typically follow on a checkout:

1. **Launch the dev client** — `./gradlew runClient`. The source-tree write paths only resolve when the game directory is `<project>/run`, which is what the dev gradle task uses.
2. **Verify dev mode is on** — `/dungeontrain editor status` shows `Dev mode: ON`. **Since [v0.142.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.142.0)** ([PR #183](https://github.com/bh679/dungeon-train-mc/pull/183)) the flag auto-defaults to `on` whenever the source tree is writable (which is exactly the `./gradlew runClient` case), so you no longer need to type `/editor devmode on` before each session. The manual command still works as a mid-session override (`/editor devmode off` pauses auto-promotion); the override resets back to the writable-source default on the next world load. In a packaged jar the flag stays `off` regardless, since source writes are no-ops there anyway.
3. **Build a carriage** — `/dungeontrain editor enter <variant>`, edit blocks inside the bedrock-cage footprint, `/dungeontrain editor save`. With dev mode on, each save writes BOTH `config/dungeontrain/templates/<variant>.nbt` (your local override) AND `src/main/resources/data/dungeontrain/templates/<variant>.nbt` (the bundled default for the next build).
4. **Verify** — `/dungeontrain editor list` shows `bundled: yes` for any variant whose source-tree NBT exists. The mod logs `Wrote bundled template <TYPE> to .../src/main/resources/...` on every successful source write.
5. **Commit the NBTs** — `git add src/main/resources/data/dungeontrain/templates/*.nbt && git commit`. Use the editor only for blueprint changes; the four `.nbt` files belong in the same PR as any code changes that depend on them.

If you didn't have dev mode on while saving, run `/dungeontrain editor promote <variant>` (or `promote all`) afterwards to copy from config-dir into the source tree. Same checkout-only requirement.

The loader walks `config-dir → bundled jar resource → hardcoded legacyPlaceAt` at runtime — see [Storage — three-tier loader](Features#storage--three-tier-loader-v0250) on the Features page for the full chain.

## Authoring weight tweaks that ship with the mod

**Since:** [v0.58.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.58.0) — [PR #73](https://github.com/bh679/dungeon-train-mc/pull/73)

Every in-game weight edit (`/dt editor weight`, `tracks weight`, `contents weight`, the `[-] Weight (N) [+]` HUD row) now writes to **both** the per-install `config/dungeontrain/.../weights.json` (your local override, as before) **and** `src/main/resources/data/dungeontrain/.../weights.json` (the bundled default that ships in the next jar) — no extra command, no `devmode on` toggle.

The auto-promote is gated on `sourceTreeAvailable()`, which only returns true when `FMLPaths.GAMEDIR.getParent()` resolves to a checkout with a writable `src/main/resources/`. End-user installs see no behaviour change — the source tree is unreachable, so the second write short-circuits to a no-op.

Workflow on a checkout:

1. **Launch the dev client** — `./gradlew runClient`. Same source-tree resolution as the template editor.
2. **Tune weights in-game** — use the editor menu's `[-] Weight (N) [+]` row, or `/dt editor weight <variant> <n>`. The success chat line lists only the config path, but the server log shows `Wrote bundled <kind> weights to .../src/main/resources/...` on every successful source write.
3. **Commit the JSON** — `git add src/main/resources/data/dungeontrain/**/weights.json && git commit`. The three weight files (`templates/weights.json`, `contents/weights.json`, `<track-kind>/weights.json`) belong in the same PR as the code that depends on them.

If the source-tree write fails for any reason (read-only mount, file lock), the in-game state stays correct — the config write already succeeded. A warn log surfaces the failure without breaking the command.

Before v0.58.0, dev-mode weight tweaks lived only in `run/config/` (git-ignored), so a session of in-game tuning was silently lost on commit unless you remembered to copy the JSON by hand.

## Authoring part-variant assignments that ship with the mod

**Since:** [v0.60.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.60.0) — [PR #75](https://github.com/bh679/dungeon-train-mc/pull/75)
**Updated:** [v0.144.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.144.0) — [PR #185](https://github.com/bh679/dungeon-train-mc/pull/185) — auto-promote now also covers shipping **custom** carriages (any variant whose `<id>.nbt` exists in `src/main/resources/data/dungeontrain/templates/`, e.g. `pen`, `black`). Previously gated on `CarriageType` enum membership only, which silently dropped pen/black part toggles into the config dir without source-tree write-through.

The same source-tree write-through applies to per-carriage **part assignments** edited via the in-world block-variant menu (Walls / Floor / Roof / Doors panels — Add / Remove / Clear / BumpWeight / CycleSide). On a dev checkout, every menu mutation now writes to BOTH `config/dungeontrain/templates/<id>.parts.json` AND `src/main/resources/data/dungeontrain/templates/<id>.parts.json`, mirroring the weights flow shipped in [PR #73](https://github.com/bh679/dungeon-train-mc/pull/73). The same dual-tier behaviour also applies to the per-variant **contents-allow** sidecars edited via the X-menu's `carriage-contents <variant> <contents> on|off` rows (`<id>.contents-allow.json`).

Restrictions:

- **Shipping variants only** — auto-promote fires when a corresponding `<id>.nbt` is committed to `src/main/resources/data/dungeontrain/templates/`. Covers built-in (`standard`/`windowed`/`flatbed`) AND custom shipping (`pen`/`black`/...) variants. Per-install user-only variants (no shipped NBT) stay config-dir only — correct, since they don't have a shipping home.
- **Dev mode is not required** for parts/contents-allow (unlike the template auto-promote above) — the gate is purely whether `src/main/resources/` is writable, which is true on any `./gradlew runClient` from a checkout.

Look for `Wrote bundled parts assignment <TYPE> to .../src/main/resources/...` in the server log to confirm a write. A menu Clear also propagates to the bundled file via `tryDeleteFromSource`, so deleting a variant in-game doesn't leave a stale promoted file shadowing the cleared state on the next reload.

Before v0.60.0, dev-mode part-variant edits lived only in `run/config/` (git-ignored), so a session of in-game wall/floor/roof/door authoring was silently lost on worktree switch — exactly the same defect [PR #73](https://github.com/bh679/dungeon-train-mc/pull/73) fixed for weights.

## Authoring creative-menu prefabs that ship with the mod

**Since:** [v0.78.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.78.0) — [PR #112](https://github.com/bh679/dungeon-train-mc/pull/112)

Block-variant prefabs (V-key menu's Save button) and loot prefabs (C-key menu's Save button) saved in dev mode now write to BOTH `config/dungeontrain/prefabs/<kind>/<id>.json` (your local override) AND `src/main/resources/data/dungeontrain/prefabs/<kind>/<id>.json` (the bundled default that ships in the next jar). Mirrors the dual-write pattern used by the carriage-template editor.

Workflow on a checkout:

1. **Launch the dev client** — `./gradlew runClient`. Same source-tree resolution as the rest of the editors.
2. **Verify dev mode is on** — `/dungeontrain editor status` shows `Dev mode: ON`. Auto-on by default in `./gradlew runClient` sessions [since v0.142.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.142.0) ([PR #183](https://github.com/bh679/dungeon-train-mc/pull/183)) — manual `/editor devmode on|off` only needed to override the default mid-session.
3. **Save a prefab** — open the V-key (block-variant) or C-key (loot) menu, hit Save, type a name. The action-bar feedback tells you which tiers were written:
   - `Saved prefab 'foo' (→ src)` — both config and source tree (dev mode on, source writable)
   - `Saved prefab 'foo'` — config only (dev mode off)
   - `Saved prefab 'foo' (no src tree)` — dev mode on but running from a packaged jar (e.g. dedicated server)
   - `Saved prefab 'foo' (src write failed)` — config write succeeded but the source-tree file write hit an IO error (rare; check log)
4. **Visual confirmation in the creative tabs** — uncommitted prefabs (config-only) render with a translucent yellow tint behind the icon in the prefab side-tabs. Committed prefabs (in source tree or bundled jar) render normally. The tint is driven by an `NBT_PREFAB_UNCOMMITTED` marker set on the creative-tab `ItemStack` when the server-side `isCommitted()` check returns false.
5. **Commit the JSON** — `git add src/main/resources/data/dungeontrain/prefabs/**/*.json && git commit`. The next mod build packages them as bundled resources for all players.

If you didn't have dev mode on while saving, run `/dungeontrain editor prefab promote <kind> <id>` to copy the config-dir file into the source tree without re-saving in-game. (Or just re-save with dev mode on — it overwrites both tiers cleanly.)

The reload chain is `bundled jar resource` ∪ `config-dir` → server-side `IDS` `TreeSet`. `delete()` only removes the config-dir copy — bundled prefabs are read-only from the running jar's perspective and stay registered.

Before v0.78.0, prefab saves landed only in `run/config/` (git-ignored), so a session of in-game prefab authoring was silently lost on commit unless you copied the JSONs by hand.

## Adding a new bundled template

**Since:** [v0.59.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.59.0) — [PR #74](https://github.com/bh679/dungeon-train-mc/pull/74)

All four template registries — [`CarriageVariantRegistry`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/CarriageVariantRegistry.java), [`CarriageContentsRegistry`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/CarriageContentsRegistry.java), [`CarriagePartRegistry`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/editor/CarriagePartRegistry.java), and [`TrackVariantRegistry`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/track/variant/TrackVariantRegistry.java) — now use a shared [`BundledNbtScanner`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/util/BundledNbtScanner.java) that walks the classpath at server start. **Drop a new `.nbt` into `src/main/resources/data/dungeontrain/...` and it auto-registers** — no manifest update required.

```
src/main/resources/data/dungeontrain/
├── templates/<id>.nbt          # carriage shells       → CarriageVariantRegistry
├── contents/<id>.nbt           # carriage interiors    → CarriageContentsRegistry
├── parts/<kind>/<id>.nbt       # FLOOR/WALLS/DOORS     → CarriagePartRegistry
├── tracks/<id>.nbt             # open-air track tiles  → TrackVariantRegistry
├── pillars/<section>/<id>.nbt  # pillar / stairs       → TrackVariantRegistry
└── tunnels/<kind>/<id>.nbt     # tunnel section/portal → TrackVariantRegistry
```

The scanner handles three URL schemes: `file:` (dev mode, exploded resources under `build/resources/main/`), `jar:` (production builds), and Forge's `union:` (the transformer-pipeline filesystem used during `runClient`). Built-in NBTs (`standard.nbt`, `flatbed.nbt`, `default.nbt`, etc.) are filtered via existing `isReservedBuiltinName` checks before drift comparison so they don't shadow the enum-driven types.

### Legacy manifests (transitional)

Before v0.59.0, each registry maintained its own JSON manifest:

| Registry | Legacy manifest |
|---|---|
| `CarriageVariantRegistry` | `templates/customs.json` |
| `CarriageContentsRegistry` | `contents/customs.json` |
| `CarriagePartRegistry` | `parts/<kind>/manifest.json` (one per kind) |
| `TrackVariantRegistry` | _(never had one — bug; see [Feature-Track-Variants § v0.59.0](Feature-Track-Variants))_ |

These manifests are still in the repo and still read by the scanner — but only for a **drift cross-check**. The scan is authoritative; a WARN fires if any manifest disagrees with what's actually on the classpath:

```
[DungeonTrain] Bundled drift in parts/floor: 'standard' scanned but not in manifest
[DungeonTrain] Bundled drift in parts/walls: 'cracked' scanned but not in manifest
```

If you see drift WARNs after editing the resource tree, either delete the stale manifest entry or commit the new `.nbt`. The manifests will be removed entirely in a follow-up commit once a few releases ship with clean drift logs.

### Implementation note

Forge's `UnionFileSystem` doesn't implement `getPathMatcher`, so the scanner uses `Files.newDirectoryStream(dir)` (no glob) and filters by `.nbt` suffix in code. Don't pass `"*.nbt"` to `newDirectoryStream` from anywhere that might run against a `union:` path — it throws `UnsupportedOperationException` at server start.

## CI/CD

**Since:** [v0.69.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.69.0) — [PR #94](https://github.com/bh679/dungeon-train-mc/pull/94)

Two GitHub Actions workflows run automatically:

### `build.yml` — PR and push validation

Triggers on every PR and every push to `main`. Steps:
1. Check out code with full git history
2. Cache Gradle wrapper + Forge Gradle home
3. `./gradlew build`
4. Upload `dungeontrain-*.jar` as a workflow artifact (downloadable from the Actions tab)

### `release.yml` — tag-driven releases

Triggers on `v*.*.*` tag push, or manually via `workflow_dispatch` with a `tag` input.

Steps:
1. Verify the tag matches `mod_version` in `gradle.properties`
2. Build the release jar
3. **Compute release type** — tag MAJOR = 0 → GitHub Pre-release + Modrinth/CurseForge `beta`; MAJOR ≥ 1 → Release + `release` ([PR #101](https://github.com/bh679/dungeon-train-mc/pull/101))
4. Create the GitHub Release (pre-release flag set automatically)
5. Run `scripts/publish-wiki.sh` to update `Downloads.md` and `Downloads-Archive.md` ([PR #96](https://github.com/bh679/dungeon-train-mc/pull/96))
6. Publish to Modrinth + CurseForge via `Kir-Antipov/mc-publish@v3.3` ([PR #97](https://github.com/bh679/dungeon-train-mc/pull/97))

Steps 5 and 6 emit workflow warnings (not failures) if the required secrets/variables are not configured: `WIKI_TOKEN`, `MODRINTH_TOKEN`, `MODRINTH_PROJECT_ID`, `CURSEFORGE_TOKEN`, `CURSEFORGE_PROJECT_ID`.

To retro-publish an existing tag on Modrinth/CurseForge after adding secrets, trigger `workflow_dispatch` with the tag name.

## Versioning

SemVer lives in `gradle.properties` → `mod_version`. Per the project
convention:

- Every commit during development → **PATCH** bump
- Feature branch squash-merged to `main` (Gate 3) → **MINOR** bump
  (reset patch), tagged `vX.Y.0`
- Breaking save format / API change → **MAJOR** bump

The MINOR bump is a separate commit on `main` after the squash-merge (see
[`10ece02`](https://github.com/bh679/dungeon-train-mc/commit/10ece02) for
an example).
