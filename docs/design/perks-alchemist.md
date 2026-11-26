# Alchemist perks: designs (2026-11-26)

The Alchemist class, settled with Brennan one perk at a time. It follows the conventions of
`perks-unbuilt-designs.md` (on `dev/perks-unbuilt-designs`): r = points in the perk; chips carry the
numbers, descriptions say what the perk does; ladders ease; no caps unless stated; every point
changes at least one chip; previews read `→ N at K points`.

## Class

- **Alchemist**, tree class id `calchemist`, colour `#5fc8e0` (cyan). Confirmed 2026-11-26 after
  seeing it in the constellation editor.
- **Placement** (Brennan placed the stars in the editor, 2026-11-26): the wedge sits between Lover
  and Warlock. Stack Potions on the tier 1 ring; Standless Brewing (moved here from Warlock) at
  581.4, 328.6; Brewmaster at 657.2, 245.5. The editor re-spaced the tier 1 ring for eleven classes
  and moved Teleport Player to 603.2, 374.
- **Links:** Engine → Stack Potions → Standless Brewing → Brewmaster (req). Cross links
  Friendliness ↔ Stack Potions ↔ Throwables (the old Friendliness ↔ Throwables cross link goes).
  Throwables → Standless Brewing and Standless Brewing → Summon Lightning stay (Brennan kept them,
  one way), so Warlock still reaches Summon Lightning through the brewing star or Crits.

---

### Stack Potions (tier 1) — CONFIRMED 2026-11-26

**How it works**
- Drinkable, splash and lingering potions in the player's inventory stack to `1 + r` (vanilla 1).
  Only identical potions stack (same item and components). 99 is the game's own limit, reached at
  98 points.

**Ladder:** 1 → 2, 2 → 3, 3 → 4 … r → 1 + r … 98 → 99.

**Details card**
- Description: "Identical potions stack together."
- Chip: `Stack 3 → 4` (PLAIN).

**Build notes**
- Same pattern as Stackable Book and Quill (`perks/tier1/BookQuillStacking`): stamp
  `DataComponents.MAX_STACK_SIZE` on potion stacks in the inventory, strip it when a potion leaves
  the player (toss, death drops) or the rank drops to 0, re-walk once a second.
- Vanilla `PotionItem.finishUsingItem` already handles drinking from a stack (one consumed, the
  glass bottle goes to the inventory); splash and lingering consume one.

---

### Standless Brewing (tier 2) — CONFIRMED 2026-11-26 (reworked from Warlock's design)

**How it works**
- A **Brew** button on the inventory screen opens the player's own brewing stand: fuel slot,
  ingredient slot and `1 + floor(r / 5)` bottle slots (wider than vanilla's 3 once past 10 points).
- It keeps brewing on the server while the screen is closed. Its contents are saved with the player.
- **Brew time** `20 s × 0.9^r` (vanilla 20 s; never 0, at least one tick).
- **Free fuel:** one fuel (one brew, whatever the number of bottles) is added every
  `300 s × 0.9^(r − 1)` (5 min at 1 point), up to a full bar of 20. A blaze powder is still 20 fuel.

**Ladder**

| r | Bottle slots | Brew time | Free fuel every |
|---|---|---|---|
| 1 | 1 | 18.0 s | 5.0 min |
| 2 | 1 | 16.2 s | 4.5 min |
| 4 | 1 | 13.1 s | 3.6 min |
| 5 | 2 | 11.8 s | 3.3 min |
| 10 | 3 | 7.0 s | 1.9 min |
| 15 | 4 | 4.1 s | 69 s |
| 20 | 5 | 2.4 s | 41 s |
| 30 | 7 | 0.8 s | 14 s |

**Tick floor.** Brew time reaches a single tick at about 57 points and the free-fuel wait counts
whole ticks, so from 67 points some points change no chip (the game's own limit, as 99 is for a stack).

**Details card**
- Description: "Your own brewing stand, opened from your inventory. It keeps brewing while you play."
- Chips: `Bottle slots 1 → 2 at 5 points`, `Brew time 13.1s → 11.8s`,
  `Free fuel every 3.6 min → 3.3 min`.

---

### Brewmaster (tier 3) — CONFIRMED 2026-11-26

**How it works**
- Potions the player brews come out stronger: extra effect levels rolled once per bottle when a
  brew finishes, on the `PerkOverflow` curve at `33⅓% × r` (Brennan, 2026-11-26: start with a 33%
  chance of +1).
- Levels only. The earlier draw's duration bonus is dropped (Brennan, 2026-11-26).

**Ladder**

| r | Extra levels | Strength I brewed becomes |
|---|---|---|
| 1 | 33% chance of +1 | sometimes II |
| 2 | 66% chance of +1 | often II |
| 3 | +1 | Strength II |
| 4 | +1, 33% chance of +2 | II, sometimes III |
| 5 | +1, 66% chance of +2 | II, often III |
| 6 | +2 | Strength III |
| 9 | +3 | Strength IV |

**Details card**
- Description: "Potions you brew come out stronger."
- Chips as Luck and Looting: `Level +1 chance 33% → 66%`; from 3 points `Level +1 always`, then
  `Level +2 chance 33%` and so on.
