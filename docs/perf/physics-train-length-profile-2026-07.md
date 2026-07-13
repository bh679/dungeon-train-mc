# Sable physics vs train length — profile & recommendation (2026-07)

Issue #646 remainder. Question: can DT cut the Sable **physics** server-tick cost that
scales with carriage count, and is it worth doing for the felt long-ride lag?

**Headline: no new physics work is warranted.** Profile-first measurement shows the
per-carriage physics cost is already small after the shipped optimisations (#640, #648,
#742), and the felt long-moving-ride lag is dominated by **chunk generation**, not
physics. Details below.

## Method

- Build `0.451.1` (adds `active=` to the `[mspt]` DEBUG line). Headless dedicated
  `runServer`, pinned seed, `view-distance=20`, RCON. Harness per
  `project_dt_server_perf_ab_harness`.
- Metric: vanilla `tick query` / `[mspt]` `avgTickMs` (`getAverageTickTimeNanos`,
  whole-server mean incl. Sable physics), matched-toggle A/B via
  `/dungeontrain debug physicsfreeze on|off` over RCON.
- Fixture: a **25-sub-level train, parked, player-less** (the cleanest physics isolation
  — no rider, no forward chunk-gen, no DT train-work). The substep tuner (#648) engaged
  (`2->1` at 25 residents), so these are the **shipping** substep=1 numbers.

## Run B — per-body physics cost (25 bodies, substep=1)

| Freeze | State | avgTickMs (settled) |
|---|---|---|
| ON  | all 25 parked (bodies still native-stepped) | **~4.75** |
| OFF | all 25 fully stepped | **~7.9** |

- Δ = 3.15 ms / 25 = **0.126 ms/body** — the DT-skippable Java per-body work + DT's
  per-tick teleport (what soft-freeze #742 removes).
- Native-step residual (body in-scene, kinematically stepped, DT-irreducible) cross-checked
  against #742's own hard-vs-soft A/B = **~0.073 ms/body**.
- **Full stepped cost ≈ 0.20 ms/body**, of which **~63% is DT-skippable, ~37% is the
  irreducible native `Rapier3D.step`** (only an upstream Sable per-body disable removes it;
  DT removing the body = the abandoned hard-freeze uncatchable JVM abort).
- Parked-floor base (server tick minus all 25 bodies' native step) ≈ **2.9 ms**.

Extrapolated to a **63-carriage** train (the felt-lag size): physics ≈ 63 × 0.20 ≈
**12.6 ms**, + ~2.9 ms base ≈ **~15 ms** of server tick. DT-skippable part = 63 × 0.126 ≈
**7.9 ms**; irreducible native = 63 × 0.073 ≈ **4.6 ms**.

## The `active ≈ resident` gap — proven from code (linchpin)

`TrainCarriageAppender.bootstrapTargetFromServerViewDistance` sizes the resident
force-load target as `(viewDistance × 16 × 2) / carriageLength`
([TrainCarriageAppender.java:3301](../../src/main/java/games/brennan/dungeontrain/train/TrainCarriageAppender.java)),
and the steady-state hold is the same **render-distance-bounded near-player window**
([:2344](../../src/main/java/games/brennan/dungeontrain/train/TrainCarriageAppender.java)).
That is the **identical span a client tracks at that view distance**. Therefore on a
normally-ridden train **every resident carriage is tracked → `active` ≈ `resident`**, and
#742's "park untracked carriages" parks **~none** of the active train. Confirmed by the
`active=` instrumentation on the player-less fixture (`active=0` when nothing tracks).

> Live long **moving** ride was not reproducible headlessly: the auto-join rendering
> client connects only briefly on macOS and the spawn-deck hand-off fails without real
> player input, collapsing the train to a 1-car stub. The `active≈resident` claim rests on
> the code proof above (stronger than a flaky reading); the felt moving-ride MSPT figures
> below are from a prior **real interactive** session (`project_transition_zone_gen_profile`).

## Why physics is NOT the felt moving-ride bottleneck

- Prior real-ride profile: `avgTickMs` ~**45–60 ms** at 55–63 carriages.
- This profile's physics floor at 63 carriages (substep-tuned, shipping): ~**15 ms**.
- The remaining ~**30–45 ms** is chunk generation + DT per-tick train-work (appender /
  kill-ahead / fluid displacement) that only exist on a *moving* train. This matches #742's
  direct finding: a matched freeze ON-vs-OFF toggle **on the moving run washed to ~0 net**,
  swamped by gen.
- The old "63 × 0.65 ms ≈ 41 ms physics" estimate (`project_sable_physics_shared_scene_resident_scaling`)
  predates the substep tuner (that was 2 substeps) and the #640 overlay fix. The
  **0.65 ms/body figure is superseded by 0.20 ms/body** at the shipping substep=1.

## Levers, measured payoff, verdict

| Lever | Helps moving ride? | Max payoff @63car | Risk | Verdict |
|---|---|---|---|---|
| **Velocity-gated distance park** (DT-side, safe) | No — parks only stationary carriages; a moving train's carriages must keep teleporting or they visibly desync | 0 on moving; extends #742 to parked/server long trains only | Low | **Defer** — #742 already covers the parked/server case; marginal |
| **Upstream Sable `set_enabled`/sleep** (skip native step for tracked-but-distant) | Partially — the only thing that touches the native floor on a moving train | ≤ 4.6 ms (the irreducible native part) | Cross-repo, depends on Sable author; DT ships stock Modrinth 2.0.2 (no fork) | **File as low-priority upstream request** — small absolute win |
| **Risky sim-decouple** (keep teleport, skip Java) | Barely — pose read-back needed for visuals must stay | < 7.9 ms, minus the read-back you must keep | Visual desync; high effort | **Reject** |
| **Chunk-gen work** (separate session) | **Yes — dominates the felt moving-ride lag** | ~30–45 ms of the felt 45–60 ms | n/a here | **This is where effort belongs** |

## Recommendation

1. **Do not build a new physics decoupling for this.** Post-#648/#640/#742 the physics
   floor is ~15 ms at 63 carriages; even a perfect DT-side park saves ~7.9 ms and can't be
   applied to a moving train anyway (active≈resident, and parking a moving carriage
   desyncs it). Low ceiling, real risk. Profile-first has saved us that build.
2. **Direct effort to chunk generation** — it is the measured dominant term of the felt
   long-ride lag (the gen session already spawned per #742/#743; leads: memoize
   `WorldGenCycle.fromConfig`, off-thread pre-gen ahead of the train).
3. **Optionally** file the upstream Sable per-body `set_enabled`/sleep request as a
   nice-to-have for dedicated servers hosting many long trains (removes the ~4.6 ms native
   floor). Not DT-shippable alone.

## Shipped from this session

- `active=` on the `[mspt]` line (permanent instrumentation; makes the tracked-vs-resident
  gap readable in every future profile). No behaviour change.
- This report. No freeze-logic or Sable change (none is warranted).
