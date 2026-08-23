# Sable mixin-target verification

DT's Sable performance work — the #648 substep tuner, the #646 soft-freeze, and the
no-sync-load collision mixins — is entirely DT-side. **Sable is never forked for performance**;
players install stock Sable from Modrinth, so every optimisation is a mixin or a public-API call
in this repo. (`~/Projects/sable` is the MC-26.1 *port* fork — unrelated.)

Those mixins bind to Sable internals **by name** with `remap = false`. A `sable_version` bump can
therefore change what a mixin sits on top of without any compile error. `gradle.properties`
checklist item #5 requires re-verifying the targets on every bump; this directory mechanises it.

```bash
scripts/sable/verify-mixin-targets.sh 2.0.2 2.0.5
```

Both versions must be in the Gradle module cache — a version lands there once `./gradlew build`
has run with that pin in `gradle.properties`. Exit 0 = all identical, 1 = something changed and
needs a human, 2 = the check could not run.

## What a PASS proves

Every class DT mixes into is byte-for-byte unchanged. That means the targets still resolve **and**
the specific claims in each mixin's javadoc still hold verbatim — the enumerated freeze method set,
"exactly two `Level.getChunk(II)` call sites", "exactly one `new LevelAccelerator` site". On a PASS,
move the `Bytecode-verified against {@code sable-<ver>+mc…}` stamps forward and stamp item #5.

## What it does NOT prove

**That no *other* Sable class started doing per-body work.** The script only looks at the seven
classes DT already mixes into. A bump that adds a new per-tick reader elsewhere would pass here and
still cost performance, because soft-freeze never removes a body — a missed reader is wasted work,
not a crash. Two manual supplements, both fast:

1. **New native-boundary callers.** DT gates `RapierPhysicsPipeline` because every `Rapier3D.*` call
   funnels through it. Confirm nothing new bypasses it:

   ```bash
   # inside each version's unpacked sable_rapier jar
   grep -rl 'Rapier3D' . | wc -l     # 12 in both 2.0.2 and 2.0.5
   ```

   A larger count means a new caller — check whether it reads per-body state.

2. **Whole-jar class diff.** Compare `shasum` over every `*.class` in both jars to see what changed
   at all, then eyeball anything physics- or sub-level-shaped. This is how 2.0.5's new
   `SableServerConfig` was spotted.

## Why this isn't in CI

It needs two Sable versions present at once, which is only true around a bump. It is a bump-time
tool, and `gradle.properties` item #5 is where the next person is told to reach for it.

## Missing targets are already caught, loudly

`src/main/resources/dungeontrain.mixins.json` sets `"injectors": {"defaultRequire": 1}`, so an
injection that fails to resolve is a **hard crash at mod load** — never a silent perf regression.
A shipped release running on a Sable version is itself proof that every injection landed. This
script exists for the case that bytecode alone can't catch: targets that still resolve but no
longer mean what the mixin assumes.
