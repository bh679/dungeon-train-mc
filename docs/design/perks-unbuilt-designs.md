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

### Aura (tier 3) — CONFIRMED 2026-10-29 (replaces the 2026-10-28 version)

Replaces the review's "Shared Aura" (mirroring the Lover's own buffs). Rename the perk; the aura
gives its own buffs, each with **its own range**.

**How it works**
- Players, PlayerMobs and tamed pets within a buff's range get that buff. Not the Lover themself.
- Reapplied every second while in range, with a 5-second tail so it fades shortly after they leave.
  A friend who already has a stronger version of an effect keeps theirs; the aura never lowers one.
- **Pool (10):** Regeneration, Speed, Resistance, Strength, Haste, Jump Boost, Fire Resistance,
  Absorption, Slow Falling, Water Breathing. Draws are random, rolled per life. **The first draw is
  always one of Strength, Resistance or Absorption**; later draws come from the rest.
- **Ladder** (ranges in blocks; A is the first buff drawn, B the second, …):

  | r | Ranges after |
  |---|--------------|
  | 1 | A 2 |
  | 2 | A 3 |
  | 3 | A 4 |
  | 4 | A 5 |
  | 5 | A 5, B 2 |
  | 6 | A 5, B 3 |
  | 7 | A 5, B 4 |
  | 8 | A 6, B 5, C 2 |
  | 9 | A 6, B 5, C 3 |
  | 10 | A 6, B 5, C 4 |
  | 11 | A 7, B 6, C 5, D 2 |
  | 14 | A 8, B 7, C 6, D 5, E 2 |
  | … | every third point from 8: the newest buff reaches 5, every older buff gains a block, the next buff enters at 2 |
  | 29 | tenth buff enters |
  | 32 | J reaches 5, the rest gain a block |
  | 33+ | alternate: +1 block to all, then a random level II among the eight that have one (Fire Resistance and Water Breathing ignore the amplifier, so skipped), then +1 to all, … then level III. No cap. |

  Rule in words: the first buff grows 2→5 over points 1–4. A new buff enters at 2 and gains a
  block on each of the next two points (3, 4); the point after that it jumps to 5, each older buff
  gains 1, and the next buff enters at 2 on the same point. The ranges stay staggered by one block,
  oldest widest.
- **Toggles.** Each owned buff can be switched off by the player. A switched-off buff stays owned,
  keeps its range, is drawn in grey and struck through, and is skipped by the level rounds until
  switched back on. Toggles are saved with the perk state and survive death; draws do not.

**Details card**
- Description: "Friends and pets near you get buffs."
- Chips: one green chip per owned buff with its level and range in small text (`Resistance ·
  6 blocks`, `Haste II · 5 blocks`), switched-off ones greyed and struck. No separate Range chip.
  The buff that is growing carries the gold preview on its range (`2 blocks → 3`); on a point
  that does several things the preview reads `→ +1 to all, a new buff`; in the level phase the
  last chip is the dashed `→ a level II`.
- Clicking a buff chip toggles it. Hint line under the chips: "Click a buff to turn it off or on."

**Build notes**
- Perk state needs per-life data beyond the rank: the ordered list of drawn buffs with level and
  range, and the toggle set (toggles persist across death; draws do not). A new attachment or an
  extension of `PerkState`; a client→server packet for the toggle (bump `PROTOCOL_VERSION`).
  Ranges are a pure function of (rank, draw index), so only the draw order needs storing.
- Server tick every 20 ticks: for each Lover with the perk, per owned buff, apply
  `MobEffectInstance(effect, 120 ticks, level−1, ambient=true, showParticles=false)` to targets
  within that buff's range unless they already have a higher amplifier. Reuse the friend test from
  Share Hearts / Good Gifter.
- Card: `PerksScreen`'s chip row needs a chip kind that lists effects with a sub-label and is
  clickable. Shared with Knight's Debuff Aura.

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

---

## Crafter

### Re-enchant (tier 3) — CONFIRMED 2026-10-29

The table wiring exists behind `CrafterPerks.REENCHANT_ON_HOLD` (`EnchantmentMenuCrafterMixin`:
enchanted items count as enchantable, clashing rolls dropped, same enchantment only as an
upgrade). On hold because an all-filtered row still shows and charges, and rank does nothing.
This design finishes it.

**How it works**
- An enchanted item or book can go on the enchanting table and keeps every enchantment it has.
  Rows roll as usual minus anything that clashes; the same enchantment appears only as a higher
  level. A row with nothing left to add is greyed "Nothing new" and cannot be picked or paid for.
- **Re-enchants per item:** `1 + floor(r / 5)` (1 at r1–4, 2 at r5–9, 3 at r10–14, …). The count
  used is stamped on the item (a data component) and shown in its tooltip: "Re-enchanted 1 of 2".
  A re-enchant row is disabled when the item is out.
- **Extra cost:** re-enchant rows cost more levels than a fresh enchant (lapis unchanged). Starts
  at +100% and is multiplied by 0.9 on every point that does **not** add a re-enchant, so each
  point changes exactly one chip: +100, 90, 81, 73, **73** (r5), 66, 59, 53, 48, **48** (r10), 43,
  … never reaching +0%. The screen shows the normal price struck through with the real one beside
  it (the Enchant Cost rendering, in the other direction).
- Enchant Max / Enchant Bonus apply as to any enchant.

**Details card**
- Description: "Enchanted items can go back on the table and keep what they have." (unchanged)
- Chips: `Re-enchants per item 1 → 2` (PLAIN; on a non-step point the preview reads `→ 2 at 5
  points`) and `Extra cost +73% → +66%` (PERCENT).

**Build notes**
- Flip `REENCHANT_ON_HOLD`; `allowsReenchant(rank)` also checks the item's stamped count against
  `1 + rank / 5`. In `EnchantmentMenuCrafterMixin`: when the filtered list is empty, zero that
  row's cost/clue so vanilla disables it, and sync a "nothing new" flag for the label. Apply the
  extra cost in `CrafterEnchantCost`'s seam (it already carries the pre-discount price per row to
  the client). On a successful re-enchant, bump the stamp.

---

## Knight

### Debuff Aura (tier 2) — CONFIRMED 2026-10-29

The Lover's Aura turned on enemies, with a range **per debuff**.

**How it works**
- Hostile mobs and PlayerMobs within a debuff's range get that debuff, reapplied every second
  with a 6 s tail (so "for a while" after they leave or the Knight backs off). Neutral mobs only
  while angry at the Knight. Never lowers a stronger effect the mob has. Poison and Wither stop at
  half a heart (as vanilla Poison) so kills stay the player's. Bosses (Wither, Ender Dragon,
  Warden) are immune.
- **Pool (5):** Weakness, Slowness, Poison, Wither, Glowing. Draws are random, per life.
- **Per point (the cycle):**

  | r | Point | Ranges after |
  |---|-------|--------------|
  | 1 | first debuff at 1 block | A 1 |
  | 2–4 | +1 block to it | A 4 |
  | 5 | second debuff at 1 block | A 4, B 1 |
  | 6–8 | +1 to the new one until it matches | A 4, B 4 |
  | 9 | +1 block to all | 5, 5 |
  | 10 | third debuff at 1 | 5, 5, 1 |
  | 11–14 | +1 to C until it matches | all 5 |
  | 15 | +1 to all | all 6 |
  | 16 / 17–21 / 22 | fourth debuff; catch up; +1 to all | all 7 |
  | 23 / 24–29 | fifth debuff; catch up | all 7 |
  | 30+ | alternate: +1 to all, then a random level II among those with one (not Glowing), then +1 to all, then level II … then level III. No cap. |

  General rule: a new debuff enters at 1; each point after adds 1 to it until it equals the
  others; then one point adds 1 to all; then the next debuff. Once all five are owned, +1 to all
  alternates with a level round.
- **Toggles** as Aura: a switched-off debuff stays owned, keeps its range, is greyed and struck,
  is skipped by level rounds; toggles survive death, draws do not.

**Details card**
- Description: "Hostile mobs near you are weakened for a while." (unchanged)
- Chips: one per owned debuff, red, with its level and range in small text: `Weakness · 4 blocks`,
  `Poison II · 5 blocks`. No separate Range chip. The debuff that is catching up carries the gold
  preview on its range (`2 blocks → 3`); otherwise the last chip is the dashed preview:
  `→ a new debuff`, `→ +1 block to all`, `→ a level II`. Hint: "Click a debuff to turn it off or on."

**Build notes**
- Same machinery as Aura (per-life attachment: ordered draws with level and range, toggle set;
  toggle packet; 20-tick server tick). Target filter: `Monster` or PlayerMob, or `NeutralMob`
  angry at the Knight; skip `MobCategory` bosses / `Entity.getType().is(...)` boss tag. Apply
  `MobEffectInstance(effect, 140, level−1, ambient=true, showParticles=true)` unless a higher
  amplifier is present. Poison/Wither floor: cancel the effect's damage tick when health ≤ 1.0 (a
  `LivingDamageEvent.Pre` check on the magic/wither source with no attacker).

