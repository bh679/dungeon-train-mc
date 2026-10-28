# Unbuilt perks: designs (2026-10-27)

Designs for the perks that have no effect code, settled with Brennan one at a time. Each entry is
the brief a build session works from: how it works, the details card (description and chips), and
build notes. Conventions follow `perks-review-2026-10-27.md`: r = points in the perk; chips carry the
numbers, descriptions say what the perk does; no hidden maximums on the card.

Order of the pass: Archivist, Lover, Trader, Hoarder, Crafter, Knight, Builder, Warlock.

---

## Archivist

### Favourite Author (tier 2) — CONFIRMED 2026-10-27

Authors are **players** (relay accounts, by player name), not in-game characters.

**How it works**
- **Following.** Three ways in: a Follow button on the vote page of a found community book (next to
  the boost star); a Follow button beside Tribute on another player's photo; and the Followed Authors
  menu, where the player types a name. Unfollow from the menu.
- **Slots.** r = active follow slots. More follows than slots (points reset on death): the newest
  follows are active, the rest stay saved and greyed as inactive until a point returns.
- **Share.** When a community book spawns for the player, the chance it is an unread book by an
  active followed author (if any are left) is `15% + 2% × active followed authors`. Otherwise the
  normal pick. The same share applies to shared photos placed in frames. The +2% rule is not
  written anywhere on the card.
- **Library rooms.** Author-locked library rooms prefer active followed authors.
- **Persistence.** Follows are stored on the relay against the player's account, so they survive
  deaths and worlds. Free Play: perk off like every perk; the list is kept.

**Details card**
- Description: "Follow players to find more of their books and photos."
- Chips: `Authors 2 → 3` (r, PLAIN, with the hover preview) and `Their share 17%` (PERCENT, the
  current share; previews the next point's value).
- After the chips, once unlocked: a **Following N (M active)** button that opens the menu.

**Followed Authors menu** (a small screen over the perks menu)
- Header: "Followed Authors" and "M of N active".
- One row per follow: skin head, player name, then on the right either "K found this life" or
  "No more books" (nothing unread left from that player on the relay), then Unfollow. Inactive rows
  are greyed with "Inactive. Needs a point."
- Bottom: a **Follow** text box. Autocompletes from the names of players whose books or photos this
  player found this life. Enter asks the relay. Found: the name turns green with a check and becomes
  a row at "0 found this life". Not found: red with a cross, "No player with that name", only Remove.
  An unfound name never takes a slot.
- Never show how many books an author has.

**Build notes**
- Relay: an author token (or player uuid) on each row of `/books/pool` and `/photos/pool`; a
  follows endpoint (list, add by name, remove) that returns per-follow `found this life` and an
  `exhausted` flag; name lookup must match relay accounts even if they have never signed a book.
- Mod: the pick goes into `SharedBookSelector` as a tier above the weight tier, and into
  `SharedPhotos.rollFound`; `PortalRoomAuthorLocks.choose` prefers followed tokens. Follow
  buttons: `BookVoteClientEvents` page and `PhotoTributeButtons`. The menu is a new client screen
  opened from `PerksScreen`'s details bar. Follows cache in a `copyOnDeath` attachment mirrored
  from the relay.

---

## Lover

### Share Hearts (tier 2) — CONFIRMED 2026-10-28

Replaces the review's "Heal Friends" (feeding). Same tree slot; rename the perk id/name/desc.

**How it works**
- Shift + right-click a player, a PlayerMob, or a tamed pet. Nothing in hand is needed; with food
  in hand the normal food gift (Good Gifter / vanilla feeding) takes priority and this does not fire.
- You lose `cost` of your own health, they gain `gift`, capped at what they are missing. Target at
  full health: nothing happens, nothing spent. Never takes you below 1 heart.
- 2-second cooldown per target so a held button does not drain the giver in one burst.
- Chance points: the extra half heart is rolled per gift (50%). The target sees it land or not.
- Ladder (cost : gift), half-heart units, no cap:

  | r | Cost | Gift |
  |---|------|------|
  | 1 | 1 | ½ |
  | 2 | 1 | ½ or 1 (50%) |
  | 3 | 1 | 1 |
  | 4 | 1 | 1 or 1½ |
  | 5 | 1 | 1½ |
  | 6 | 1 | 1½ or 2 |
  | 7 | ½ | 1 |
  | 8 | ½ | 1 or 1½ |
  | 9 | ½ | 1½ |
  | 10 | ½ | 1½ or 2 |
  | 11+ | ½ | same rhythm: even points add a 50% extra ½, odd points make it certain |

  Formula from r = 7: base = `1 + ½ × floor((r − 7) / 2)`, chance of +½ when `(r − 7)` is odd.
  Points 1 to 6 follow the same shape with base `½ + ½ × floor((r − 1) / 2)` and cost 1.

**Details card**
- Description: "Right click a friend while in shift to give them some of your hearts."
- Chips, both drawn in heart sprites (HEARTS unit): `Cost ♥ 1` and `Gift ♥ 1 or 1½`. On a certain
  point the Gift chip is `Gift ♥ 1`. The gold preview shows the next point's form, e.g. `→ 1½` or
  `→ 1 or 1½`. Point 7's preview is a visible step down on Gift while Cost halves; accepted.

**Build notes**
- Hook `PlayerInteractEvent.EntityInteract` (shift held, target Player / PlayerMob /
  TamableAnimal), alongside `GoodGifterEffects`. Server-side heal + `hurt`-free health reduction
  on the giver (direct `setHealth`, no damage event, so armour and Resistance do not apply).
- Needs a new "or" form in `PerkStats.Value` (two HEARTS values) for the chip renderer.

### Spare Enemies (tier 3) — SKIPPED 2026-10-28

Brennan may change or remove it. Not designed in this pass; stays "Coming soon".

### Aura (tier 3) — CONFIRMED 2026-10-28

Replaces the review's "Shared Aura" (mirroring the Lover's own buffs). Rename the perk; the aura
gives its own buffs.

**How it works**
- Players, PlayerMobs and tamed pets within Range get the aura's buffs. Not the Lover themself.
- Reapplied every second while in range, with a 5-second tail so it fades shortly after they leave.
  A friend who already has a stronger version of an effect keeps theirs; the aura never lowers one.
- **Pool (10):** Regeneration, Speed, Resistance, Strength, Haste, Jump Boost, Fire Resistance,
  Absorption, Slow Falling, Water Breathing.
- **Per point:**
  - Points 1 to 10: one buff drawn at random from the pool entries not yet owned. Point 1 also sets
    range 4 blocks. The draw order is rolled per life (points reset on death).
  - Point 11: range 5 blocks.
  - Points 12 to 19: a random level II among the owned buffs that have one (Fire Resistance and
    Water Breathing ignore the amplifier, so they are skipped). Point 20: range 6. Then level III
    the same way, then range, and so on. No cap.
- **Toggles.** Each owned buff can be switched off by the player. A switched-off buff stays owned
  (still counts toward "all ten" for the range point), is drawn in grey, and is skipped by the
  level rounds until switched back on. Toggles are saved with the perk state and survive death.

**Details card**
- Description: "Friends and pets near you get buffs."
- Chips: `Range 4 blocks` (BLOCKS) first, then one green chip per owned buff with its level
  (`Resistance`, `Haste II`), switched-off ones greyed and struck through. The last chip is the
  gold dashed preview for the next point: `→ a new buff`, `→ Range 5 blocks`, or `→ a level II`.
  No numeric previews; the next chip is the preview.
- Clicking a buff chip toggles it. Hint line under the chips: "Click a buff to turn it off or on."

**Build notes**
- Perk state needs per-life data beyond the rank: the ordered list of drawn buffs with levels, the
  range step, and the toggle set (toggles persist across death; draws do not). A new attachment or
  an extension of `PerkState`; a client→server packet for the toggle (bump `PROTOCOL_VERSION`).
- Server tick every 20 ticks: for each Lover with the perk, apply `MobEffectInstance(effect,
  120 ticks, level−1, ambient=true, showParticles=false)` to targets in range unless they already
  have a higher amplifier. Reuse the friend test from Share Hearts / Good Gifter.
- Card: `PerksScreen`'s chip row needs a chip kind that lists effects and is clickable.

### Save Ally (tier 4) — CONFIRMED 2026-10-28

**How it works**
- A friend (player, PlayerMob or tamed pet) within Range who takes a killing hit is saved exactly
  as a totem of undying would save them: left at one heart (2 health), Regeneration II 45 s,
  Absorption II 40 s, Fire Resistance 40 s, totem flash and sound on them.
- The cooldown (`Every`) is the Lover's, not per friend: one save, then nothing for that long.
  No chance roll. Not the Lover themself. Void and `/kill` deaths are not saved. Works in one-life.
- Ladder (points 1 to 7 set by hand; from 8 the wait eases toward 8 min and never reaches it):

  | r | Range | Every |
  |---|-------|-------|
  | 1 | 6 blocks | 30 min |
  | 2 | 7 | 25 |
  | 3 | 8 | 21 |
  | 4 | 8 | 19 |
  | 5 | 9 | 17.5 |
  | 6 | 9 | 16.5 |
  | 7 | 10 | 16 |
  | 8+ | +1 block every 2 points (10, 10, 11, 11, 12, …) | `8 + (prev − 8) × 0.94` each point: 15.5, 15.1, 14.6, 14.2, 13.9, … (20: 11.6, 30: 9.9, 50: 8.6) |

  No two points are the same; no cap.

**Details card**
- Description: "Once in a while, a friend who dies near you is saved like a totem would."
- Chips: `Range 9 blocks → 9` (BLOCKS) and `Every 17.5 min → 16.5 min` (MINUTES, shown to one
  decimal, e.g. `15.1 min`; whole minutes drop the `.0`).

**Build notes**
- Hook `LivingDeathEvent` (like `KnightCombatEvents.onKill`): if the victim is a friend of a
  Lover with the perk within Range and that Lover's cooldown has passed, cancel the event,
  `setHealth(1.0F)`, clear effects as the totem does, add the three totem effects, and send the
  totem packet (`ClientboundEntityEventPacket` id 35) so the flash and sound play.
- Cooldown: last-save game time stored per Lover in an attachment that is **not** copyOnDeath
  (a new life starts ready).
- `PerkStats` MINUTES unit needs a fractional form for this chip.

---

## Trader

### Persuade Enemy (tier 2) — SKIPPED 2026-10-28

Too complicated for this pass (a per-mob table of anger sources and gift items). Stays "Coming
soon". The draft that was set aside: point 1 stops every neutral mob starting on you; gifts
(gold, flower, meat, bamboo, iron, chorus fruit, wheat) calm an angry neutral mob with a Calm
chance easing toward 100% and spread to its pack within a growing range.

### Reroll Trades (tier 3) — CONFIRMED 2026-10-28

**How it works**
- A dice button beside Restock Villager's on the villager trade screen. Pressing it rolls the
  villager's whole trade list again for its profession and level, as if it had just reached that
  level: all stock full, no demand price rises. Level and XP untouched. Not wandering traders.
- **Cap:** r rerolls per villager per life (like Restock's charges).
- **Free ones:** `floor(r / 10)` of those are free and are spent first.
- **Price:** the paid rerolls cost emeralds taken from the player's inventory: 1 for the first paid
  reroll of that villager, then doubling (2, 4, 8, 16, …) with no cap. The price is per villager per
  life and does not depend on points.
- Example at 10 points: free, 1, 2, 4, 8, 16, 32, 64, 128, 256.

**Details card**
- Description: "Reroll a villager's trades for emeralds. Each reroll of the same villager costs
  twice as much."
- Chips: `Rerolls per villager 2 → 3` (PLAIN) and `Free 0 → 1 at 10 points` (PLAIN; the preview
  names the point at which the next free one arrives, since most points do not change it).
- Button on the trade screen: dice glyph, rerolls left on this villager, and the next price with
  the emerald sprite (`⚄ 8 · 2 [emerald]`), or FREE while free ones remain. Greyed when none are
  left or the player cannot pay. Hover: "Reroll trades (N left, costs M emeralds)".

**Build notes**
- Mirror `TraderPerkEvents` / `RestockVillagerButton` / the status + request packets (new packet
  pair or a widened one; bump `PROTOCOL_VERSION`). Per-villager use counts in the same per-life
  store Restock uses.
- Reroll = clear `villager.getOffers()` and re-add `VillagerTrades.TRADES` for the profession,
  one tier at a time up to the villager's level (what `Villager#updateTrades` does per level),
  then `setOffers` and resend the menu's offers.
- Emerald payment: count `Items.EMERALD` across the inventory, refuse if short, remove the exact
  amount server-side before rerolling.

---

## Hoarder

### Chest Sense (tier 2) — CONFIRMED 2026-10-29

**How it works**
- On a timer, no button. Starts at the first point and runs while the player is alive.
- When it fires: a sound plays and a ring of particles pulses out from the player to the edge of
  Range; every **unopened, non-empty** container within Range (a sphere) glows through walls for
  **15 s**. Containers: chests, trapped chests, barrels, shulker boxes, vases, backpack-apple
  chests; on the ground and on carriages. Containers anyone has opened, and empty ones, do not
  glow. Nothing shows which has the best loot.
- Ladder (points 1 to 9 set by hand):

  | r | Range | Every |
  |---|-------|-------|
  | 1 | 5 blocks | 6 min |
  | 2 | 6 | 5 |
  | 3 | 7 | 4.8 |
  | 4 | 8 | 4.6 |
  | 5 | 9 | 4.5 |
  | 6 | 10 | 4.3 |
  | 7 | 11 | 4.2 |
  | 8 | 12 | 4.1 |
  | 9 | 13 | 4 |
  | 10+ | +1 block per point | −0.1 min per point; floor 1 min (reached at point 39), never less |

**Details card**
- Description: "Every few minutes, sense nearby chests through walls."
- Chips: `Range 6 blocks → 7` (BLOCKS) and `Every 5 min → 4.8 min` (MINUTES, one decimal, as
  Save Ally).

**Build notes**
- Server tick per Hoarder with the perk: countdown in a per-life attachment; on fire, collect
  container block entities in range (main level **and** Sable plots: `CarriageDeck.chunkInPlot`
  for plot chunks, never a per-block scan), filter by DT's opened-container tracking (loot table
  still unrolled, or `LootedContainers` if that exists for vases) and non-empty inventory, send
  one packet with the world-space positions and the range (bump `PROTOCOL_VERSION`).
- Client: 15 s of a glowing outline per position (render through walls, like the entity glow
  shader on a block box), an expanding particle ring from the player to Range over ~1 s, and a
  sound event. Plot positions must be re-transformed each frame as the carriage moves.

