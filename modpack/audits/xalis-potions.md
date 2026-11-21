# Audit: xali's Potions v1.2.0 (resource pack)

Audited 2026-11-21 from the pack zip, using [`../newmod.md`](../newmod.md). **Not added to the modpack yet:** this
audit is the report for that decision.

| | |
|---|---|
| Type | Resource pack, not a mod (no mod id; Free Play never checks resource packs) |
| Candidate pins | CurseForge 510849 (texture pack); Modrinth `yFn9hTiq` / file `xali's potion v1.2.0.zip`, 346 KB |
| Licence | CC BY-NC 4.0 on CurseForge and Modrinth, and in the zip. **Non-commercial**: needs your call, since the pack sits beside paid support links |
| `pack.mcmeta` | Formats 15–79 (MC 1.20 to 1.21.11), so 1.21.1 (format 34) loads it without a warning |

## Result: it does nothing on our 1.21.1 NeoForge client

The pack holds its potion textures in three places, and none of them reaches a stock 1.21.1 NeoForge game:

| Folder | Used by | On 1.21.1 NeoForge |
|---|---|---|
| `assets/minecraft/items/*.json` + `textures/item/*.png` | The 1.21.4+ item-model format | Ignored: 1.21.1 has no `items/` definitions |
| Overlay `55/` → `models/item/` | Pack formats 55–79 (1.21.4+) | Not applied: overlay doesn't match format 34 |
| Overlay `32-64/` → `optifine/cit/*.properties` | OptiFine CIT (Custom Item Textures) | The only path for 1.21.1, and NeoForge has no CIT loader in the pack |

Tested in game with the pack selected (the log shows `file/xalis.zip` in the resource manager): every potion, splash
potion and lingering potion drew as the vanilla bottle (screenshot 01). The pack's own texture is a tall thin vial
(screenshot 06).

## To make it work

It would need a CIT loader for NeoForge 1.21.1 in the pack. That is a new mod with its own audit (render mixins, Sodium
and Iris compatibility). Alternatively, DT could ship its own potion art through item-model predicates, which needs no
third-party pack and no NC licence question.

## Shipping a resource pack (if it ever works)

The modpack pipeline ships no third-party resource packs today. It would need:

- CurseForge: the pack as a manifest file entry (project 510849), which the CurseForge app installs into
  `resourcepacks/`.
- Modrinth: a `files[]` entry in the `.mrpack` with `path: resourcepacks/...` and the Modrinth URL + hashes.
  `build-mrpack.py` only emits `mods/` today.
- Selection: an installed pack is off until the player selects it. DT's `CompanionResourcePackAutoEnabler` only
  auto-selects its own `DungeonTrain-*-compat.zip` packs.
- `check-overrides.py`: no change if it ships as a file entry rather than an override.

## Performance cost

+0.34 MB download. Boot time was measured with ismah and BEB; see `beautiful-enchanted-books.md`. No runtime cost
while it draws nothing.

## Verdict

**Don't add it.** It has no effect on our game version and loader, and its licence is non-commercial.
