# Audit: Beautiful Enchanted Books [MOD EDITION] 6.0.0

Audited 2026-11-21 from the 1.21.1 NeoForge jar, using [`../newmod.md`](../newmod.md). **Not added to the modpack
yet:** this audit is the report for that decision.

| | |
|---|---|
| Mod id | `beb` |
| Candidate pins | CurseForge 1083202 / file 7268159; Modrinth `pcqEicMM` / `XeLhIDWZ` (same SHA-1 on both: `4a1828c6…a48e`) |
| Licence | **Disagrees:** Apache-2.0 in the jar, All Rights Reserved on CurseForge and Modrinth. The CurseForge page says it may be used in modpacks. We copy nothing from it, so only the modpack permission matters |
| Sides | Client only in practice. Modrinth says the server needs it and the toml says `BOTH`, but its only mixin is client-side (`ItemRendererMixin`) and it registers no payloads. A client with it joined a dedicated server without it |
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
| Existing mods | No id clash. Its one mixin is on vanilla `ItemRenderer`; booted with Sodium 0.8.12 + Iris 1.8.14 with no mixin errors |
| Item display | Written books (loot books, Shared Books) are untouched: BEB only swaps the model of `minecraft:enchanted_book` by its stored enchantment. Six vanilla enchantments checked in game (screenshot 01). Not checked: how a book with an enchantment it has no art for looks |
| World gen, loot, vanilla data, economy | Untouched |

## DT systems it can reach

| System | Result |
|---|---|
| Sable | Nothing physical; inventory rendering only |
| Commands, HUD, network | None |
| Text | No player-facing text |
| Saves | Nothing saved |

## Performance cost

Measured for all three candidates together (ismah + BEB + xali's Potions), against the same client without them.
Sodium and Iris were in both arms. Client JVM start to title screen, 5 runs each, alternated:

| Arm | Runs to title screen (s) | Median | Crashed at setup |
|---|---|---:|---:|
| With the three | 29.29, 25.86, 24.06, 27.76 | 26.8 | 1 of 5 (plus 1 in an earlier, discarded run) |
| Without | 26.05, 27.36, 24.85, 24.28, 25.05 | 25.1 | 0 of 5 |

**Boot time: no measurable change.** The 1.7 s median gap is inside each arm's own 3 to 5 s spread.

**The crashes are not in these mods.** Both happened during `FMLClientSetupEvent`, which NeoForge runs on parallel
threads, and both are an existing thread race that adding any mods can expose by shifting the timing:

- DT's `client/WornPhotographStyles` writes Exposure's style map straight from its setup handler, with no
  `enqueueWork`: `IllegalStateException: PhotographStyle for type 'dungeontrain:worn_1' is already registered`.
- Creeper Overhaul writes vanilla's `EntityRenderers` map from its own setup handler:
  `ArrayIndexOutOfBoundsException` in `Object2ObjectOpenHashMap.rehash`.

Neither stack touches ismah, BEB or the pack. Players can hit the same crash today; fix DT's handler before adding
mods, whichever are picked.

| Cost | Result |
|---|---|
| Download size | +1.7 MB (BEB) + 48 KB (ismah) + 0.34 MB (xali's Potions) |
| World load time | Not measured: none of the three adds world data, registries or chunk work |
| Runtime | Client render only: one model lookup per enchanted book drawn. No tick handlers, no server code. Tick A/B not run |
| Memory | Its book textures, under 1.7 MB compressed, join the item atlas |

## Pack and release checks

| Check | Result |
|---|---|
| Licence | See above: jar and listings disagree; modpack use is allowed on CurseForge |
| Same build on both platforms | Yes |
| NeoForge floor | Clear |
| Sides | Client only; safe on a server without it |
| Enabled or opt-in | Recommend **on by default** (`required: true`) |
| Config defaults | None |

## Verdict

Fits the pack. Wiring for the follow-up PR: `modpack.config.json` entry (`slug: beautiful-enchanted-books`,
`project_id: 1083202`, `file_id: 7268159`, `modrinth_project: pcqEicMM`, `modrinth_version: XeLhIDWZ`,
`required: true`, `mod_ids: ["beb"]`), `beautiful-enchanted-books(optional)` in `release.yml`
`curseforge-dependencies`, roster row in `modpack/README.md`. Its Modrinth slug differs:
`beautiful-enchanted-books-mod-edition`.
