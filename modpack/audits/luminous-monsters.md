# Audit: Luminous Monsters V1.3.1

Audited 2026-11-06 from the pinned jar, using [`../newmod.md`](../newmod.md).

| | |
|---|---|
| Mod id | `luminous_monsters` |
| Pins | CurseForge 1066090 / file 9388896; Modrinth `TzC3nDsQ` / `eRQ6VuD1` (same SHA-1 on both) |
| Shipped as | Hard dependency, on in both modpacks |
| Licence | All Rights Reserved in the jar and on CurseForge; Apache-2.0 on Modrinth. Author permits modpack use on both listings |
| Made with | MCreator |
| Sides | Client and server. Modrinth's `client_side: unsupported` is wrong: the mod has entity renderers |
| NeoForge floor | `[21.1.65,)`, cleared by DT's 21.1.230 |
| Dependencies | None |

## Counts

| Category | Count | Action |
|---|---:|---|
| Main items (armour, weapons, tools) | 0 | None |
| Unfair or balance-breaking items | 0 | None |
| Mobs | 16 | Kept (requested). 13 appear in normal runs; see below |
| Structures | 0 | None |
| Loot tables | 16 | Kept: mob drops only |
| Enchantments | 0 | None |
| Potion effects | 1 new, 5 hit effects | Kept (decided at Gate 1) |
| Workbenches | 0 | None |
| Advancements | 0 | None |
| New blocks | 0 | None |
| Creative tabs | 0 own | 16 spawn eggs in vanilla's tab, hidden |

<details><summary>Mobs (16)</summary>

| Mob | Spawn check | Selector in DT | Seen in testing |
|---|---|---|---|
| Miner Zombie, Miner Skeleton | Below y 50, or a cave biome | `#minecraft:is_overworld` | Yes |
| Icy Zombie, Icy Skeleton | `c:is_snowy` | `#c:is_snowy` | Yes |
| Swamp Zombie, Swamp Skeleton | `c:is_swamp` | `#c:is_swamp` | Yes |
| Savanna Zombie, Savanna Skeleton | `is_savanna` | `#minecraft:is_savanna` | Yes |
| Hollow Skeleton | Dry or sandy | Overworld dry, or `#c:is_sandy` | Yes |
| Dark Oak Zombie, Dark Oak Skeleton | None (biome list only) | Mod's own: `dark_forest` | Yes |
| Cherry Zombie | None (biome list only) | Mod's own: `cherry_grove` | Yes, rarely: groves are small |
| Cherry Skeleton | None (biome list only) | Mod's own: `cherry_grove` | Not seen in this small sample. Same setup as Cherry Zombie |
| Sunken Skeleton | Beach **and** hot | Beach and `#c:is_hot` | **No: no vanilla beach is tagged hot** |
| Crimson Walker | None (biome list only) | Mod's own: `crimson_forest` | **No: Nether band cancels natural spawns** |
| Revenant Skeleton | None (biome list only) | Mod's own: `soul_sand_valley` | **No: Nether band cancels natural spawns** |

All extend vanilla `Zombie` or `Skeleton`, so DT's difficulty gear applies. On a spawned Miner Zombie the held tool
survived (an iron shovel in the test).

</details>

<details><summary>Loot tables (16)</summary>

All are `luminous_monsters:entities/*`, dropped by that mob only. No chest tables, no global loot modifiers.

- Zombie variants: rotten flesh, plus rare player-kill iron and crops.
- Crimson Walker: rotten flesh, rare gold ingot, nether wart.
- Skeleton variants: arrows and bones. Miner adds rare iron; Dark Oak mushrooms; Icy snowballs; Sunken nautilus shell.

</details>

<details><summary>Potion effects (1 new, 5 hit effects)</summary>

| Effect | From | Default strength |
|---|---|---|
| Hemorrhage (new) | Crimson Walker's death burst, 2.5 block radius | Damages you each time you attack |
| Freezing | Icy variants | Config 60 |
| Poison | Swamp variants | Config 60 |
| Slowness | Savanna variants | Config 60 |
| Hunger | Hollow Skeleton | Config 60 |
| Lifesteal | Revenant Skeleton | Config 5 |

</details>

<details><summary>Creative items (16)</summary>

One spawn egg per mob, added directly to vanilla's Spawn Eggs tab. Hidden by `HIDDEN_ITEM_NAMESPACES`; the two existing
creative settings bring them back. Eggs are not disabled, and no recipe makes them.

</details>

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No duplicate ids, no `incompatible` entries. Mixin config is empty |
| Existing world gen | No features, carvers, biomes, surface rules or structures |
| Existing loot tables | Not modified |
| Vanilla data | Adds its mobs to `undead` and `skeletons` entity tags. Empty additive `dirt`, `logs` and `is_overworld` tags. No `replace: true` |
| Economy | No recipes or trades |

## Spawn dilution

10 mobs ship with `neoforge:any`, adding weight 630 to every biome against plains' vanilla 515. DT overrides those 10 files
in `src/main/resources/data/luminous_monsters/neoforge/biome_modifier/`.

Hostile mobs within 128 blocks, plains at night, cleared and recounted about every 3 s. 30 samples per arm per round, two
rounds, each on a fresh world, same seed:

| Arm | Mean per sample | Change | Spawned per 1000 ticks | Change |
|---|---:|---:|---:|---:|
| No mod (v0.1679.0) | 57.5 | | 671 | |
| DT's narrowed lists | 56.5 | −2% | 642 | −4% |
| Mod's original lists | 41.3 | **−28%** | 582 | **−13%** |

Natural spawns outside 3 s sampling still reach the mob cap: a 25 s wait read about 70 in every biome and arm. So the loss
shows as slower refilling, not a lower ceiling. The 55% from weights alone overstated it. Luminous made up 6–7% of plains
spawns after narrowing, all of them Miners.

## Performance

| Cost | Without | With | Verdict |
|---|---:|---:|---|
| Download size, Modrinth pack's on-by-default files | 356.4 MB | 359.2 MB | **+2.84 MB (+0.8%)**. No new libraries |
| Server start to `Done`, new world | 30.7 s (27.9–43.7) | 33.3 s (30.7–50.8) | No measurable change |
| World load only, new world | 12.2 s (10.4–18.4) | 13.9 s (12.7–15.5) | No measurable change |
| Server start to `Done`, existing save | 35.5 s (30.0–37.6) | 30.7 s (28.2–43.0) | No measurable change |
| World load only, existing save | 10.9 s (8.6–15.1) | 10.2 s (8.9–15.7) | No measurable change |
| Client start to title screen (dev client) | 19 s (11–23) | 16 s (11–30) | No measurable change |
| Runtime tick cost | | | Not benchmarked; see below |

Medians of 5 runs with range. Server timings used real jars; the machine was under other load (12 cores, load average
13–25), so small differences would not show. The client figure comes from the dev client, not a full modpack install.

Runtime, from reading the jar:

- **Per-tick code:** 1 of 16 mobs (Revenant Skeleton), and it doesn't appear in normal runs. One shared server-tick work queue.
- **World gen and mixins:** none.
- **Spawn checks:** a sky-light check, two config reads and up to a few tag lookups per attempt. Narrowing removes most of them.
- **Client:** 27 mob textures, 1.5 MB in total. Crimson Walker's burst is up to 150 particles, and it doesn't appear in normal runs.

`scripts/perf/run-ab.sh` was not run: its test mode turns mob spawning off, so it can't exercise this mod. Every Luminous
mob is a plain zombie or skeleton subclass, and mob caps are unchanged.

## Other notes

- **Fair play:** on by default through the pack entry. Its `packages` value is `net.mcreator`, shared by every MCreator
  mod, which makes the package check weak for this id. Exact hashes are still recorded.
- **Text:** the mod ships English only, so mob names, egg names and Hemorrhage show in English in every locale.
- **Saves:** existing worlds keep their terrain and pick up the new spawns immediately. Removing the mod later would
  delete any of these mobs in saved chunks, and held eggs.
- **Re-audit on bump:** diff the mod's biome modifiers against DT's overrides. Upstream changes to those 10 files have no
  effect until they're copied across.
