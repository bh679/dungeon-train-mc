# Sub-level group packing — does one sub-level per 2 carriage groups buy tick time?

**Status:** measured 2026-08-06 — no measurable win, recommendation is DON'T BUILD (see Results)
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

The arm is flipped with `/dungeontrain debug groupsize <n>` (added for this bench so the A/B can be
driven headlessly — it delegates to the same `DungeonTrainConfig.setGroupSize` the settings screen
uses, and only affects groups spawned after it).

> **Naming trap:** `[mspt] carriages=` counts **sub-levels**, not visual carriages — it is
> `Trains.Carriage` entries, one per `spawnGroup`. The parser reports it as `subLevels`.

Emission order within a tick is fixed — `[gen.timing]` (`:259`) → `[freeze]` (`:292`) → `[mspt]`
(`:323`) — so companion lines always precede the sample they belong to. They are **not** joined on
the timestamp: the three lines can straddle a millisecond boundary (observed live: freeze `.331`,
mspt `.332`).

## Run protocol

**A fresh world per arm, regenerated from the same seed.** Every arm gets identical terrain, an
identical spawn position and identical carriage content, leaving `groupSize` as the only variable.

The cheaper design — alternating arms inside one world — was tried first and **must not be used**.
Each `/dungeontrain spawn` walks the train to a new position, so every arm sits on different terrain
and draws different carriage variants (both deterministic on position + seed, both confounded with
the thing under test). It produced two paired reps that disagreed in *sign* (+10.2 ms and −35.4 ms),
and the same arm measured 47 ms, 44 ms and 5.78 ms in one session. Its `run` mode is kept for quick
pokes only.

Pinning the arm before boot is possible because `groupSize` lives in
`run/config/dungeontrain-server.toml`, which is **global, not per-world** — so the world-creation
train is already correct and no in-world respawn is needed to apply it.

Four confounds and how the design handles each:

1. **Chunk-gen noise swamps the signal.** → Train parked (`speed 0`); the parser drops any window
   carrying a `[gen.timing]` line.
2. **View distance couples train length to sim-bubble churn.** → View distance fixed at 20, window
   pinned by carriage count so it never auto-sizes off render distance.
3. **Post-respawn fill.** → The parser keeps only windows at the segment's settled sub-level count.
4. **Rider loss.** → The train is culled outright the moment the rider drops. Each cycle asserts the
   rider is still online at the end of its dwell and marks itself invalid otherwise. This is not
   hypothetical: a client exited mid-run, the train culled to 1 sub-level, and that arm would
   otherwise have folded an emptying world into its median.

```bash
python3 scripts/perf/run-ab.py setup
python3 scripts/perf/run-ab.py matrix --reps 3 --arms 3,6 --carriages 48 --dwell 90
```

`matrix` owns both JVMs, cycling: reset world → pin the arm in the TOML → boot server → join rider →
spawn → wait for the resident count to settle → dwell → stop. Ports are 25571 (game) / 25581 (RCON),
deliberately not the defaults, since a sibling worktree's dev server sits on 25565. Old worlds are
**moved** to `run/bench-archive/`, never deleted (`rm -rf` is deny-listed in this repo).

Use `--tag` for a second run (e.g. a counterbalanced `--arms 6,3`) or it overwrites the first run's
per-cycle logs.

Verify from the log that the arm actually took — `Spawned group … groupSize=N` states what was used.

## Analysis

`matrix` captures each cycle's server stdout separately, which is what you parse:

```bash
python3 scripts/perf/parse-dt-perf-log.py run/logs/ab/rep*-arm*-server.log --csv windows.csv
```

> **Never `latest.log`.** `[mspt]` and `[freeze]` are DEBUG lines; `latest.log` is INFO-only and
> contains **zero** of them, so pointing the parser at it yields an empty analysis that looks like a
> failed run. `debug.log` does have them, but the server and the rider client share `run/` and both
> write it — which is why `matrix` captures per-cycle stdout instead.

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

## Results — 2026-08-06

Dedicated server, view-distance 20, `--carriages 48`, 90 s dwell, 3 reps × 2 arms, fresh world from
seed `dtbench` per arm. 6/6 cycles valid; 151 and 146 retained windows.

| arm (groupSize) | sub-levels | carriages | median MSPT | p15 | p85 | ms/carriage |
|---|---|---|---|---|---|---|
| 3 | 17 | 51 | **19.26** | 16.68 | 20.69 | 0.378 |
| 6 | 10 | 60 | **19.23** | 18.56 | 20.33 | 0.321 |

**Raw difference: −0.03 ms (−0.2%). That is a null.**

Per-rep medians, which are the honest way to read it:

| rep | arm 3 | arm 6 | paired delta |
|---|---|---|---|
| 1 | 19.75 | 18.71 | −1.04 |
| 2 | 19.80 | 19.12 | −0.68 |
| 3 | 16.79 | 19.91 | **+3.12** |

The deltas do not agree in sign, and the **within-arm** spread (arm 3: 16.79–19.80, a 3.01 ms range
across identical same-seed worlds) is ~100× the between-arm difference. The effect, if any, is below
this rig's resolution.

### What that actually means

Halving the sub-level count did **not** reduce server tick time — but it did not raise it either,
while carrying **18% more carriages**. So the fixed per-sub-level overhead (plot, tickets, pose sync,
provider tick) is real but small, and here it roughly cancelled the cost of 9 extra carriages of
content. The dominant term is content-proportional, not per-sub-level — which is the opposite of the
premise the experiment was built on.

Note the arms could not be load-matched by pinning: the appender rounds the window outward to group
boundaries, so a bigger group overshoots further (51 vs 60 carriages off the same `--carriages 48`).
That over-fetch is not a measurement artifact — it is a real property the change would ship with.

### The one clear, repeatable difference: spawn spikes

| arm | groups placed | median total | p95 total | per carriage |
|---|---|---|---|---|
| 3 | 102 | 24.0 ms | 80 ms | 8.0 ms |
| 6 | 58 | **39.5 ms** | **119 ms** | 6.6 ms |

Placing one group costs 1.6× more, and the p95 spike is 1.5× worse. Per carriage it is cheaper, but
players feel the spike, not the average — and `TrainCarriageAppender.MAX_SPAWNS_PER_TICK` is 2, so a
bad tick can place two of them.

### Call: don't build it

Against the thresholds above (≥10% to justify a feature session, <5% = don't build), a −0.2% raw
result with an inconsistent sign is a clear no. Packing 2 groups per sub-level would buy no
measurable server tick time, cost a 1.5× worse spawn hitch, over-fetch ~18% more carriages than
asked for, and — in the `DEFAULT_GROUP_SIZE` form — halve the open-air flatbed cadence.

### Limitations, stated plainly

- **Order was not counterbalanced.** Arm 3 ran first in every rep. That cannot manufacture a null,
  so it does not threaten this conclusion — but it would have to be fixed before claiming a win.
- **Rig noise floor is ~±1.5–3 ms**, so a real effect smaller than ~15% would not be visible here.
  A quieter box (no rider client sharing the CPU) would resolve more.
- **Single load point per arm.** To get the marginal cost of a carriage under each grouping, sweep
  several pinned window sizes per arm and fit the slope — that is the experiment to run if the
  question ever comes back.
- **Server-side only.** Fewer sub-levels may still help the *client* (fewer render setups, fewer
  pose syncs); nothing here measures frame time.
