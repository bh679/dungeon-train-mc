# Adding a new mod to Dungeon Train

Run this audit before adding any mod to the modpacks or to DT's dependencies. Save the result as
`modpack/audits/<slug>.md` and put its summary tables in the Gate 1 plan. Run it again whenever the mod's pin moves.

**Audit the jar, not the listing.** Listings leave things out. Luminous Monsters' page never mentions that its
spawn lists cover every biome or that its eggs go in a vanilla tab. Unzip the pinned file, read `data/`,
`META-INF/neoforge.mods.toml` and the mixin configs, and use `javap -c -p` on registration classes where the data
files don't answer the question.

## The default rule

Everything a mod adds is **disabled unless it was requested**, or counted only where the table says so.

Report each category as a count in one table, then one collapsed section per category with the details:

```markdown
| Category | Count | Action |
|---|---:|---|
| Mobs | 16 | Kept (requested) |

<details><summary>Mobs (16)</summary>

| Id | Where it spawns | Notes |
|---|---|---|

</details>
```

| Category | Default | Where it's disabled |
|---|---|---|
| Main items: armour, weapons, tools | Disabled | `DisabledModContent` `NAMESPACES` or `GEAR_ONLY_NAMESPACES` (gear is found by item class) |
| Items with an unfair advantage or that break balance | Disabled | `DisabledModContent#HIDDEN_ITEMS` |
| Mobs | Disabled | Remove or override its biome modifiers; veto in `BandMobSpawnEvents` |
| Structures | Disabled | Override the structure set; check `structure/` band rules |
| Loot tables, and where each is found | Disabled | `StripDisabledItemsLootModifier`; a suppressor like `ExposureLootSuppressor` for injected tables |
| Enchantments and potion effects | Disabled | `isDisabledEnchantment` (automatic for mod enchantments) plus the `data/minecraft/tags/enchantment/` remove lists; effects case by case |
| Workbenches | Disabled | `DisabledModContent#DISABLED_BLOCKS`, enforced by `DisabledBlockUseGate` |
| Advancements and advancement tabs | Disabled | `ForeignAdvancementFilter`, the same as other modded advancements |
| New blocks | **Count only.** Disabled only if unfair | `DISABLED_BLOCKS` |
| Creative tabs and search | **Always hidden** | See below |

### Creative tabs and search

New tabs are hidden and the mod's items are left out of creative search. Players can turn them back on with the two
existing settings, `ClientDisplayConfig#isCreativeModBlockTabs` and `#isCreativeModBlocksInSearch`.

- **The mod has its own tab:** add its namespace to `DisabledModContent#HIDDEN_TAB_NAMESPACES`.
- **The mod adds items to vanilla's tabs:** add it to `HIDDEN_ITEM_NAMESPACES`. Check for this in the mod's tab
  class, because the tab rule above can't see it.
- **Recipe viewers:** JEI builds its list from the tabs, so hiding a tab hides its items there too. Add anything JEI
  must still show to `JEI_EXTRA_ITEMS`.

## Conflicts

| Check | How |
|---|---|
| Existing mods | Duplicate mod ids; `[[dependencies]]` marked `incompatible`; overlapping mixin targets; keybinds; shared registry names |
| Existing world gen | Biomes, features, carvers, surface rules, `worldgen/` overrides, TerraBlender regions, structure sets. Check that bands stay deterministic and existing worlds are unaffected |
| Existing loot tables | Global loot modifiers, `data/minecraft/loot_table/` overrides, injected tables |
| Vanilla data | Everything under `data/minecraft/`, especially tags with `"replace": true` and recipe overrides |
| Economy | Recipes, villager and wandering trader changes, Trade Everything values. Trades can bypass loot balance |

## DT systems a mod can reach

| System | What to check |
|---|---|
| **Spawn pools** | Weights and every `biomes` selector. See the warning below |
| **DT's own spawning** | The Nether band ignores biome lists: it cancels natural spawns and uses `NetherMobSpawner`'s lists. `BandMobSpawnEvents` cancels natural spawns in the upside-down and spheres bands, and `EndBandSpawnRules` admits whatever a biome lists |
| **Natural spawns** | A mob pack's natural spawns wait for the train: add it to `GATED_NAMESPACES` in `NaturalSpawnLevelGate` **and** `derive-mob-offsets.py`. Each mob unlocks at the lowest Diff-Level a room places it, and a mob in no room never spawns naturally |
| **Biome twins** | `VanillaBiomeTwins` keeps spawns added by biome modifiers, but replaces other changes to WWOO's biomes outside its stretch |
| **Sable** | Anything touching physics, collision or world interaction: test on a moving train |
| **Mob systems** | Base class decides difficulty gear (`DifficultyApplier#canHoldTools`). An `Enemy` joins `MobDifficultyEvents`. Mobs placed on the train need an entry in `scripts/advancements/mob-photos/categories.json` |
| **Fair play** | Everything in the pack is whitelisted unless marked `"whitelist": false`. Ask at Gate 3 |
| **Commands, HUD, network** | New commands, keybinds and overlays; outgoing network calls; auto-updaters; telemetry |
| **Text** | Third-party strings are often English only. Note the gap against DT's 20 locales |
| **Saves** | What existing worlds see, and what is lost if the mod is ever removed |

> **Spawn-pool warning.** `neoforge:add_spawns` with `{"type": "neoforge:any"}` adds the mob to every biome. The spawner
> picks a mob by weight first and only then runs its spawn check, so a mob rejected by that check still uses up the
> attempt. Compare the mod's added weight with the biome's existing total. Fix it by overriding the file at the same
> path in DT with a real selector, and declare the dependency `ordering="AFTER"` so DT's copy wins.

## Performance cost

Required in every audit. Use the same method each time so results compare between mods. If a difference is within
run-to-run noise, write "no measurable change" instead of a number.

| Cost | Method | Report |
|---|---|---|
| **Modpack download size** | Sum pinned file sizes before and after, including every new library | MB and % of the pack |
| **Boot time** | Server start to `Done (`, and client start to title screen. 5 runs with and 5 without, same machine | Median and spread |
| **World load time** | New world on a fixed seed, and rejoining an existing save. 5 runs with and 5 without | Median and spread |
| **Runtime** | Tick A/B with `scripts/perf/run-ab.sh`, plus the review below | Tick ms change |

Runtime review, read from the jar:

- Per-tick code: `ServerTickEvent` and `LevelTickEvent` handlers, entity `tick` or `baseTick` overrides.
- Spawn checks: how many run per spawn attempt and what they look up.
- World gen: new features, carvers and structures. Compare with `reference_gen_timing_slab_harness`.
- Mixins into hot paths: rendering, ticking, chunk loading, entity collision.
- Client: textures, models, particles, shaders, sounds.
- Memory: registry entries and caches.

`-PperfTest` turns mob spawning off, so `run-ab.sh` alone says nothing about spawn cost. A mod that needs its spawning
measured also needs a sample with spawning on. Mods that behave differently from the dev classpath get measured on
real jars with the `scripts/deptest/` server setup.

### Measuring spawns

Compare three arms: no mod, the mod as shipped, and the mod with DT's changes. Luminous Monsters' audit got misleading
results before these rules were followed:

- **Every arm gets a fresh world.** A reused world made one arm look 39% better than no mod at all.
- **Sample every few seconds.** Hostile mobs reach the cap of about 70 within 25 s, whatever the spawn pool, which hides any loss.
- **Let chunks settle first,** then discard that period. Newly generated areas fill slowly.
- **Record game ticks as well as time.** A busy machine slows the server, and that looks like fewer spawns.
- **Stand in the middle of the biome.** `/locate` lands on its edge, so small biomes barely count. Check variants there before calling one missing.
- **Run at least two rounds.** Use one player, with the same seed and position method.

## Pack and release checks

| Check | Why |
|---|---|
| Licence on **every** source: jar, CurseForge, Modrinth | They can disagree. Record modpack permission. Never copy art or code from an All Rights Reserved mod |
| Same build on both platforms (compare SHA-1) | Both packs should ship the file that was tested |
| NeoForge floor in the jar against `neo_version` | A floor above it stops the game loading (JEI and Enchantment Descriptions both did this) |
| Client and server sides; required network payloads | Mismatches refuse the join (What Are They Up To). Don't trust listing metadata: confirm from the code |
| Dependency chain | Each library needs its own pins and `mod_ids` |
| Enabled or opt-in | In the CurseForge app, `required: false` ships **off**. See [README](README.md#enabled-vs-disabled-by-default) |
| Config defaults | Anything shipped in `overrides/` must be allowlisted in `check-overrides.py` |
| Third-party mixins | Add a guard in `mixin/ThirdPartyMixinPlugin` if DT mixes into the mod |
| Changelog | New mobs stay secret. Follow the spoiler rules for hidden content |

### Wiring checklist

Hard dependency:

- [ ] `gradle.properties`: `<mod>_version` (Modrinth version id) and `<mod>_min_version`
- [ ] `build.gradle`: dependency and floor expansion
- [ ] `src/main/templates/META-INF/neoforge.mods.toml`: required entry
- [ ] `release.yml`: `<slug>(required)` in both dependency lists
- [ ] `scripts/deptest/`: a jar alias, in every case, plus one case without it

Every mod:

- [ ] `modpack.config.json`: `project_id`, `file_id`, `modrinth_project`, `modrinth_version`, `required`
- [ ] `python3 scripts/modpack/check-mod-ids.py --fill`, then `--verify`
- [ ] Approved hashes: `python3 scripts/modpack/pull-approved-hashes.py`. **Review the diff.** Run without
      `CURSEFORGE_API_KEY` it can drop unrelated mods; keep only the new mod's record if so
- [ ] `modpack/README.md`: roster row
- [ ] `check-pins.py`, `check-relations.py`, `build-mrpack.py --check-config`, `scripts/ci/run-local.sh`
- [ ] `scripts/deptest/run-all.sh` before the release

## Questions to answer before Gate 1

- Which categories were requested? Everything else stays disabled.
- Do any effects or abilities belong to a requested mob, and do they stay?
- Hard dependency, modpack on by default, or opt-in?
- Will all of the requested content actually appear in a normal run, given DT's spawn paths?
