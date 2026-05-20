# Compatibility

Notes on interactions with [Sable](https://github.com/ryanhcode/sable) (the sub-level physics dependency that replaced Valkyrien Skies in v0.83.0) and any non-obvious 1.21.1 behaviour we've hit. This page is a running log — add an entry each time something bites us.

For the archived Forge 1.20.1 / VS 2.4.11 notes, see [Compatibility (VS legacy)](Compatibility-VS-Legacy).

## Sable 1.2.1+mc1.21.1

All entries below apply to `dev.ryanhcode:sable:1.2.1+mc1.21.1` running on NeoForge 1.21.1 (21.1.228).

### Why Sable?

VS 2 upstream has not shipped a NeoForge 1.21.1 build (as of 2026-04). Sable is actively maintained, ships 1.21.1 builds, and exposes a similar primitive — a "sub-level": a kinematically-driven volume of blocks, separate from the main world chunk grid, that the player can walk around inside while it moves. The migration is one-file behind a `Shipyard` abstraction (`games.brennan.dungeontrain.ship`) so swapping physics backends remains a one-file change for everything else in the codebase.

Cosmetic: Sable's coordinate system has a per-`ServerLevel` "shipyard" region (millions of blocks from origin) where each sub-level's blocks actually live. The `TrainTransformProvider` driver translates between shipyard space and world space.

### `SubLevelContainer.getAllSubLevels()` is asynchronous after assembly

**Symptom:** A `Trains.byTrainId(level)` call right after `TrainAssembler.spawnGroup(...)` returns an empty map (or a map missing the just-spawned group) for one to several server ticks afterward.

**Cause:** Sable registers new sub-levels on the next tick after assembly, not synchronously. `SubLevelContainer.getAllSubLevels()` returns a snapshot that excludes the freshly-created sub-level until Sable's manager pumps.

**Fix patterns:**

- For the **bootstrap → first-login race**: a deferred-retry queue keyed by player UUID, drained on each `LevelTickEvent.Post`. See [`PlayerJoinEvents.onLevelTick`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/PlayerJoinEvents.java) for the canonical implementation. Capped at `MAX_RETRY_TICKS = 100` so a stuck race never hangs the player forever.
- For the **per-tick appender**: an authoritative `Trains.SPAWNED_GROUPS` registry — populated synchronously by `TrainAssembler.spawnGroup` — is the source of truth for "what anchors does this train own?", regardless of whether Sable's `findAll()` has pumped yet. The throttle in [`TrainCarriageAppender`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/TrainCarriageAppender.java) caps spawns at 1 group per tick to avoid bursting past the lazy load.

### Vanilla `setSpawnPoint` / `adjustSpawnLocation` clashes with our cached placement

**Symptom:** First-time players spawn 5–10 blocks off the placement that `TrainBootstrapEvents` carefully cached at server start, leaving a visible jump as our login retry teleports them back.

**Cause:** `ServerPlayer.adjustSpawnLocation(level, sharedSpawnPos)` (the 1.21.1 successor to 1.20.1's `fudgeSpawnLocation`) searches a `spawnRadius=10`-block square around `sharedSpawnPos` via `PlayerRespawnLogic.getOverworldRespawnPos`, which uses the `MOTION_BLOCKING` heightmap and stops on leaves. Our `findGroundY` descends *through* leaves to find the trunk top, so the two heuristics disagree.

**Fix:** [`PlayerFudgeSpawnMixin`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/mixin/PlayerFudgeSpawnMixin.java) injects at HEAD of `adjustSpawnLocation` and short-circuits to the cached placement when one is set, so the `ClientboundLoginPacket` carries our coords directly. See [Feature-Login-Spawn](Feature-Login-Spawn) for the full pipeline.

### `Shipyards.of(level)` must be cached per `ServerLevel`

**Symptom:** Kinematic driver appears attached at assembly time but `getKinematicDriver()` returns `null` a moment later — train doesn't move.

**Cause:** Each `Shipyards.of(level)` call returns a fresh `SableShipyard` wrapping the level's container, and that wrapper holds a `WeakHashMap<ServerSubLevel, SableManagedShip>` cache of `ManagedShip` wrappers. Stateful wrapper data — most importantly the kinematic driver attachment — would be silently lost between calls because each fresh `SableShipyard` starts with an empty wrapper map.

**Fix:** A static per-`ServerLevel` cache in [`Shipyards`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/ship/Shipyards.java) backed by a `WeakHashMap` so unloaded levels don't leak. Always go through `Shipyards.of(level)` — never construct `SableShipyard` directly.

### Per-carriage architecture: each carriage = one sub-level

Pre-v0.83.0, a whole train was a single VS ship with a rolling-window manager that painted carriages in/out of the ship's voxel grid. PR #119 replaced that with a per-carriage architecture: every carriage is its own Sable sub-level, identified by `(trainId, anchorPIdx)`. Implications:

- **No 128-chunk shipyard wall.** Each sub-level has its own shipyard allocation; there's no upper bound on a train's length. The successor-train chain (Feature-Train-Chain) was retired as a result.
- **Per-tick work is bounded by the active group count, not the all-time carriage count.** Old long-session lag from accumulated pIdx drift is gone.
- **Player ↔ sub-level handoff happens at carriage seams.** Sable tracks which sub-level the player is "in" via `getContaining(level, pos)`. The seam between adjacent carriages is mostly invisible to the player but is the validation boundary `DebugCommand.runPair` (the `/dt debug pair` probe) was added to exercise.

### Carriages share `trainId` UUIDs, not `shipId`

Don't key per-train state on `ManagedShip.id()` — that's the per-sub-level id. The grouping key is `TrainTransformProvider.getTrainId()` (a `UUID` shared across all sub-levels of the same train). [`Trains.byTrainId(level)`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/Trains.java) is the canonical aggregation helper.

### `KinematicDriver` is the contract; `TrainTransformProvider` is the implementation

`ManagedShip.getKinematicDriver()` returns a `KinematicDriver` interface. Our `TrainTransformProvider` implements it and adds train-specific state (trainId, pIdx, dims, canonicalPos). When you find a sub-level via `Shipyards.of(level).findAll()`, the canonical pattern is:

```java
for (ManagedShip ship : Shipyards.of(level).findAll()) {
    if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
        // ... your per-carriage work, using provider.getTrainId() etc ...
    }
}
```

Anything that's *not* a `TrainTransformProvider` is some other Sable user (or a probe carriage from `/dt debug pair`); skip it.

### `getCanonicalPos()` returns world-space, `currentWorldPosition()` may not

**Symptom:** Spawn placement code lands the player at coordinates `~20,481,000` (the shipyard offset).

**Cause:** Sable's `currentWorldPosition()` can return shipyard-translation coordinates in some code paths rather than the world-space pivot. Our `TrainTransformProvider.getCanonicalPos()` gives the kinematic driver's authoritative world-space center; use it instead.

**Fix:** [`PlayerJoinEvents.resolveTrainCenter`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/PlayerJoinEvents.java) calls `getCanonicalPos()` first and only falls back to the spawn anchor if the driver hasn't ticked yet.

### Carriage-contents entities live at shipyard-absolute coords — kill-ahead must spare them

**Symptom:** After a Dungeon Train ran for ~30 seconds, animals, villagers, and armor stands stopped showing up in newly visible carriages. Soak diagnostics ([PR #180](https://github.com/bh679/dungeon-train-mc/pull/180)) revealed every armor stand was discarded at `ageTicks=0` — same tick it spawned.

**Cause:** Two interacting things:

1. Sable transforms `Mob` (villagers, animals — entities with AI) into ship-local coords on `addFreshEntity`, so their `getX()` returns small ship-relative numbers. But non-`Mob` `LivingEntity` (e.g. `ArmorStand`) stays at *shipyard-absolute* world coords (e.g. `(20481038, 126, 20489226)`).
2. [`TrainTickEvents.killEntitiesAhead`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/TrainTickEvents.java) sweeps the train's runway each tick. Its "spare interior" filter is `train_aabb.contains(e.getX(), …)` where `train_aabb` is computed in *visible-world* coords. Armor stands at shipyard coords fail that check and get killed. Mobs survive the first tick but eventually drift out of the contains window as the train moves and get killed too.

**Fix:** Every contents-spawned entity is now tagged `dungeontrain_contents_pidx_<N>` (see [`CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/CarriageContentsPlacer.java)). The kill-ahead filter exempts anything carrying that tag prefix. The tag is persistent NBT, so it survives save/load and chunk reload.

**Defensive additions in the same PR:** entity spawn is now deferred until the carriage's placement-collision tracker confirms `placedSuccessfully` (60 clean ticks), and `Mob` instances get `PersistenceRequired=true` so vanilla despawn can't silently remove them either.

**Diagnostics:** opt-in via `/dungeontrain debug contents-entities on` — emits per-entity JOIN/LEAVE log lines with stack trace at LEAVE, plus per-entity spawn lines with UUID. See [`ContentsEntityDiagnostics`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/ContentsEntityDiagnostics.java).

### Save hangs on `Saving worlds` after rapid world-switch — `ChunkMap.hasWork()` never clears

**Symptom:** After clicking **New World** / **Same World** on the death screen (or running `/new-world`), the save sequence sometimes hangs on the `Saving worlds` log line and never reaches `Saving chunks for level …`. The integrated server has to be force-killed; in-memory progress is lost.

**Cause:** Sable's [`dev.ryanhcode.sable.plot.ChunkMapMixin`](https://github.com/ryanhcode/sable) tries to filter `PlotChunkHolder` entries out of vanilla's `ChunkMap.hasWork()` check via a `@Redirect` on `Long2ObjectLinkedOpenHashMap.isEmpty()`. Its own javadoc admits the fix is incomplete: *"TODO: Remove when plot chunks are unloaded with their plots."* When DT sub-levels are removed, Sable evicts them from `getAllSubLevels()` quickly but the `PlotChunkHolder` entries linger in `ChunkMap.updatingChunkMap` until async cleanup completes. If vanilla's `MinecraftServer.stopServer()` wait loop starts before that cleanup finishes, it spins on `chunkMap.hasWork()` returning true forever.

**Upstream tracker:** [ryanhcode/sable#679](https://github.com/ryanhcode/sable/issues/679). DT side tracker: [bh679/dungeon-train-mc#226](https://github.com/bh679/dungeon-train-mc/issues/226).

**Partial mitigation** (since [v0.181.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.181.0)):

- [`DeathScreenLayoutHandler.preDrainTrainSubLevels`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/client/DeathScreenLayoutHandler.java) deletes DT sub-levels and runs `container.tick()` in a wall-clock-bound loop (~1 s) with `Thread.yield()` between ticks BEFORE the disconnect chain — giving Sable's async chunk-map cleanup threads CPU time before the stop sequence begins.
- [`ShipShutdownEvents.onServerStopping`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/ShipShutdownEvents.java) runs a second wall-clock drain (~1 s per Sable-active level) unconditionally on every shutdown — catches the cull-race case where DT sub-levels were already evicted from Sable's list before this event fired.

**Why wall-clock-bound, not tick-count-bound:** `container.tick()` is a synchronous queue-pump that returns instantly when nothing's queued, but Sable's plot-chunk cleanup runs on OTHER threads. A tight tick-count loop completes in milliseconds — too fast for those threads to be scheduled. Wall-clock looping with `Thread.yield()` gives them CPU time.

**Why this is "mitigation" not "fix":** the wall-clock drain reduces hang frequency dramatically but doesn't eliminate it. The proper fix would be a DT mixin into vanilla `ChunkMap.hasWork()` that polls Sable's plot-chunk-holder state directly and returns false when all DT sub-levels are truly cleaned up — tracked in [#226](https://github.com/bh679/dungeon-train-mc/issues/226).

**Cost:** ~3 s extra on every clean shutdown for default 3-dimension worlds. Acceptable trade for the rate of hangs prevented.

**Further hardening in [v0.200.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.200.0) ([PR #248](https://github.com/bh679/dungeon-train-mc/pull/248)):** Diagnostic evidence (run 2026-05-18) showed Sable's own `ChunkMapMixin` filter is incomplete — 910 `PlotChunkHolder` entries leaked into `ChunkMap.updatingChunkMap` and kept vanilla's `stopServer()` wait loop spinning for 60+ seconds (server thread RUNNABLE in `ChunkMap.processUnloads`). The drain in `ShipShutdownEvents.onServerStopping` now reflectively sweeps `updatingChunkMap` after the sub-level drain loop and removes any entry whose class is exactly `dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder`. `hasWork()` then returns `false` and vanilla's wait loop exits. Exact class-name matching leaves vanilla `ChunkHolder` and other-mod subclasses untouched; the reflection is wrapped in try/catch with a single WARN on failure (graceful degradation to pre-fix behaviour). A `ShutdownDiagnostics` watchdog thread was also added to log `hasWork()` state and server-thread stack traces during shutdown, surfacing regressions in future.

**Second-pass drain in [v0.205.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.205.0) ([PR #254](https://github.com/bh679/dungeon-train-mc/pull/254)):** A repro case showed vanilla overworld `ChunkHolder`s (not Sable `PlotChunkHolder`s) can also pin `hasWork() = true` — 2094 holders at `ServerStopping`, vanilla drains 303 in 2 s then stalls at 1791 indefinitely. A second sweep now calls `ServerChunkCache.save(true)` to flush persistent player data before also clearing vanilla holders from `updatingChunkMap`. The `/new-world` command gained a test harness to reproduce rapid-switch hangs in CI.

### Bedrock floor: bypass `LevelChunk.setBlockState` to avoid Sable livelock

**Since:** [v0.204.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.204.0) — [PR #253](https://github.com/bh679/dungeon-train-mc/pull/253)

Dungeon Train overworld noise settings inherit vanilla's noise function (calibrated for `min_y=-64`) but use higher floor levels (32 default, up to 96 in Y-variants). The mismatch leaves vanilla's `bedrock_floor` surface rule with no terrain to convert in deep ocean trenches and aquifer columns, producing holes through to the void.

`BedrockFloorEvents` fixes this by writing bedrock directly into each freshly-generated chunk's bottom section via `LevelChunkSection.setBlockState`, **bypassing `LevelChunk.setBlockState`**. This is deliberate: Sable mixes into `LevelChunk.setBlockState` and, when called from inside a chunk-load completion handler, reads neighbouring chunks via `managedBlock` — which triggers further chunk loads and livelocks the spawn-area preparation sequence.

A complementary `above_bottom: 5 → 1` edit across the 11 noise settings JSONs collapses the surface-rule's randomised bedrock fade, so terrain that does reach the minimum Y gets a clean single-block bedrock layer rather than a probabilistic fade.

**Rule of thumb:** any bedrock or fundamental block placement that happens during chunk-load events should target `LevelChunkSection.setBlockState` directly, not `LevelChunk.setBlockState`.

### Plot-culled pending spawns stall the appender forever

**Symptom:** Train extends normally for ~15 carriages, then a far-edge spawn stops settling and *no* new carriages spawn in that direction for the rest of the session. Server log shows `Appender about to spawn forward=true(N) backward=true(M)` every tick with no actual `Spawned group` lines following.

**Cause:** Two-step interaction with Sable's player-relative plot:

1. The lane-placement gate in [`TrainCarriageAppender.isLanePlacementGateClear`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/TrainCarriageAppender.java) waits for the previous spawn's `placedSuccessfully` flag to flip. That flag flips inside the per-tick `runPlacementCollisionTracker`, whose loop iterates only ships in `Shipyards.findAll()`.
2. When a fresh spawn at the edge of the train drifts outside Sable's plot before reaching 60 clean ticks, Sable transitions it to `SubLevelRemovalReason.UNLOADED` (cached in `HoldingSubLevel` storage, not currently visible). The tracker never sees it again, the flag stays false, and the gate stays closed.

**Fix** ([PR #221](https://github.com/bh679/dungeon-train-mc/pull/221)): the gate now also clears the lane when the pending sub-level has dropped out of `currentTrain` for longer than `CULL_DETECTION_GRACE_TICKS = 60` (matches the normal placement window so registration lag doesn't false-trigger). To prevent a cull-then-clear cascade from spawning unboundedly past the player, the cull-clear path is gated by a one-shot `CULL_CLEARED_FORWARD` / `_BACKWARD` latch — armed only after the next natural `placedSuccessfully` (i.e. the train has caught up). A separate proximity check in `updateTrain` re-arms the latch when the player's pIdx passes the registry edge (on foot or in creative flight), at which point `cleanupGhostAnchors` unregisters every anchor past the visible end *and* calls `Shipyards.delete(ship)` on each one, so Sable's holding-storage entry is dropped (`SubLevelRemovalReason.REMOVED`) and the freshly-respawned anchor can't collide with a resurrected ghost. `needsForward` was also made symmetric with `needsBackward` — the old unconditional `needsForward = true` had relied on the placement gate as a rate limiter, which the cull-clear path removes.

**Sable API notes (collected while building this fix):**
- `SubLevelRemovalReason` is a two-value enum: `UNLOADED` (plot moved away, retained in `HoldingSubLevel` cache, may reload if the plot returns) vs `REMOVED` (gone for good).
- `SubLevelContainer.addObserver(SubLevelObserver)` fires `onSubLevelAdded(SubLevel)` / `onSubLevelRemoved(SubLevel, SubLevelRemovalReason)` / `tick(SubLevelContainer)`. Currently unused; a future refactor could replace the polling-with-grace approach with event-driven cull detection.
- [`SableShipyard.delete`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/ship/sable/SableShipyard.java) calls `SubLevel.markRemoved()` which the container converts to `REMOVED` on its next tick.

**Cosmetic side-effect:** the client occasionally logs `[Sable/ERROR] Received a sub-level movement packet for a non-existent sub-level` clustered ~170ms after each cleanup — a stale-packet race between the server's delete and the client's processing of a few last movement updates. Benign; gameplay is unaffected.

## NeoForge 1.21.1

### `RegisterGuiOverlaysEvent` → `RegisterGuiLayersEvent`

The 1.20.1 client-overlay event was renamed and slightly reshaped. `registerAboveAll` / `registerBelowAll` replace the per-id register call. Affects `VersionHudOverlay`. ([commit `288500f`](https://github.com/bh679/dungeon-train-mc/commit/288500f))

### `TickEvent.LevelTickEvent` / `TickEvent.ClientTickEvent` → split events

`TickEvent` is gone. NeoForge 1.21.1 uses separate per-phase events (`LevelTickEvent.Pre` / `LevelTickEvent.Post`, `ClientTickEvent.Pre` / `Post`). Subscribers must declare which phase they want; there's no `event.phase` field anymore. ([commit `c2ee329`](https://github.com/bh679/dungeon-train-mc/commit/c2ee329))

### `SimpleChannel` → `CustomPacketPayload`

NeoForge dropped Forge's `SimpleChannel` net registration. All packets must implement `CustomPacketPayload`, register via `RegisterPayloadHandlersEvent`, and serialize via `StreamCodec`. ([commit `8fb0550`](https://github.com/bh679/dungeon-train-mc/commit/8fb0550))

### `@Mod.EventBusSubscriber` → `@EventBusSubscriber`

NeoForge cleaned up the event-bus subscriber annotation. Drop the `Mod.` prefix. Only matters when reading older code — the new annotation is in `net.neoforged.fml.common.EventBusSubscriber`.

### `NbtIo` File → Path

`NbtIo.read(File)` / `write(File, ...)` are gone in 1.21.1 — replaced by `Path` overloads. Callers that built `File` objects from world data folders must switch to `Path`. ([commit `504ef03`](https://github.com/bh679/dungeon-train-mc/commit/504ef03))

### Vertex / color builder API rewrite

`vertex(...)` / `color(...)` / `endVertex()` chain on `BufferBuilder` was replaced by `addVertex(...)` / `setColor(...)`. Affects every custom render. ([commit `504ef03`](https://github.com/bh679/dungeon-train-mc/commit/504ef03))

## Mixins

Dungeon Train uses [SpongePowered Mixins](https://github.com/SpongePowered/Mixin) (provided by NeoForge). Active mixins are listed in `src/main/resources/dungeontrain.mixins.json`:

- **Server-side:**
  - `PlayerFudgeSpawnMixin` — overrides `ServerPlayer.adjustSpawnLocation` for first-time login cached-placement (see Sable section above).
  - `DecoratedPotBlockEntityPotLootMixin` — round-trips the `dt_pot_loot` NBT field that the rolled-content writer adds to vases.
- **Client-side:** several `CreateWorldScreen*Mixin`s that wire the "Dungeon Train Options…" button into the World tab and inject the dimension picker, plus `CreativeModeInventoryScreenPrefabMixin` for the prefab side-tabs and `AbstractContainerScreenPrefabTintMixin` for the uncommitted-prefab yellow tint.

If a mixin fails to apply at startup (`InvalidInjectionException` in the FATAL log), the mod won't load — most often the cause is a method rename in a Minecraft / NeoForge minor version, since SpongePowered Mixins target methods by name. Fix by retargeting to the new name; see [`fix(login): retarget fudge mixin to ServerPlayer`](https://github.com/bh679/dungeon-train-mc/commit/e46b826) for the canonical example.
