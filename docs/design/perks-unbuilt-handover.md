# Unbuilt perks: design pass handover

**Branch:** `dev/perks-unbuilt-designs` (off `dev/perks`). Design only; no code yet.
**Design doc:** `docs/design/perks-unbuilt-designs.md` (one section per class, one entry per perk).
**Earlier review:** `docs/design/perks-review-2026-10-27.md` (approved descriptions for every perk).
**Last updated:** 2026-10-31.

## Where things stand

| Perk | Class / tier | State |
|---|---|---|
| Favourite Author | Archivist T2 | CONFIRMED 2026-10-27 |
| Share Hearts | Lover T2 | CONFIRMED 2026-10-28 |
| Spare Enemies | Lover T3 | SKIPPED (set aside, revisit later) |
| Aura | Lover T3 | CONFIRMED 2026-10-29 (per-buff ranges, 3-point cycle) |
| Save Ally | Lover T4 | CONFIRMED 2026-10-28 |
| Persuade Enemy | Trader T2 | SKIPPED ("too complicated"; draft noted in doc) |
| Reroll Trades | Trader T3 | CONFIRMED 2026-10-28 |
| Chest Sense | Hoarder T2 | CONFIRMED 2026-10-29 |
| Re-enchant | Crafter T3 | CONFIRMED 2026-10-29 |
| Debuff Aura | Knight T2 | CONFIRMED 2026-10-29 |
| Bonus Blocks | Builder T2 | CONFIRMED 2026-10-29 |
| Drift Reach | Builder T3 | DRAWN, NOT CONFIRMED (see below) |
| Keep Carriage | Builder T3 | not started. Approved desc: "Drifting carriages you have edited follow you into your next run." |
| Throwables | Warlock T1 | not started |
| Teleport Player | Warlock T2 | not started |
| Standless Brewing | Warlock T2 | not started |
| Summon Lightning | Warlock T3 | not started |
| Convert Mob | Warlock T3 | not started |
| Extra Journal | Archivist | blocked until Brennan defines "journal" |

### Drift Reach draft (awaiting Brennan)
- Desc (approved): "Your builds drift into more players' trains."
- Chips: `Trains at once 3 → 4` (1 + r, no cap; today every shared build is leased to one world at
  a time), `Front of the line Off → On at 5 points` (r ≥ 5: the owner's builds sort ahead of others
  with the same wait).
- Needs a **relay change** (`dp-relay/carriages.js` `lease`): the candidate query orders
  `(leaseToken IS NULL) DESC, leaseLastMsgTs ASC, id ASC` and allows one holder; it would read an
  owner reach value off the build row (sent by the mod on submit and on rank-up) to allow `reach`
  concurrent leases and to add a priority term. Mod side is a field on the submit body plus a rank
  sync. Any mod packet change bumps `PROTOCOL_VERSION`.

## How the design pass works (keep doing this)
- One perk at a time. Draw the perks-menu **details card** for every new perk and every change:
  an HTML file sent with `SendUserFile` (`display: render`, `status: normal`) AND a `show_widget`.
  Card = class-colour name, rank bar, `CLASS · TIER N`, description, chips with gold `→ next`
  preview, "Add a point" button, then the ladder table. Class colours: Lover `#f08cb0`, Trader
  `#5fd3a0`, Hoarder `#e0a85a`, Crafter `#8fb7ff`, Knight `#e05a5a`, Builder `#d9a066`; bar
  `#0f0d22`, chip `#1a1733`, gold `#f2c94c`.
- Brennan's ladder rules: steps **ease** (smaller each time), no hard cap unless he states one,
  **no two points identical** (every point changes at least one chip), and when he pastes a ladder
  use it verbatim and extend its pattern. Previews between steps read `→ N at K points`.
- On "Confirm X as drawn. Write it to the doc and move on.": append the entry (Mechanics, ladder,
  Details card, Build notes), commit with `git add docs/design/perks-unbuilt-designs.md` only
  (never `git add -A`), message `docs(perks): …`, then draw the next perk.
- Player-visible text: no long dashes.

## Build session (when all are confirmed)
Start a new session, **Opus 5.5, medium effort**, briefed from the design doc: implement every
CONFIRMED entry on `dev/perks` conventions (`BuiltPerks.markBuilt`, `PerkStats.register`, `Unit`
enum, lang keys `gui.dungeontrain.perks.stat.<id>` and `perk.dungeontrain.<id>.desc` in all 20
locales, immutable `PerkState`, `PROTOCOL_VERSION` bump on any packet change). Each perk's Build
notes name the existing hooks to extend (Restock Villager wiring for Reroll Trades, the
`REENCHANT_ON_HOLD` flag and enchant-menu mixin for Re-enchant, Aura machinery for Debuff Aura,
`CarriageDeck.chunkInPlot` for deck scans). Merges into `dev/perks` take a MINOR bump and their own
changelog entry.
