# Perks: how points are earned

Two currencies feed the perks tree.

| | Unlock points | Active points |
|---|---|---|
| Earned from | advancements | eating Cosmic Debris |
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

## Active points and Cosmic Debris

`dungeontrain:cosmic_debris`: stacks to 8, always edible, eaten fast, 1 hunger. Eating one gives
one active point, prints the new count and calls `PerkMenu.openPerksMenu(player)`, which sends
`OpenPerksMenuPacket`; the client's `PerksMenuOpener.open` is a chat-line stub until the menu
screen lands. Eating it never breaks the melon-diet challenge.

Active points live in the `perk_active_points` attachment: saved with the player, copied on End
return, not `copyOnDeath`, and cleared on respawn.

### Where it drops

About one carriage group in five holds one Cosmic Debris, in one of its chests, barrels or shulker
boxes (`perks/CosmicDebrisDrops`, called from `TrainAssembler.spawnGroup` right after the drawn
carriage's contents pass, before a fresh shared carriage registers, so it never counts as an edit).

- The draw is a seeded hash of (world generation seed, group index), so a group rolls the same way
  every time, like the rest of its loot. Held groups reload with their containers, so no second roll.
- The first band (position tier 0) gets none.
- A drawn group whose chosen carriage has no container with a free slot gets none (relay-leased and
  whole-room carriages have no contents pass), so the real rate is a little under one in five.
- No loot prefab carries it (`CosmicDebrisLootTest`).
