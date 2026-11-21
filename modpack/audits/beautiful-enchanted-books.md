# Audit: Beautiful Enchanted Books [MOD EDITION] 6.0.0

Audited 2026-11-21 from the 1.21.1 NeoForge jar, using [`../newmod.md`](../newmod.md). **Not added to the modpack
yet:** this audit is the report for that decision.

| | |
|---|---|
| Mod id | `beb` |
| Candidate pins | CurseForge 1083202 / file 7268159; Modrinth `pcqEicMM` / `XeLhIDWZ` (same SHA-1 on both: `4a1828c6…a48e`) |
| Licence | **Disagrees:** Apache-2.0 in the jar, All Rights Reserved on CurseForge and Modrinth. The CurseForge page says it may be used in modpacks. We copy nothing from it, so only the modpack permission matters |
| Sides | Client only in practice. Modrinth says the server needs it and the toml says `BOTH`, but its only mixin is client-side (`ItemRendererMixin`) and it registers no payloads. Tested both ways: a client with it joined a server without it, and a dedicated server with it booted and accepted a client that had it |
| NeoForge floor | `[21,)`, cleared by DT's 21.1.230 |
| Dependencies | MixinExtras 0.3.5, jarJar'd inside it (NeoForge already provides a newer one) |
| Size | 1.7 MB |
| Fair play | Not approved. Adding it to the pack approves it at build time. In testing it put the session into Free Play, as an unapproved mod should |

## What it does

Gives every enchanted book a texture for its enchantment (a different book for Sharpness, Mending, Protection…),
including textures for about 100 other mods' enchantments. Visual only.

## Counts

| Category | Count | Action |
|---|---:|---|
| Main items (armour, weapons, tools) | 0 | None |
| Unfair or balance-breaking items | 0 | None |
| Mobs | 0 | None |
| Structures | 0 | None |
| Loot tables | 0 | None |
| Enchantments and effects | 0 | None. It only re-skins books; it adds no enchantments |
| Workbenches | 0 | None |
| Advancements | 0 | None |
| New blocks | 0 | None |
| Creative tabs | 0 | None |

No `data/` folder, no registries, no payloads.

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No id clash, no keybinds. Its one mixin is a `WrapOperation` on the item-model lookup in vanilla `ItemRenderer`, which chains with other mods' hooks. Sodium, Exposure, Exposure Polaroid and Platform also mix into `ItemRenderer`; all applied with no errors. DT's only item-renderer mixin (`mixin/vista/TvItemRendererMixin`) targets Vista's TV head, not this method |
| Shaders | Tested with Complementary Unbound r5.8.1 on Iris 1.8.14: the book renders correctly in hand and in the hotbar (screenshots 07, 08), with no BEB errors in the log |
| Item display | Written books (loot books, Shared Books) are untouched: BEB only swaps the model of `minecraft:enchanted_book` by its stored enchantment. Six vanilla enchantments checked in game (screenshot 01). Not checked: how a book with an enchantment it has no art for looks |
| World gen, loot, vanilla data, economy | Untouched |

## DT systems it can reach

| System | Result |
|---|---|
| Sable | Nothing physical; inventory rendering only |
| Commands, HUD, network | None |
| Text | None: the jar has no `lang` files and no keybinds |
| Saves | Nothing saved |

## Performance cost

### BEB alone

Every arm had Sodium and Iris; nothing else extra. Alternated, 5 runs each. The machine was busy with other work (load
average 11 to 18), so expect a few seconds of noise.

| Measure | With BEB (median, range) | Without (median, range) |
|---|---|---|
| Client: JVM start to title screen | 24.5 s (22.8–31.3) | 27.0 s (23.6–33.6) |
| Client: new world on a pinned seed (⏱ perf world button) to in-world | 7.7 s (7.6–8.9) | 8.3 s (7.1–14.8) |
| Client: rejoin that save to in-world | 4.7 s (4.3–7.0) | 4.6 s (4.3–5.3) |
| Client setup crashes | 0 of 5 (plus 0 of 1 warm-up) | 0 of 5 |
| Dedicated server: JVM start to `Done (`, 10 runs each | 24.5 s (22–49) | 24 s (22–26) |
| Dedicated server: the game's own `Done (` timer | 7.2 s (5.4–8.9) | 6.7 s (5.3–8.4) |

**No measurable change anywhere.** The server's first round had two slow BEB runs (41 s, 49 s), but their extra time
was in other mods' loading (VanillaBackport, BetterEnd) and the machine's load average was 13 to 18. A second round
of 5 v 5 gave medians of 24 s in both arms.

### All three candidates together

Measured for all three candidates together (ismah + BEB + xali's Potions), against the same client without them.
Sodium and Iris were in both arms. Client JVM start to title screen, 5 runs each, alternated:

| Arm | Runs to title screen (s) | Median | Crashed at setup |
|---|---|---:|---:|
| With the three | 29.29, 25.86, 24.06, 27.76 | 26.8 | 1 of 5 (plus 1 in an earlier, discarded run) |
| Without | 26.05, 27.36, 24.85, 24.28, 25.05 | 25.1 | 0 of 5 |

**Boot time: no measurable change.** The 1.7 s median gap is inside each arm's own 3 to 5 s spread.

### Client crashes at startup

**With extra mods installed, 3 of 10 client boots crashed during startup. Without them, 0 of 21 did.** "Extra mods"
means ismah + BEB, or all five tested mods; Sodium and Iris were in every boot. Each crash happened in
`FMLClientSetupEvent`, which NeoForge runs on parallel threads, and in a thread race that neither ismah nor BEB takes
part in:

- **2 crashes:** DT's `client/WornPhotographStyles` writes Exposure's style map straight from its setup handler, with
  no `enqueueWork`. Exposure's own client init also registers into that map; which thread it runs on is not checked:
  `IllegalStateException: PhotographStyle for type 'dungeontrain:worn_1' is already registered`.
- **1 crash:** Creeper Overhaul writes vanilla's `EntityRenderers` map from its setup handler, with no `enqueueWork`:
  `ArrayIndexOutOfBoundsException` in `Object2ObjectOpenHashMap.rehash`. The other writer is not identified. DT's own
  creeper renderer is queued correctly (`VanillaCreeperRendererClient`).

ismah's setup handler only logs, and BEB does its work in `ModelEvent.RegisterAdditional`, so the likely cause is
timing: more mods in the parallel pool make the existing races more likely to land. The same would apply to players who
add their own mods, so fix DT's handler first and re-run these boots before adding anything.

| Cost | Result |
|---|---|
| Download size | BEB +1.77 MB, **0.39%** of the Modrinth pack (452 MB: 79 pinned files + DT's jar). ismah +48 KB, xali's Potions +0.34 MB |
| World load time | BEB alone: no measurable change (table above) |
| Runtime | Client render only: one model lookup per enchanted book drawn. No tick handlers and no server code, so the tick A/B (`run-ab.sh`) has nothing to measure and was not run |
| Memory | Its book textures, under 1.7 MB compressed, join the item atlas |

## Pack and release checks

| Check | Result |
|---|---|
| Licence | See above: jar and listings disagree; modpack use is allowed on CurseForge |
| Same build on both platforms | Yes |
| NeoForge floor | Clear |
| Sides | Safe either way: boots on a dedicated server, and a server without it accepts clients that have it |
| Enabled or opt-in | Recommend **on by default** (`required: true`) |
| Config defaults | None |

## Verdict

**Every `newmod.md` check that applies passes.** One thing is left: DT's client-setup crash (above) must be fixed and
the boots re-run with BEB in. BEB alone booted cleanly 6 of 6 times, but the crash rate only showed up across 10+
boots.

Wiring for that PR: `modpack.config.json` entry (`slug: beautiful-enchanted-books`,
`project_id: 1083202`, `file_id: 7268159`, `modrinth_project: pcqEicMM`, `modrinth_version: XeLhIDWZ`,
`required: true`, `mod_ids: ["beb"]`), `beautiful-enchanted-books(optional)` in `release.yml`
`curseforge-dependencies`, roster row in `modpack/README.md`. Its Modrinth slug differs:
`beautiful-enchanted-books-mod-edition`.
