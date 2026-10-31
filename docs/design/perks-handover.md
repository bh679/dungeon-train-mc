# Perks handover (2026-10-31)

Where the perks work stands after the 2026-10-27 review (`perks-review-2026-10-27.md`, which
holds every decision) and its follow-up PRs. All perk work merges into **dev/perks**, never main.

## On dev/perks now

- **Menu** (#2348), **code-driven stat chips, player descriptions, Coming soon** (#2358),
  **effect reworks** (#2364), **Perks button on the advancements screen** (#2373),
  **perks menu on death** (#2412).
- **Tier 1** (this PR, `dev/perks-tier1-stats`): Health, Speed, Damage, Stackable Book and
  Quill, Friendliness, See Trade Values, Bigger Backpacks, Craft Level Stats are built and
  show their chips. Drift Chance has code but stays Coming soon (`BuiltPerks.LOCKED`).
  - Friendliness's extra tame rolls now also apply when Good Gifter takes over a taming
    (`Friendliness.withExtraRolls`). Note a bone is not wolf "food" in vanilla, so bones never
    go through Good Gifter; beef does.
- Tier 2/3 built: Archivist, Crafter, Knight, Lover (Pet Health, Good Gifter), Trader
  (Reduced Reduction, Restock Villager).

## How a perk goes live

Its class's `declare()` (called from `perks/BuiltPerks#bootstrap`) calls
`BuiltPerks.markBuilt(ids)`, registers any `PerkEffect` handlers, and registers chips with
`PerkStats.register(perk, statId, r -> value)` using the effect's own maths. Each new
`statId` needs `gui.dungeontrain.perks.stat.<id>` in all 21 locales
(`scripts/localization/merge-locale-keys.py --request/--apply`, then
`stamp-provenance.py --sync --author 'Opus 5.5 (Claude)'`), which also restamps the shipped
credits. Client-only classes must not be referenced from `declare()`; put shared maths in a
common class (as `perks/tier1/SeeTradeValues`).

## Still waiting (merge order)

| Branch | State | To do |
|---|---|---|
| `dev/perks-foundation-tier1` | merged via `dev/perks-tier1-stats`; can be deleted | |
| `dev/perks-builder-tier2` | 6 ahead, cut from the old foundation | Re-merge on dev/perks; Drift Gift Repaid chips (35% +5%/point); Own Carriage Return stays locked with the "pick yours vs others" dev note |
| `dev/perks-tank` | 1 ahead, own shim | Drop the shim; Hunger ×0.95^r, Totem 30 × 0.93^r min, Healing 120 × 0.85^(r−1) s; armour and heart icons |
| `dev/perks-hoarder-tier3` | 1 ahead | Luck stacks past 100%; Looting becomes real vanilla Looting via `GetEnchantmentLevelEvent` |
| `dev/perks-speedster-tier2-3` | 2 ahead, own shim | Drop the shim; Wind Resistance and Sprint Hunger ×0.9^r; needs a moving-Sable test |

Builder must come after tier 1 (done now). Tank, Hoarder and Speedster can go in any order.

## Testing recipe

A temporary client harness (never committed) gated on `run/perksgate2.flag`: press
`DevQuickWorldHandler.creativeNewWorldRef` by reflection, skip the cinematic, set ranks with
`Perks.setUnlockLevel` + `Perks.setActive` on the integrated server, open `PerksScreen`, set
its `selected` field, hover a block via the bar's `graph.indexAt`, and save shots with
`Screenshot.grab`. No desktop screencapture.

## Not yet checked for tier 1

- Health after death and relog (re-applied by `AttributePerks.onPlayerReady`, untested).
- On a moving Sable train, multiplayer, dedicated server.
- Trade Everything values a diamond sword at 1/16 of an emerald: a trade value data issue,
  not the perk.
