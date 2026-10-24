# Perks: how points are earned

Two currencies feed the perks tree.

| | Unlock points | Active points |
|---|---|---|
| Earned from | advancements | eating Cosmic Debris |
| Spent on | unlocking a perk (tier 1/2/3 cost 1/5/10) | ranks on unlocked perks |
| On death | kept | lost, with every rank they bought |
| Unspent | kept forever | lost on death |

## Unlock points

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
hundreds of earns costs one pass. A genuine earn prints `+N unlock points (M unspent)`.

## Active points and Cosmic Debris

`dungeontrain:cosmic_debris`: stacks to 16, always edible, eaten fast, 1 hunger. Eating one gives
one active point, prints the new count and calls `PerkMenu.openPerksMenu(player)`, which sends
`OpenPerksMenuPacket`; the client's `PerksMenuOpener.open` is a chat-line stub until the menu
screen lands. Eating it never breaks the melon-diet challenge.

Active points live in the `perk_active_points` attachment: saved with the player, copied on End
return, not `copyOnDeath`, and cleared on respawn.

### Where it drops

Plain weighted entries in the loot prefabs (`data/dungeontrain/prefabs/loot/`). The first band
gets none: its `loot` containers are downgraded to `starter`
(`DifficultyProgression.effectiveLootPrefabId`), which carries no debris.

| prefab | weight / pool | about per container |
|---|---|---|
| loot | 1 / 86 | 1 in 10 |
| loot_irongold | 2 / 369 | 1 in 30 |
| netherloot | 2 / 81 | 1 in 7 |
| ancient_vase | 1 / 189 | 1 in 200 |
| end (portal room) | 3 / 23 | most rooms |
| upsidedown (portal room) | 3 / 22 | most rooms |

Target: about one per band in the early bands, rising in the Nether and the portal rooms. The
roller has no band input; the rise comes from which pools later bands use. All numbers are data;
tune them in the prefabs (`CosmicDebrisLootTest` pins them).
