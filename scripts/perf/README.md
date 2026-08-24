# Server-tick A/B benchmarking

Measures DT's server tick cost (`avgTickMs`) across a toggle, on a **pinned-seed superflat world**
so the measurement isn't drowned in chunk-generation noise.

```bash
git worktree add ../dt-perf-armB --detach <your-branch>   # once
bash scripts/perf/run-ab.sh ../dt-perf-armB               # ~11 min
```

Arm A = feature OFF (baseline), arm B = ON. Results print at the end; raw logs land in
`scripts/perf/out/`.

## Perf-test mode

The environment half lives in the mod, not here — see
[`PerfTestMode`](../../src/main/java/games/brennan/dungeontrain/perf/PerfTestMode.java).

| | |
|---|---|
| **Superflat** | world preset `dungeontrain:dungeon_train_flat` — fixed layer stack, surface at y=77 so the train sits at its default y=78, no features/lakes/structures |
| **Pinned seed** | `PerfTestMode.DEFAULT_SEED`. The world seed alone pins the **train** seed too — `DungeonTrainWorldData` derives `generationSeed` from it, so there is no second seed to set |
| **Quiet world** | `-PperfTest` applies `doDaylightCycle`, `doWeatherCycle`, `doMobSpawning`, `doFireTick` = false and `randomTickSpeed=0` on server start |

```bash
./gradlew runClient -PperfTest                 # flat, pinned-seed quick-world
./gradlew runClient -PperfTest -PperfSeed=123  # different pinned seed
./gradlew runServer -PperfTest                 # quiet rules; preset+seed from server.properties
```

**A flat world cannot measure DT's worldgen.** `minecraft:flat` bypasses DT's noise settings and
biome-source mixin, so band content (disintegration, the Nether transition) never generates. That
is exactly what makes it a clean baseline for train / AI / physics work — and exactly why you must
not benchmark worldgen changes on it.

## Gotchas

Every one of these produced a confident-looking but **invalid** result before it was understood.

1. **Run it from your own terminal.** The Claude Code harness reaps long-lived background process
   trees (exit 144), killing both servers mid-protocol and firing each arm's cleanup trap. `nohup`
   and `disown` do not help — the process group goes with it. Two runs died this way.
2. **One worktree per arm.** Two servers cannot share `run/`: they clobber each other's world,
   `server.properties` and logs, and contend on the Gradle project lock.
3. **A rider must be aboard.** `TrainCarriageAppender` early-returns when `players.isEmpty()`, so a
   *fully* headless test measures an empty world. This is a headless **server** plus an
   auto-joining client (`-PquickJoin`); the measurement is the server's own `[mspt]` line.
4. **Populate before measuring.** A fresh `/dtp` train is ~95 % PENDING — contents never spawned,
   because the spawn gate needs a player within 48 blocks. The despawn gate deliberately skips those,
   so without a populate walk it holds ~1 mob and no A/B can resolve anything. The walk must stay
   **shorter than the train** (~207 blocks) or the player exits the front, lands on terrain, and
   stops riding.
5. **Off-train offset matters.** 10 blocks sideways keeps the player beside the track with the train
   streaming past. At 30 the train stopped being sustained, drove away, and `[mspt]` — which only
   logs while a train exists — went nearly silent (8 samples instead of 60).
6. **Delete `run/logs/latest.log` before waiting for readiness.** The previous run's `Done (` line
   satisfies the wait instantly; every console command is then typed before the server exists, they
   all vanish, and the run looks like a mysteriously fast success.
7. **Never trust a single ordering.** Run the arms concurrently (what `run-ab.sh` does) or run both
   orderings. Sequentially, whichever arm ran *second* was ~5 ms slower regardless of which it was:
   one pair read **+29 %**, and swapping the order flipped it to **−20 %**. Concurrency removes that,
   at the cost of CPU contention between the two servers — so trust the *sign* of a large delta and
   treat the magnitude as soft.
8. **Honour the validity gates.** `analyze.py` fails an arm on too few samples (train left), or on
   player drift that contradicts the intended on/off-train state. An invalid arm means no result,
   not a smaller one.
9. **`entitiesHeld` is the load check.** If the ON arm barely held anything, a near-zero delta says
   nothing about the feature — only that the scenario never exercised it. The analyzer prints a
   warning below 5.

## Single-arm soak runs

A single arm doubles as a soak rig for anything that needs the server to simply *keep ticking* —
most usefully an **autosave**, which a dev client can rarely reach because the integrated server
pauses whenever the window loses focus (a 40-minute client session once reached only tick 1681).

```bash
MEASURE_SECS=420 VIEW_DISTANCE=20 bash scripts/perf/run-arm.sh soak off . 25571
```

- **`MEASURE_SECS >= 400`** — autosave fires every 6000 ticks = 300 s at 20 tps; the surplus absorbs
  any tick-rate dip so the save actually lands inside the measure window.
- **`VIEW_DISTANCE=20`** — `TrainCarriageAppender` sizes the resident window off view distance, so
  this is the knob that decides how many carriages are resident. The default 12 lands ~18-20; 20
  reaches ~31, which is what you need to push past `PhysicsSubstepTuner`'s `HIGH_WATER = 24` and see
  `[substep-tuner] … 2->1` engage at all. It cannot engage in singleplayer, which caps resident ~22.
- The arm flag is still `contentsdespawn`; passing `off` matches what the populate phase already
  sets, so it is a no-op for a soak.

### A dedicated-server autosave leaves NO log line — check `level.dat` instead

Do not try to detect it in the log. `Saving and pausing game world` is **`IntegratedServer`-only**, a
client string. A dedicated server autosaves via `MinecraftServer.tickServer`, which logs
`Autosave started` through `LOGGER.debug` on **`net.minecraft`** — and that logger sits at INFO in
this harness. DT raises only its own `…dungeontrain.jitter` logger to DEBUG (which is why `[mspt]`
and `[freeze]` are visible), so the autosave lines are filtered out. Verified 2026-08-24: a run that
demonstrably autosaved twice produced **zero** matches for `autosave`, `Saving chunks`, or any
variant.

The reliable, log-independent signal is the **world's `level.dat` mtime**:

```bash
stat -f '%Sm' run/world/level.dat     # rewritten once per autosave
```

Sample it during the run. Successive rewrites ~5 minutes apart (6000 ticks at 20 tps) are autosaves;
a single write at shutdown is just the quit-save. Region `.mca` mtimes are noisier — ordinary chunk
writes touch them too.

## Files

| | |
|---|---|
| `run-ab.sh` | both arms concurrently, rendezvous-synced so the windows share a wall clock |
| `run-arm.sh` | one arm end to end |
| `analyze.py` | `[mspt]` aggregation, controls, validity gates |
| `stdin.gradle` | wires `runServer`'s stdin so the console can be driven from a pipe |

`run/world.old.*` snapshots accumulate (hundreds of MB); prune them yourself when they add up.
