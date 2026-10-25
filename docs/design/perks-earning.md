# Perks: how points are earned

Two currencies feed the perks tree.

| | Unlock points | Active points |
|---|---|---|
| Earned from | advancements | eating cosmic apples |
| Spent on | unlocking a perk (tier 1/2/3 cost 1/5/10) | ranks on unlocked perks |
| On death | kept | lost, with every rank they bought |
| Unspent | kept forever | lost on death |

## Unlock points (Advancement Points)

Each advancement that shows in the advancements screen is worth:

| frame | points |
|---|---|
| task | 1 |
| goal | 2 |
| challenge | 3 |
| hidden (any frame) | +1 |

`recipes/*`, `editor/*` and tab-head copies (`TabGateways.isLinked`) count for nothing.

**Derived, not accumulated.** `earned` is recomputed from the advancements the player has done
right now (`perks/PerkPointEvents#recompute`), and `available = max(0, earned - spent)`, where
`spent` is the tier cost of every unlocked perk (the foundation installs it through
`perks/PerkSpend#setSpentFunction`). So:

- relogging, the login replay from `GlobalAchievementStore` and a difficulty-partition swap can
  never credit an advancement twice;
- existing players are credited for everything they already earned, on their first login after
  this ships, silently (no chat line);
- a revoked advancement lowers `earned`; the balance clamps at 0 and no unlocked perk is taken back;
- each difficulty keeps its own advancement profile, so each shows its own total.

Earns only mark the player dirty; the next player tick recomputes once, so a login replay of
hundreds of earns costs one pass. A genuine earn prints `+N Advancement Points (M unspent)`.

The advancements screen shows the unspent total in a small box under the window's bottom-right
corner, the same on every tab; hovering it says "Advancement Points" (`client/AdvancementPointsBadge`;
Better Advancements' window bounds come from `BetterAdvancementsScreenBoundsMixin`).

## Active points and cosmic apples

Three tiers, one class (`item/CosmicAppleItem`), all stacking to 8, always edible, eaten fast, 1 hunger:

| item | points | name colour |
|---|---|---|
| `dungeontrain:cosmic_apple` Cosmic Apple | 1 | white |
| `dungeontrain:celestial_bite` Celestial Bite | 5 | aqua (rare) |
| `dungeontrain:violet_core` Violet Core | 10 | purple (epic) |

Eating one adds its points, prints the new count and calls `PerkMenu.openPerksMenu(player)`, which
sends `OpenPerksMenuPacket` and opens the perks menu. Eating any of them never breaks the
melon-diet challenge (`CosmicAppleItem.isCosmicApple`). They replaced Cosmic Debris before it shipped.

Active points live in the `perk_active_points` attachment: saved with the player, copied on End
return, not `copyOnDeath`, and cleared on respawn.

### What a rank costs

Every rank on any perk costs active points, and the price rises with the ranks bought this life,
counted across all perks (`perks/PerkRankCost`). Tier does not change it; tier is paid once, in
Advancement Points, to unlock.

| price | ranks at that price | rank numbers | running total |
|---|---|---|---|
| 1 | 10 | 1 to 10 | 10 |
| 2 | 9 | 11 to 19 | 28 |
| 3 | 8 | 20 to 27 | 52 |
| 4 | 7 | 28 to 34 | 80 |
| 5 | 6 | 35 to 40 | 110 |
| 6 | 5 | 41 to 45 | 140 |
| 7 | 4 | 46 to 49 | 168 |
| 8 | 3 | 50 to 52 | 192 |
| 9 | 2 | 53 to 54 | 210 |
| 10 | 1 | 55 | 220 |

After rank 55 each rank costs more than the last by a jump one bigger each time: 11, 13, 16, 20 …
The run at each price shrinks so each price rise still bites (1 to 2 doubles, 9 to 10 would not
if it lasted as long). Death clears every rank, so the next life starts at 1 again. Active points
too few for the next rank stay in the balance until another apple is eaten, or are lost on death.

### Where they drop

About one carriage group in five holds one cosmic apple, in one of its chests, barrels or shulker
boxes (`perks/CosmicAppleDrops`, called from `TrainAssembler.spawnGroup` right after the drawn
carriage's contents pass, before a fresh shared carriage registers, so it never counts as an edit).

- The draw is a seeded hash of (world generation seed, group index), so a group rolls the same way
  every time, like the rest of its loot. Held groups reload with their containers, so no second roll.
- The first band (position tier 0) gets none.
- A drawn group whose chosen carriage has no container with a free slot gets none (relay-leased and
  whole-room carriages have no contents pass), so the real rate is a little under one in five.
- No loot prefab carries any of them (`CosmicAppleLootTest`).
- Which apple depends on the drop's Diff-Level (`CosmicAppleDrops.tierFor`, its own seeded roll), so
  the rarer ones only turn up deeper. At the default 20 carriages per level, Celestial Bites start
  about 100 carriages in and Violet Cores about 200:

  | Diff-Level | Cosmic Apple | Celestial Bite | Violet Core |
  |---|---|---|---|
  | 1 to 4 | 100% | | |
  | 5 to 9 | 80% | 20% | |
  | 10 and up | 65% | 25% | 10% |
