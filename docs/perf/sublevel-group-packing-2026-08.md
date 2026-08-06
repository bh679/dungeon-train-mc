# Sub-level group packing — does one sub-level per 2 carriage groups buy tick time?

**Status:** protocol ready, measurement pending
**Date:** 2026-08
**Branch:** `dev/sublevel-group-packing-bench`

## The question

A Sable sub-level *is* a carriage group today. `TrainAssembler.spawnGroup` packs
`[BACK half-pad | groupSize × enclosed | FRONT half-pad]` into one sub-level — a 37-block stride at
the defaults (`groupSize=3`, `length=9`, `halfPadLen=5`). Would packing two groups' worth of
carriages into one sub-level improve performance?

Each sub-level costs, independent of how much is in it:

- one Rapier body in the shared per-`ServerLevel` scene;
- one whole Sable **plot** — `SubLevelContainer.DEFAULT_LOG_PLOT_SIZE = 7`, i.e. 128 chunks square,
  of which a 37-block carriage group uses a sliver;
- a `PlotChunkHolder` set and a chunk-ticket-manager entry;
- a pose sync per tick to every tracking client;
- a `TrainTransformProvider` tick, a placement/seam-gap tracker entry, and a group-gap packet.

Halving the sub-level count for the same visible train halves all of that.

## What we already know, and what we don't

Prior profiling (issue #646, shipped as soft-freeze in v0.450.0) measured the **physics** side:
~0.126 ms/body of DT-skippable Java + teleport on stepped bodies, plus ~0.073 ms/body of irreducible
native step on every resident body — roughly 6 ms of a ~22 ms tick at ~47 resident sub-levels.

Halving bodies does **not** halve that. A sub-level twice as long carries twice the colliders, so
only the fixed per-body share drops; the broad-phase share rides along with the collider count. The
genuinely unmeasured quantity is the **fixed per-sub-level overhead** in the list above, which does
halve cleanly — and which may be the larger share. That is what this experiment is for.

**What this experiment cannot answer:** a *moving* train is chunk-generation-dominated (28–290 ms/tick
in earlier profiles), and grouping does nothing to chunk gen. A win here is a win for parked trains,
dedicated servers, and the physics floor under a ride — not for the felt stutter of a long solo run.

## Measurement proxy

`groupSize` is already a live config knob (`CarriageGenerationConfig`, range `[1, 16]`, default 3),
and the whole stride/anchor/appender chain is parameterised on it. So **`groupSize=6` is a valid
performance proxy** for "2 groups per sub-level": identical sub-level count, near-identical block
count — the only difference is that it drops one half-pad pair per 6 carriages, which is a gameplay
difference (the open-air flatbed deck arrives half as often), not a meaningful perf one.

That means the number can be obtained with **zero production code change**.

## Instrumentation (all shipped, no code change)

The `games.brennan.dungeontrain.jitter` namespace is forced to DEBUG on dev builds
(`DungeonTrain.java:300`), so a plain `./gradlew runClient` log already carries everything:

| Line | Source | Gives us |
|---|---|---|
| `[mspt] … avgTickMs= carriages= near= trains=` | `TrainTickEvents.java:323` | server MSPT + resident sub-level count, every 40 t |
| `[freeze] … resident= active= frozen=` | `PhysicsFreezeController.java:126` | how many bodies are actually stepped |
| `[gen.timing] … chunksFulled= …` | `GenProfiler` | fires only when chunks generated — our gen-quiet detector |
| `Spawned group … timing(ms): … total=` | `TrainAssembler.java:583` | per-group placement hitch |

> **Naming trap:** `[mspt] carriages=` counts **sub-levels**, not visual carriages — it is
> `Trains.Carriage` entries, one per `spawnGroup`. The parser reports it as `subLevels`.

Emission order within a tick is fixed — `[gen.timing]` (`:259`) → `[freeze]` (`:292`) → `[mspt]`
(`:323`) — so companion lines always precede the sample they belong to. They are **not** joined on
the timestamp: the three lines can straddle a millisecond boundary (observed live: freeze `.331`,
mspt `.332`).

## Run protocol

Paired in-session A/B in a single world. Only a config value changes between arms, so both arms run
on identical terrain in the same JVM at the same JIT state — this removes the terrain and warmup
confounds that wrecked the earlier fresh-world-per-arm attempts, and it is why the arms are
alternated rather than run as two blocks.

Three known confounds and how the design kills each:

1. **Chunk-gen noise swamps the physics signal.** → Sample with the train **parked** (`speed 0`) on
   already-generated terrain; the parser drops any window carrying a `[gen.timing]` line.
2. **View distance couples train length to sim-bubble churn.** → View distance is untouched, and the
   window is pinned by carriage count so it never auto-sizes off render distance.
3. **Spawn nondeterminism.** → Alternate A/B/A/B/A/B and read the paired deltas, not one pair.

Run it (the client must be launched from a real terminal — the agent harness reaps long-lived clients):

```bash
./gradlew runClient
```

1. New world, Dungeon Train world type, note the seed. Ride/fly forward ~2 min to generate a stretch.
2. `/dungeontrain carriages 36` — pins the rolling window off render distance. 36 divides evenly by
   both 3 and 6, so neither arm pays group-rounding overshoot: expect exactly 12 vs 6 sub-levels.
3. `/dungeontrain speed 0` — park the train.
4. **Arm A:** settings screen → groupSize **3** → `/dungeontrain spawn 36`. Stand still aboard ~90 s.
5. **Arm B:** settings screen → groupSize **6** → `/dungeontrain spawn 36`. Stand still aboard ~90 s.
6. Repeat steps 4–5 twice more.
7. Optional, once per arm: `/dungeontrain debug physicsfreeze off` for 60 s, to split the physics
   share from the rest of the per-sub-level overhead.
8. Quit. The log is `run/logs/latest.log`.

Verify from the log that the arm actually changed — `Spawned group … groupSize=N` states what was
really used. If the settings screen does not take effect live, fall back to one fresh world per arm
at the same seed.

## Analysis

```bash
python3 scripts/perf/parse-dt-perf-log.py run/logs/latest.log --csv windows.csv
```

The parser segments the log on each `/dungeontrain spawn` and labels each segment by the `groupSize`
in its `Spawning train` line, so the arms need no manual bookkeeping. It drops the first 10 windows
after each respawn (`avgTickMs` is `getAverageTickTimeNanos()`, a 100-tick rolling mean that lags a
step change by ~5 windows) and every gen-active window, then prints per-segment stats, the pooled
arm aggregate, the A/B delta with per-rep paired deltas, and the per-group spawn cost.

**Primary metric:** median `avgTickMs` per arm, at matched carriage count.
**Secondary:** per-group `total=` placement ms — the main *cost* risk — and `[freeze] active/frozen`
to confirm the stepped-body counts moved as predicted.

## Decision thresholds

| Parked-floor median MSPT reduction at 36 carriages | Call |
|---|---|
| ≥10% (or ≥2 ms) | Worth a follow-up feature session to build it |
| 5–10% | Borderline — weigh against the gameplay cost of the flatbed-cadence change |
| <5% | Don't build |

If it clears the bar, two shipping forms are on the table (decision deferred until the numbers are in):

- **Pad-preserving** — pack `K` pad-wrapped runs into one sub-level, keeping the flatbed deck every 3
  carriages. Real work in `TrainAssembler.spawnGroup` plus the appender's stride/anchor math. Bonus:
  the internal seam is rigid, so it has no gap at all.
- **Bump `DEFAULT_GROUP_SIZE` 3 → 6** — near-zero code, accepts the halved flatbed cadence.

Either way, if per-group placement ms roughly doubles, `TrainCarriageAppender.MAX_SPAWNS_PER_TICK`
(currently 2) should drop to 1: spawn spikes are what players actually feel.

## Results

_Pending the run. Paste the parser output here, then fill in the analysis and the call._

| arm (groupSize) | sub-levels | windows | median MSPT | p15 | p85 | active bodies |
|---|---|---|---|---|---|---|
| 3 | | | | | | |
| 6 | | | | | | |

### Sanity checks before trusting a number

- Sub-level counts came out at the expected 12 vs 6 — if not, the window was not actually pinned.
- Both arms have ≥30 retained windows; the parser flags thin segments.
- Paired per-rep deltas agree in sign. Three deltas that disagree mean the signal is inside the noise
  — escalate to a dedicated server at view-distance 20 (more resident bodies, bigger signal) rather
  than over-reading a single-player run, which caps residents at roughly 22 sub-levels.
