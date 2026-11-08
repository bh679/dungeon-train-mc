# Audit: Creeper Overhaul 4.0.6

Audited 2026-11-08 from the pinned jar, using [`../newmod.md`](../newmod.md).

| | |
|---|---|
| Mod id | `creeperoverhaul` |
| Pins | CurseForge 561625 / file 6051279; Modrinth `creeper-overhaul` / `HNrAYCLH` (same SHA-1 `5a07cc03…` on both) |
| Shipped as | Hard dependency, on in both modpacks |
| Licence | All Rights Reserved (jar `license`, Modrinth). DT copies no code or art: it overrides one data file by path and names entities by id |
| Made by | Joosh (art), ThatGravyBoat (code) |
| Sides | Client and server: entities, GeckoLib renderers, a client mixin and network payloads |
| NeoForge floor | `[21.0,)`, cleared by DT's 21.1.230 |
| Dependencies | GeckoLib `[4.5.5,)`, Resourceful Config `[3.0.0,)` (declared); Resourceful Lib (undeclared in `mods.toml`, but its classes are used by `Creepers` and `NetworkHandler`, and Modrinth lists it) |

Not the CurseForge **resource pack** of the same name (project 570655): that one only reskins the vanilla creeper.

## Libraries

| Library | Pin | Licence | NeoForge floor | Notes |
|---|---|---|---|---|
| GeckoLib 4.9.3 | CF 388172 / 8893490; Modrinth `Grwn5rUB` | MIT | `[21.1.150,)` | Marks `geckoanimfix` incompatible. Same SHA-1 both platforms |
| Resourceful Lib 3.0.12 | CF 570073 / 5973188; Modrinth `x99nCLTm` | MIT | `[21.0.0-beta,)` | Same SHA-1 both platforms |
| Resourceful Config 3.0.11 | CF 714059 / 6467772; Modrinth `lSbyRD6v` | MIT | `[20.5,)` | Same SHA-1 both platforms |

## Counts

| Category | Count | Action |
|---|---:|---|
| Main items (armour, weapons, tools) | 0 | None |
| Unfair or balance-breaking items | 0 | None |
| Mobs | 16 | Kept (requested). Natural spawns gated by level; the vanilla creeper stays until a biome's own creeper unlocks |
| Structures | 0 | None |
| Loot tables | 16 mob, 2 block | Kept: mob drops and the tiny cactus |
| Enchantments | 0 | None |
| Potion effects | 0 new | Some creepers leave vanilla effects on explosion or hit (below). Kept |
| Workbenches | 0 | None |
| Advancements | 0 | None |
| New blocks | 2 | Tiny Cactus and its potted form. Count only |
| Creative tabs | 1 own | Hidden (`HIDDEN_TAB_NAMESPACES`): spawn eggs and the tiny cactus |

<details><summary>Mobs (16)</summary>

All extend vanilla `Creeper` through `BaseCreeper` (GeckoLib model, shearable), so they are `Enemy`: DT's creeper
rule in `DifficultyApplier` (no difficulty potion effects) and `MobDifficultyEvents` apply, and photos file them as
hostile.

| Mob | Kind | Mod's selector (kept) | Weight |
|---|---|---|---:|
| Badlands | Hostile | `#c:is_badlands` | 75 |
| Bamboo | Neutral | `#c:is_jungle` | 75 |
| Beach | Hostile | `#c:is_beach` | 75 |
| Birch | Hostile | `#c:is_birch_forest` | 75 |
| Cave | Hostile | `#creeperoverhaul:where_creepers_spawn` (every biome below) | 50 |
| Dark Oak | Hostile | `#c:is_dark_forest` | 75 |
| Desert | Hostile | `#c:is_desert` | 75 |
| Dripstone | Hostile | `#creeperoverhaul:is_cave` (`#c:is_cave` minus deep dark) | 75 |
| Hills | Neutral | `#c:is_hill` | 75 |
| Jungle | Hostile | `#c:is_jungle` | 75 |
| Mushroom | Passive | `#c:is_mushroom` | 10 |
| Ocean | Water (pufferfish puff) | lukewarm, deep lukewarm, warm ocean | 1 |
| Savannah | Neutral | `#c:is_savanna` | 75 |
| Snowy | Neutral | `#c:is_snowy` | 75 |
| Spruce | Hostile | `#c:is_taiga` | 75 |
| Swamp | Hostile | `#c:is_swamp` | 75 |

No selector is `neoforge:any`, so nothing reaches every biome. `where_creepers_spawn` is a list of overworld tags
(beach, snowy, desert, hill, savanna, badlands, mushroom, taiga, mountain, cave, jungle, swamp, dark forest, birch,
three warm oceans).

**Vanilla creeper.** The mod's `remove_vanilla.json` strips `minecraft:creeper` from every `where_creepers_spawn`
biome. DT overrides it with `neoforge:none`; instead `event/NaturalSpawnLevelGate` drops the vanilla creeper from a
spawn list once one of that biome's own overhaul creepers is unlocked. The cave creeper does not count, because it
is listed in every one of those biomes. Mountains have no creeper of their own, so they keep the vanilla creeper.

**Weights after unlock.** A jungle that has unlocked both its creepers lists bamboo 75 + jungle 75 + cave 50 = 200
creeper weight where vanilla had 100. That is the mod's own balance; DT keeps it for now. Lower the weights in an
override here if the train feels creeper-heavy.

**Vanilla model.** `ClientConfig.replaceDefaultCreeper` (default on) gives the vanilla creeper the mod's model.
`client/render/VanillaCreeperRendererClient` re-registers vanilla's renderer after client setup, so the vanilla
creeper keeps its vanilla look whatever that setting says.

</details>

<details><summary>Loot tables (18)</summary>

`creeperoverhaul:entities/<type>_creeper` per mob, dropped by that mob only, plus `blocks/tiny_cactus` and
`blocks/potted_tiny_cactus`. No chest tables, no global loot modifiers.

</details>

<details><summary>Potion effects</summary>

No new effects. `CreeperType` gives some creepers vanilla effects: one leaves Poison in its explosion cloud and one
inflicts Blindness. The Stray is listed as an entity one type avoids. Strengths are the mod's defaults.

</details>

## Conflicts

| Check | Result |
|---|---|
| Mixins | `IronGolemMixin`, `PlayerListMixin` (common), `ClientPacketListenerMixin`, `LivingEntityRendererInvoker` (client). The mod injects into `PlayerList#placeNewPlayer`, `ClientPacketListener#handleLogin` and `IronGolem#registerGoals`; DT's mixins in those classes use other methods (`setAllowCommandsForAllPlayers`, `handleCommands`, `handlePlayerCombatKill`) |
| Vanilla data | Adds `minecraft:can_breathe_under_water` and `powder_snow_walkable_mobs` entity tags (append only, no `replace`) |
| `c:` tags | Adds to `c:creepers`, `c:is_dark_forest`, `c:is_mushroom` (append only) |
| Economy | No recipes, trades or Trade Everything items |
| World gen | None |
| Explosions | `CreepersConfig.destroyBlocks` (default on) is the mod's own grief toggle; carriage blocks stay under `SableBlockChangeGuardMixin`, which DT applies to every explosion |

## DT systems

| System | Result |
|---|---|
| Natural spawns | `creeperoverhaul` in `GATED_NAMESPACES` (Java and `derive-mob-offsets.py`): each creeper unlocks at its first room level, and one in no room never spawns naturally |
| Nether band, upside-down, spheres | Natural spawns cancelled there as for every mob; selectors are overworld tags |
| Biome twins | Spawns come from biome modifiers, so `VanillaBiomeTwins` keeps them |
| Rooms | 15 seeded `enemies` sub-variants, `contents/creeper_<biome>`: the creeper room re-skinned in that biome's blocks, three slots of its creeper (room level, +5, +10). Levels 4 (birch, spruce) to 14 (mushroom), so biomes go over to their own creepers gradually. The ocean creeper has no room: it is a water mob |
| Photos | All 16 in `scripts/advancements/mob-photos/categories.json` as hostile |
| Fair play | Auto-whitelisted with the pack. Ask at Gate 3 |
| Network | Its cosmetics screen links to `creeperoverhaul.thatgravyboat.tech`; check for background calls at Gate 2 |
| Text | 12 locales of its own, not DT's 20 |
| Saves | Removing the mod later deletes any of its creepers in loaded chunks; vanilla creepers come back on their own |

## Performance cost

| Cost | Result |
|---|---|
| Modpack download size | +2.64 MB (mod 1.19 MB, GeckoLib 0.63 MB, Resourceful Lib 0.48 MB, Resourceful Config 0.34 MB) |
| Boot, world load, runtime | Measured at Gate 2 (`scripts/perf/run-ab.sh`, spawning on for a spawn sample) |
