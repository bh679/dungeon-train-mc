# Fabric Port — Phase 0 Spike Findings (2026-07-12)

**Verdict: GO.** Sable's physics foundation works on Fabric 1.21.1 at our exact pinned
version. The port program (board item "Port DT to Fabric (multiloader)", Project #16) can
proceed to Phase 1.

Spike lives in [`spike-fabric/`](../../spike-fabric) — a standalone Fabric Loom build,
deliberately not part of the root NeoForge build. Run with
`cd spike-fabric && ./gradlew runClient` (spike-local Gradle 9.5.1 wrapper).

## What was proven (headless, Fabric dedicated server)

| Check | Result |
|---|---|
| `sable-fabric-1.21.1:2.0.2` resolves + compiles against DT's API usage | ✅ same loader-neutral packages (`SubLevelAssemblyHelper`, `RigidBodyHandle`, `SubLevelContainer`, `BoundingBox3i`) compile unmodified on mojmap+parchment |
| Dedicated server boots headless | ✅ 48 mods, `Rapier scene initialized` for overworld/nether/end (macOS arm64 natives fine) |
| Sub-level assembly (`assembleBlocks`) | ✅ 16-block deck lifted into a sub-level via `/sablespike spawn` (RCON) |
| Physics motion | ✅ after `/sablespike push`, pose advanced across samples (6.72,−56.06)→(7.06,−57.61)→(7.30,−57.75), then settled under gravity |
| Sub-level persistence | ✅ sub-level from a previous run reloaded after server restart and kept simulating |
| Client boots | ✅ dev client reaches title screen with Sable-Fabric loaded (manual ride test: create world, `/sablespike spawn`, step on deck, `/sablespike push`) |

## Answers to the four verify-before-build questions

1. **Is sable-companion directly resolvable from maven.ryanhcode.dev?** YES — the maven
   publishes `dev.ryanhcode.sable-companion:sable-companion-{common,fabric}-1.21.1` AND a
   full xplat set under `dev.ryanhcode.sable`: `sable-common-1.21.1`, `sable-fabric-1.21.1`,
   `sable-neoforge-1.21.1`, `sable-sable_rapier-1.21.1`. ⇒ Phase 1 can delete the
   `extractSableCompanion` jarJar-extraction task and use plain maven deps; `:common` can
   compile against `sable-common-1.21.1`.
2. **Does Forge Config API Port provide the `net.neoforged` ModConfigSpec namespace on
   1.21.1 Fabric?** YES, and better than hoped: **Sable-Fabric itself JiJ-bundles
   `forgeconfigapiport-fabric:21.1.3`** (it's a hard dep declared in its POM), so FCAP is
   guaranteed present at runtime on any Sable-Fabric install. DT's three `ModConfigSpec`
   classes can stay in common unchanged. (Exact per-world `serverconfig/` semantics still
   to be exercised in Phase 4 with a real `dungeontrain-server.toml`.)
3. **Do any `EntityJoinLevelEvent` uses cancel the event?** YES — 3 callsites in 2 files:
   `event/PrefabUseHandler.java:101`, `event/NetherBandBehaviourEvents.java:56` and `:85`.
   Fabric's `ServerEntityEvents.ENTITY_LOAD` is not cancellable ⇒ Phase 4 needs the
   planned gap-filler mixin (cancellable spawn callback) for these.
4. **Does the Fabric dedicated server boot headless with Sable?** YES (see table).

## Gotchas discovered (feed into Phase 1/4)

- **Loom needs Gradle ≥ 9.4 for Sable.** sable-fabric 2.0.2 was built with Loom 1.16.3;
  Loom refuses to consume mods built by a newer Loom, and Loom ≥ 1.16.3 requires
  Gradle ≥ 9.4. Root wrapper is 9.2.1 ⇒ the multiloader restructure (Phase 1) must bump the
  root wrapper to 9.5.x **or** keep a per-module wrapper. MDG 2.0.141 on Gradle 9.5 needs a
  compat check early in Phase 1.
- **The maven `sable-sable_rapier` artifact is broken for dev runtimes**: intermediary-mapped,
  no `fabric.mod.json`, declared as a plain runtime dep in sable-fabric's POM. On the mojmap
  dev classpath it throws `AbstractMethodError` on `PhysicsPipelineProvider.createPipeline`
  at world init. Fix (see `spike-fabric/build.gradle`): exclude it and extract the nested
  copy from the sable-fabric jar (`META-INF/jars/…`), which carries a Loom-generated
  `fabric.mod.json` and remaps cleanly. Worth reporting upstream to ryanhcode.
- **Loom does not expand JiJ'd jars of dependency mods into dev runs** — anything DT needs
  at dev runtime must be a direct (`modRuntimeOnly`) dependency.
- Sable-Fabric pulls **Veil 4.1.4** (rendering lib, from `maven.blamejared.com`) and
  **FCAP** (from Fuzs' modresources maven) — both must be in the repositories block.
- Benign log noise: `Failed to apply tag physics properties. Unknown block: create:flywheel`
  (Sable's default physics tags reference Create; harmless without Create installed).

## What Phase 0 deliberately did NOT test

- Riding smoothness / client interpolation with a player aboard (manual test + Phase 4).
- Sodium/Indigo interaction with Sable's render path on Fabric.
- DT-scale load: many sub-levels, carriage appending cadence, autosave holds.
- FCAP per-world serverconfig read of a production `dungeontrain-server.toml`.
