# Async Locator Refined: new-mod audit

- **Pinned file:** `async-locator-refined-neoforge-1.21.1-1.6.0.jar`, Modrinth `async-locator-refined` version
  `zHBIpxJZ`, CurseForge 1331921 / file 8501111. SHA-1 `287ec5c76bf9f7f8493e789f70759ab993784f5e` (Modrinth copy).
  CurseForge's copy has not been compared: no `CURSEFORGE_API_KEY` on the dev machine.
- **Mod id / version:** `asynclocator` / `1.6.0`. The floor is `asynclocator_min_version=1.6.0`, the version the jar's
  `mods.toml` reports, not the file name's `1.21.1-1.6.0`.
- **Why:** DT's world makes vanilla's structure search very slow far along the track. One explorer-map trade froze the
  server for 96.5 s at x=60000 (v0.2031.0, a player's 283 s freeze). This mod runs the search on a background thread.
- **Requested:** all of it. The mod adds no content, only async versions of vanilla searches.
- **Dependency type:** hard dependency on both platforms (`async-locator-refined(required)`). It is enabled in both
  packs. Without the mod, DT drops explorer-map trades (`difficulty/StructureMapListings`).

| Category | Count | Action |
|---|---:|---|
| Main items, unfair items | 0 | n/a |
| Mobs | 0 | n/a |
| Structures | 0 (one empty `data/asynclocator/structure/empty.nbt`) | n/a |
| Loot tables | 0 | n/a |
| Enchantments, potion effects | 0 | n/a |
| Workbenches | 0 | n/a |
| Advancements | 0 | n/a |
| New blocks | 0 | n/a |
| Creative tabs | 0 | n/a |
| Mixins | 14 | Kept (requested) |
| Config | 1 (`asynclocator-common.toml`) | Defaults kept |

<details><summary>Mixins (14)</summary>

| Mixin | Target | Effect |
|---|---|---|
| `TreasureMapForEmeraldsMixin` | `VillagerTrades$TreasureMapForEmeralds.getOffer` | Returns the offer at once with a pending map (`menu.working` name, `asynclocator.pending` custom data) and fills it in place when the search ends |
| `SlotMixin` | `Slot.mayPickup` | A map still locating can't be taken from the trade result slot |
| `MerchantOfferAccess` | `MerchantOffer` | Accessor (`maxUses`, to mark a failed map out of stock) |
| `ExplorationMapFunctionMixin`, `SetNameFunctionMixin`, `SetNameFunctionAccessor` | loot functions | Chest explorer maps fill asynchronously |
| `DolphinSwimToTreasureGoalMixin`, `…StuckAccessor` | dolphin treasure goal | Async treasure search |
| `EnderEyeItemMixin`, `EyeOfEnderMixin` | Eye of Ender | Async stronghold search |
| `LocateCommandMixin`, `LocateCommandAccess` | `/locate` | Command answers when the search ends |
| `ChunkGeneratorMixin` | `ChunkGenerator.tryGenerateStructure` | Bookkeeping for searches running alongside generation |
| `StructureCheckMixin` | `StructureCheck` | Thread safety of the structure-start cache |

</details>

<details><summary>Config defaults</summary>

Every async toggle is on: villager trades, chest maps, dolphins, Eye of Ender, `/locate` structure and biome. 2 concurrent locates, 128 queued.
`removeMerchantInvalidMapOffer` (when a map search finds nothing) keeps vanilla's out-of-stock behaviour. Nothing ships in
`modpack/overrides/`.

</details>

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No duplicate id, no `incompatible` entries. No other pack mod mixes into the same targets that we know of. DT's own mixins on `ChunkGenerator` (decoration, spawns, Lost City) and `NoiseBasedChunkGenerator` are different methods |
| World gen | None added. Searches read DT's biome source from background threads, which chunk-gen workers already do |
| Loot tables | No overrides; it only makes the vanilla `exploration_map` function async |
| Vanilla data | None |
| Economy | Map trades keep vanilla prices. DT passes map offers through `TradeGearScaler` and the resale floor unchanged (`VillagerTrainSpawnEvents#sellsMap`), because the mod fills the stack it handed out; a copy would stay pending for good |

## DT systems

| System | Result |
|---|---|
| Spawning, mob systems | Not touched |
| Sable | Not touched (no physics, collision or block changes) |
| Fair play | Auto-whitelisted as a modpack mod; record added to `src/main/whitelist/approved_hashes.json` (8 Modrinth files) |
| Commands, HUD, network | No commands, no client code, no network calls, no tick handlers. Listeners: config reload, server start/stop (executor) |
| Text | The pending map is named with vanilla's `menu.working` key, so every locale is covered |
| Saves | A pending map is marked with custom data; the mod re-handles it. If the mod is removed, maps already filled stay, and a map still pending at removal stays unfilled |

## Performance cost

| Cost | Result |
|---|---|
| Modpack download size | +0.3 MB (299,325 bytes), no libraries |
| Boot time | Dedicated server `Done (`: see the Gate 2 report for the runs. No measurable change expected: no registry entries, 14 small mixins |
| World load | No world-gen hooks |
| Runtime | Nothing per tick. At x=60000, five explorer-map searches ran in 0.5 to 6 s each on `asynclocator-N` threads while the server kept 20 TPS (`time query gametime` +100 ticks per 5 s). One "Can't keep up" of 2.1 s came as several maps finished together, when finishing maps runs vanilla's map preview render on the server thread. On the server thread the same search took 96.5 s |

## Pack and release checks

| Check | Result |
|---|---|
| Licence | MIT in the jar, GitHub and both listings. Modpack use allowed |
| Same build on both platforms | Not compared (no CurseForge key). Same file name and version |
| NeoForge floor | `[21.1,)` against our 21.1.228: OK |
| Sides | Server logic. `displayTest = IGNORE_SERVER_VERSION`, so a client without it can still join a server. Singleplayer needs it, so DT declares it `side="BOTH"` and the `.mrpack` keeps it on clients (`compute_env`) |
| Dependency chain | None |
| Enabled or opt-in | Enabled (`required: true`) |
| Config defaults | Not shipped |
| Third-party mixins | DT does not mix into this mod |
| Changelog | No spoilers involved |
