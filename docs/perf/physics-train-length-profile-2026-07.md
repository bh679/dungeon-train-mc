# Sable physics vs train length — profile & recommendation (2026-07)

Issue #646 remainder. Question: can DT cut the Sable **physics** server-tick cost that
scales with carriage count, and is it worth doing for the felt long-ride lag?

**Headline: no new physics work is warranted — train-length physics scaling is already
broken by the shipped optimisations (#648 substeps + #742 soft-freeze).** A live
singleplayer ride shows physics cost tracks the ~constant *tracked* carriage set, not
train length, and the residual server-tick cost is dominated by chunk generation +
resume/world-load transients, not physics. Details below.

> **Corrected 2026-07-14 by a live in-game ride** (manual Gate-2 test). An earlier draft
> claimed `active ≈ resident` on a moving train (from a Gate-1 code inference) → that
> #742 reaches ~none of it. **The live data refutes that**: DT force-holds far more
> carriages resident than the client tracks, so #742 parks the majority *while riding*.
> The conclusion (no new physics work) is unchanged and in fact strengthened.

## Method

- Build `0.451.x` (adds `active=` to the `[mspt]` DEBUG line). Headless dedicated
  `runServer`, pinned seed, `view-distance=20`, RCON, for the A/B; live integrated client
  for the ride. Harness per `project_dt_server_perf_ab_harness`.
- Metric: vanilla `tick query` / `[mspt]` `avgTickMs` (`getAverageTickTimeNanos`,
  whole-server mean incl. Sable physics), matched-toggle A/B via
  `/dungeontrain debug physicsfreeze on|off` over RCON.
- Fixtures: (a) a **25-sub-level train, parked, player-less** for the clean per-body A/B
  (no rider, no gen, no train-work; substep tuner engaged `2->1`, so **shipping** substep=1
  numbers); (b) a **live singleplayer ride** (14→48 carriages) captured from `[mspt]`/
  `[freeze]` — the real felt scenario, which supplied the decisive scaling data below.

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

This per-body split is what soft-freeze exploits. On the shipping build only the ~14
*active* carriages pay the full 0.20 ms/body; the frozen surplus pays just the 0.073 ms/body
native step (see the live ride below) — which is why physics stays flat as the train grows.
Fully-stepped-everywhere (freeze OFF) a 63-car train would be 63 × 0.20 + base ≈ **~15 ms**;
with freeze ON it is ~14 × 0.20 + ~49 × 0.073 + base ≈ **~9 ms** — and flat vs length.

## Live singleplayer ride (0.451.2, `[mspt]`/`[freeze]`, 14→48 carriages, 91 samples)

The decisive dataset — a real ride on the shipping build, the felt scenario:

| carriages (resident) | active (tracked) | frozen | avgTickMs (p15 floor) |
|---|---|---|---|
| 14–22 (early / just after a resume-pause) | ~10 | ~4–11 | 40–46 (transient) |
| 25–37 (settled) | ~12–14 | ~13–24 | 16–20 |
| 47 (steady dwell, n=37) | **~14** | **~33** | **~22** |

- **`active` (tracked) is essentially FLAT ~10–14 across the whole 14→48 range** — the
  client's tracked carriage set does **not** grow with train length. Physics cost is bound
  to that set, so **it does not scale with train length**.
- **`resident` grows to 47 while `active` stays ~14 → `frozen` grows to ~33.** #742's
  soft-freeze parks the untracked majority **while riding** (74% at 47 carriages). DT
  force-holds far more resident than the client tracks (trailing-N window + near-player
  window + #547 resume-hold which pins the whole train transiently — a 5807 ms resume
  fling fired mid-ride), so the "park untracked" set is large, not empty.
- **`avgTickMs` does NOT track carriage count.** The p15 floor at 47 carriages (~22 ms) is
  *lower* than at 18–20 carriages (44–46 ms). The high early values are world-load + the
  resume fling, not physics — once settled the floor is flat ~18–22 ms from 25→47 carriages.

**Correction to the Gate-1 inference.** `bootstrapTargetFromServerViewDistance` sizes the
*bootstrap* target as `(viewDistance×16×2)/carriageLength`, but the live steady-state
resident count (trailing window + resume-hold) runs far above the client's tracked set, so
`active ≪ resident` in practice (measured 14 vs 47), the opposite of the earlier draft.

## Why physics is NOT the felt bottleneck (revised, live-data-backed)

- Physics cost is bound to the flat ~14 *active* carriages: ≈ 14 × 0.20 ms + base ≈ **~6 ms**
  of the observed ~22 ms floor at 47 carriages. The other ~16 ms is chunk-gen + DT
  train-work + transients (resume fling / world-load).
- Train-length physics **scaling** is already broken: #742 parks the untracked majority and
  #648 halves substeps ≥24 residents, so adding carriages adds frozen bodies (only the
  ~0.073 ms/body native step) not stepped ones — and `avgTickMs` is flat with length, as
  measured.
- Matches #742's own finding that a freeze ON-vs-OFF toggle on a moving run washed to ~0
  net (swamped by gen). The old "0.65 ms/body → 41 ms @63car" estimate predates #648/#742
  and is superseded.

## Levers, measured payoff, verdict

| Lever | Helps the felt ride? | Payoff | Risk | Verdict |
|---|---|---|---|---|
| **Reduce over-hold** (force-hold fewer resident so fewer bodies in scene) | Only the native step of the frozen surplus (~0.073 ms/body × ~33 ≈ 2.4 ms @47car) | Tiny | Re-introduces the cull→reload jitter/vanish class (#628/#630/#623) the over-hold exists to prevent | **Reject** — cost ≫ benefit |
| **Upstream Sable `set_enabled`/sleep** (skip native step of frozen bodies) | Removes the frozen surplus's native step | ~2–5 ms | Cross-repo, Sable author; DT ships stock Modrinth 2.0.2 (no fork) | **Optional low-priority upstream ask** |
| **Risky sim-decouple** (keep teleport, skip Java for tracked-distant) | Marginal — only the ~14 active bodies, pose read-back must stay | < 2 ms | Visual desync; high effort | **Reject** |
| **Chunk-gen + transients** (separate session) | **Yes — dominates the residual ~16 ms of the ~22 ms floor** | Most of the felt lag | n/a here | **This is where effort belongs** |

## Recommendation

1. **Do not build a new physics decoupling.** The live ride proves train-length physics
   scaling is *already handled* by the shipped #742 (parks the untracked majority — ~33 of
   47 while riding) + #648 (substeps). `avgTickMs` is flat with train length; physics is
   ~6 ms of a ~22 ms floor. Every remaining lever is <5 ms and/or reintroduces jitter.
   Profile-first has saved building a complex, risky decoupling that wouldn't move the needle.
2. **Direct effort to chunk generation + ride transients** — the measured dominant terms
   (the gen session already spawned per #742/#743; leads: memoize `WorldGenCycle.fromConfig`,
   off-thread pre-gen ahead of the train). The 40–46 ms early-ride spikes here were
   world-load + a #547 resume fling — worth a look separately.
3. **Optionally** file the upstream Sable per-body `set_enabled`/sleep request (removes the
   frozen surplus's native step, ~2–5 ms) as a dedicated-server nice-to-have. Not
   DT-shippable alone.

## Shipped from this session

- `active=` on the `[mspt]` line (permanent instrumentation; makes the tracked-vs-resident
  gap readable in every future profile). No behaviour change.
- This report. No freeze-logic or Sable change (none is warranted).
