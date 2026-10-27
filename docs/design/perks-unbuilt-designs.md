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

