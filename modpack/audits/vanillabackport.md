# Audit: VanillaBackport 1.2.2 (+ Platform 1.4.2)

Audited 2026-11-25 from the pinned jars, using [`../newmod.md`](../newmod.md). A pin move, from 1.1.7.10 / Platform
1.3.3. Everything new was requested on (Gate 1): spears and Lunge, copper gear, the new mobs, the decor blocks.

| | |
|---|---|
| Mod id | `vanillabackport` (content under `minecraft:`) |
| Pins | CurseForge 417430 / file 9318571; Modrinth `6xwxDTgf` / `mGS7BxD4` (same SHA-1 `12871e53…` on both) |
| Library | Platform 1.4.2: CurseForge 997634 / file 9318519; Modrinth `i6fiqm5y` / `rh4p4ixM` (same SHA-1 `38947983…`). VB 1.2.2 requires `[1.4,)` |
| Shipped as | Hard dependency, on in both modpacks |
| Licence | MIT (VB), LGPL-3.0 (Platform) |
| Sides | Client and server |
| NeoForge floor | `[21.1,)`, cleared by DT's `neo_version` |
| Size | Jar grows from about 50 MB to 64 MB |

## Counts (new in 1.2.2)

| Category | Count | Action |
|---|---:|---|
| Weapons | 7 spears (wooden, stone, copper, iron, golden, diamond, netherite) | Kept. Roll beside every sword in DT loot at the sword's weight (`editor/SpearTwins`) and in the mob weapon pool (`difficulty/ProceduralTiers`) |
| Tools and armour | Copper set, copper/netherite horse armour, 5 nautilus armours | Kept |
| Mobs | Nautilus, Zombie Nautilus, Camel Husk, Parched, Copper Golem (+ zombie horse spawns) | Kept. Spawn under VB's own rules, not level-gated (`minecraft:` ids) |
| Structures | Abandoned Camp (3 loot tables) | Kept |
| Enchantments | `minecraft:lunge` | Kept (`isDisabledEnchantment` keeps `minecraft:`) |
| Potion effects | Breath of the Nautilus | Kept |
| Advancements | 1 new non-recipe (`spear_many_mobs`); recipe advancements 113 → 332 | Kept |
| New blocks | About 150 (copper chests, lanterns, chains, bars, golem statues; shelves; cushions; wool/concrete slabs and stairs; Poplar set) | Kept. Poplar gets a train stage (`StageWoodFamily.POPLAR`) |
| Biomes | Dappled Forest | Already in `worldgen/BackportBiomes` |
| Creative tabs | VB's own tabs | Hidden (`HIDDEN_TAB_NAMESPACES`) |

## Conflicts

- **Creaking classes moved** in VB's 1.2 rewrite. DT's five creaking mixins and helpers point at the new
  paths (`block_entities`, `entities.mob.monster.creaking`, `WorldUtilities$EnvironmentUtils/$SpawnUtils`).
  Every hooked method and call site exists. The `ConfigValue.get()` wrap in `CreakingHeartSettingsMixin` sees only
  `doCreakingHeartsWorkOnDay` in 1.2.2, so its Boolean answer stays type-safe.
- **Sulfur surface rule.** 1.1 baked it into every `NoiseGeneratorSettings`; 1.2 merges it at surface-build time
  for `minecraft:overworld` only. `mixin/vanillabackport/DtOverworldSulfurRuleMixin` repeats the merge for
  `dungeontrain:overworld*` and for the unregistered settings DT builds (legacy bands, portal rooms, samplers).
  Verified on a dev server, seed 424242, the two Nether-exit caves past the first Nether core: 206 sulfur / 1755
  cinnabar and 1470 / 3343, identical to `main` on 1.1.7.10; with the wrap disabled, 0 and 0.
- **Pale oak boats.** 1.2 drops the `pale_oak_boat` / `pale_oak_chest_boat` entity types (the items stay). A pale
  oak boat already placed in a world made on 1.1.7.10 is lost when the world loads. DT references neither id.
- **TerraBlender region** still `vanillabackport:overworld`, so `RegionsMixin`'s veto is unchanged.
- **Mixins** grow from 76 to 167. 19 vanilla classes are newly patched by both VB and DT (`AbstractHorse`,
  `BaseContainerBlockEntity`, `ClientLevel`, `EnchantmentHelper`, `EnderMan`, `GameRenderer`, `Gui`, `Level`,
  `LevelRenderer`, `LightTexture`, `LightningBolt`, `Minecraft`, `NoiseBasedChunkGenerator`, `Player`,
  `PlayerList`, `Projectile`, `ServerChunkCache`, `ServerLevel`, `SoundOptionsScreen`). A clashing redirect or
  overwrite fails at load, so the Gate 2 boot covers it.
- No loot modifiers, no `replace: true` tags, no biome-modifier files.

## DT systems it reaches

- **Loot:** spears via `SpearTwins` at roll time; stored pools and the editor are unchanged.
- **Mob gear:** spears in `ProceduralTiers.weaponSet`; VB's `has_spears` also lets vanilla monsters spawn holding one.
- **Trade Everything:** spears and Lunge books are outside `GearValueProvider`'s formula and fall back to TE's
  recipe pricing; copper gear is priced by its tags.
- **Mob photos:** none of the new mobs is placed by a DT room, so `categories.json` needs nothing yet.
- **Sable:** Copper Golems and the new mounts on a moving train are a Gate 2 check.

## Performance

Boot and tick A/B (`scripts/perf/run-ab.sh`) to be recorded at Gate 2.
