# Audit: Balanced Totems 0.6.0

Audited 2026-11-12 from the GitHub release jar (`balancedtotems-neoforge-0.6.0.jar`, 115 KB), using [`../newmod.md`](../newmod.md).

| | |
|---|---|
| Mod id | `balancedtotems` (display "Balanced Totems"; store name "Dungeon Totems") |
| Repo | `bh679/balancedtotems-mc` — our own sibling |
| Pins | GitHub release `v0.6.0` via the shared `bh679` Ivy repo. Modrinth `dungeontotems` and CurseForge 1800270 are unapproved drafts: no store pins yet |
| Shipped as | jarJar'd inside every DT jar (like Sable Mob Paths). No modpack entry, no CurseForge/Modrinth relation |
| Licence | PolyForm Shield 1.0.0 (ours) |
| Sides | Client and server: three S2C payloads (totem pop, morph sync, curse count) |
| NeoForge floor | `[1,)` loader, `neo_version` 21.1.228 at build; cleared by DT |
| Dependencies | None. Optional PlayerMob integration by reflection (`compat/PlayerMobGifts`) and the `rampage_targets` tag |

## Counts

| Category | Count | Action |
|---|---:|---|
| Items (totems) | 14 | Kept (requested). Evokers drop them; DT's loot rolls them through `dungeontrain:random_totem` |
| Items with an unfair advantage | 0 | The Void Totem is steered onto the train (`compat/BalancedTotemsBridge`) |
| Mobs | 0 | None |
| Structures / world gen | 0 | None |
| Loot | 1 global loot modifier | Kept: replaces the vanilla totem in `minecraft:entities/evoker` |
| Mob effects | 8 | Kept: they are the curses. Not reachable from DT's random potions (vanilla-only tiers) |
| Damage types | 6 | Kept: deaths from a broken curse. They end a run like any other death |
| Attachments | 4 | Per-player curse state (form, rampage kills, lonely friends) |
| Enchantments | 0 | None |
| Workbenches / blocks | 0 | None |
| Advancements | 0 | None |
| Creative tabs | 0 | The mod registers none, so nothing to hide |
| Mixins | 0 | None |

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No duplicate ids, no `incompatible` entries, no mixins, no keybinds |
| World gen | None |
| Loot tables | Evoker GLM only. DT has no evoker loot override. `StripDisabledItemsLootModifier` does not touch `balancedtotems` |
| Vanilla data | `replace:false` tags only: six `minecraft:tags/damage_type/bypasses_*` + `no_knockback` additions for its own damage types |
| Economy | No recipes, no trades. No Trade Everything values yet |

## DT systems it reaches

| System | Result |
|---|---|
| Rage / Loneliness targets | With `dungeontrain` loaded villagers never count (`Targets.villagersStandIn`). PlayerMobs count by the `playermob:player_mob` tag entry and by chaining `PlayerMobSocialHooks`' observer at ServerStarted, after DT installs its own at commonSetup. Signatures checked against playermob 0.119.0 |
| Death | Totem saves cancel `LivingDeathEvent` at HIGHEST, ahead of every DT death handler |
| Void Totem | Lands on the nearest front pad of the train (`BalancedTotemsBridge`), with DT's deck hold + harmless window |
| Sable | Teleport onto a moving sub-level: Gate 2 tested on a moving train |
| Fair play | Embedded ⇒ auto-approved by `generateApprovedMods`; `unmatched` in `approved_hashes.json` until it has a listing |
| Text | `en_us` only. Its item names and curse messages are English in DT's other locales |
| Saves | Curse state lives in player attachments. Removing the mod drops the totems and curses; nothing in the world |

## Performance

| Cost | Result |
|---|---|
| Download size | 115 KB inside the DT jar |
| Boot / world load | No world gen, no mixins, 14 items + 8 effects: no measurable change expected; measured at Gate 2 |
| Runtime | Two `PlayerTickEvent.Post` handlers. One is a map lookup that returns at once without a pending curse death; the other only works for a morphed player |
