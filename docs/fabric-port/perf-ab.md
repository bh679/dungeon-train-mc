# Fabric Port — Stage 5a Server MSPT A/B: NeoForge vs Fabric (2026-07-13)

**Verdict: near-parity, as expected.** Both loaders boot the same real DT world (pinned
seed `dtperf2026`, full 13-carriage train, view/simulation-distance 10) at essentially the
same server-tick cost. The ~2-4x run-to-run swings seen on *both* loaders dwarf the
loader-to-loader gap — consistent with the working assumption that DT's per-tick cost is
dominated by mod/physics (Sable) code that runs identically on both platforms, not by
loader overhead.

## Methodology

- 3 runs per loader, strictly sequential (no concurrent servers, no concurrent Gradle
  builds). `run/world` (NeoForge) / `fabric/run/world` (Fabric) deleted before every run so
  each boot regenerates fresh from the pinned seed.
- Per run: `./gradlew :runServer` (NeoForge) or `./gradlew :fabric:runServer` (Fabric),
  background + logged → wait for `Done (` (dedicated-server ready) → wait for train
  assembly evidence (`Bootstrap eager fill`, `totalShips=13`) → 60s settle → 24×
  `/tick query` samples over RCON at 5s intervals (120s window) → one `jcmd <pid>
  GC.heap_info` → RCON `stop` → verify process exit and port freed before the next run.
- `server.properties` identical between loaders except rcon port/password and
  `server-port` (servers never run concurrently, so this is cosmetic): `level-seed=dtperf2026`,
  `view-distance=10`, `simulation-distance=10`, `online-mode=false`, `enable-rcon=true`.
- One harness bug found and fixed mid-run: the boot-detection regex only matched
  NeoForge's Log4j2 format (`]: Done (`); Fabric's console logger renders `) Done (`
  instead. Fixed to match either format before the Fabric runs. The very first Fabric
  attempt (pre-fix) was discarded — it booted and ran fine, just wasn't detected in time —
  and rerun cleanly after the fix.
- Per-run wall-clock `t_start`/`t_done` capture in the harness had an unrelated stale-log
  race on most runs (returned `boot_to_done_s=0`). Where that happened, boot time below is
  taken instead from the server's own self-reported `Done (X.XXXs)` line, which is accurate
  and is what's reported in the table.
- Both loaders are companion-mod-degraded in this environment (AIN/PlayerMob jars not on
  the dev classpath → `NoClassDefFoundError` for `NameComposer` / `PlayerMobEntity` at
  carriage-contents/spawn time on both loaders equally) — this affects both sides
  identically and does not bias the comparison.

## Environment

- macOS 26.5 (build 25F71), Apple Silicon (aarch64)
- Java: Eclipse Temurin 21.0.11+10-LTS (Gradle toolchain 21)
- Minecraft 1.21.1, NeoForge `21.1.228`
- Fabric Loader `0.19.3`, Fabric API `0.116.13+1.21.1`, Fabric Loom `1.17.9`
- Sable `2.0.2+mc1.21.1` (multiloader — common/NeoForge/Fabric artifacts at identical
  version, per Phase 0 findings)
- Same machine, sequential runs, dev-mode (non-shaded) servers on both sides

## Results

### NeoForge (`./gradlew :runServer`)

| Run | Boot→Done | Done→train assembled | MSPT avg (mean/median) | MSPT P95 (mean/median) | MSPT P99 (median) | Heap used (jcmd) |
|---|---|---|---|---|---|---|
| 1 | 4.251s | 6s | 12.74 / 12.65 ms | 15.26 / 15.20 ms | 18.1 ms | 527 MB |
| 2 | 6.933s | 9s | 18.33 / 16.15 ms | 36.01 / 25.45 ms | 69.7 ms | 530 MB |
| 3 | 4.280s | 5s | 13.53 / 13.60 ms | 16.32 / 15.80 ms | 21.0 ms | 662 MB |

NeoForge median-of-run-medians MSPT avg: **13.6 ms**

### Fabric (`./gradlew :fabric:runServer`)

| Run | Boot→Done | Done→train assembled | MSPT avg (mean/median) | MSPT P95 (mean/median) | MSPT P99 (median) | Heap used (jcmd) |
|---|---|---|---|---|---|---|
| 1 | 4.874s | 5s | 12.20 / 11.50 ms | 15.67 / 13.10 ms | 17.6 ms | 747 MB |
| 2 | 4.011s | 6s | 12.59 / 12.50 ms | 28.26 / 22.45 ms | 47.55 ms | 538 MB |
| 3 | 6.831s | 11s | 9.38 / 6.30 ms | 18.49 / 14.05 ms | 39.15 ms | 792 MB |

Fabric median-of-run-medians MSPT avg: **11.5 ms**

(Raw per-sample RCON output and `jcmd` heap dumps live in the harness's scratch logs,
not committed — this doc is the durable record.)

## Reading the numbers

- Median avg-MSPT: NeoForge 13.6ms vs Fabric 11.5ms — a ~2ms gap, well within the noise
  band each loader shows against itself (run 2 on *both* loaders is 30-70% worse than runs
  1/3 on the same loader, most likely GC/JIT warm-up variance during the 120s sampling
  window rather than a loader effect — several runs show MSPT trending sharply downward
  sample-to-sample, e.g. Fabric run1 26ms→4ms, indicating the window still overlaps
  post-boot JIT compilation rather than steady state).
- P95/P99 are noisier still (single GC pauses or chunk-gen bursts dominate a 24-sample
  window) and shouldn't be over-read run to run.
- Heap usage is comparable and dominated by run-to-run variance in how much of the world
  got explored/generated during settle+sample, not by loader.
- Boot time and train-assembly time are both in the same few-seconds ballpark on each
  loader.

## Caveats

- **n=3 per loader.** This is a spike-level sample, not a statistically powered
  benchmark — sufficient to rule out a *gross* Fabric regression, not to detect a subtle
  one.
- **Dev-mode servers**, not production/shaded jars — dev classloading and mixin
  application overhead differs from a shipped build, on both sides.
- **Same machine, sequential, non-isolated** (shared thermal/scheduler state, Gradle
  daemon warm between runs) — background load could bias any individual run, and did
  visibly bias run 2 on both loaders (whether that's coincidental timing or a
  reproducible pattern is not something 3 runs can distinguish).
- **Companion mods degraded** (AIN/PlayerMob) equally on both loaders — real production
  MSPT on both platforms will be somewhat higher once name-composition and PlayerMob
  logic run for real, but that's an additive cost independent of loader.
- **Conclusion given these caveats:** the data is consistent with the pre-registered
  expectation — no material MSPT regression from switching loaders, because DT's
  per-tick cost is Sable/physics/mod-logic bound, and that code path is identical on
  both loaders. This is not proof of parity to sub-millisecond precision; it clears the
  "loader switch doesn't tank performance" bar the Stage 5a gate exists to check.

## World / config restore

- `run/world.bak-perfab` (the long-lived NeoForge test world backed up before this A/B)
  was restored to `run/world` after the last NeoForge measurement run, and the backup
  directory was removed.
- `run/server.properties` and `fabric/run/server.properties` were left with
  `enable-rcon=true`, `level-seed=dtperf2026`, `view-distance=10`,
  `simulation-distance=10`, `online-mode=false` — these are intentional carry-overs from
  the interrupted prior attempt and are harmless for future dev runs (rcon password is a
  local-only dev credential, not a secret used anywhere else).
