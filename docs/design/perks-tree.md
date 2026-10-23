# Perks Tree: perk ideation and economy

Status: ideation draft, 2026-10-22. Companion doc: `perks-tree-ui.md` (UI spec, written in a sibling session, branch `dev/perks-tree-ui-design`).

Nothing here is implemented. Hooks named below are the existing systems a perk would attach to.

---

## 1. Open design questions (decide before Gate 1)

| Question | Options | Recommendation |
|---|---|---|
| **Scope of a perk** | (a) per run, lost on death; (b) permanent across runs (meta progression) | **(a) per run.** DT is a one-life run game with a leaderboard; permanent perks would make early boards unbeatable and need a cheat-tier partition. Keep a separate "legacy" layer as a later idea. |
| **Currency** | (a) perk points granted per difficulty tier reached; (b) vanilla XP levels; (c) emeralds via Trade Everything | **(a) tier points, with (b) as a top-up.** Tier points tie progression to distance (`DifficultyProgression.tierForTravelled`), which players already understand. Spending XP levels gives a second, choice-based source without touching the trade economy. |
| **Respec** | none / paid / free once per tier | **Paid**, costs one point. Keeps commitment meaningful but lets players recover from a bad early pick. |
| **Multiplayer** | per player / per train | **Per player.** Stored on a player data attachment (`ModDataAttachments`), cleared on the life reset path alongside the other `*_this_life` attachments. |
| **Where it opens** | keybind / menu / block on the train | UI doc recommends keybind P plus a pause-menu button plus a one-time chat hint. A **perk block in the starter carriage** works as a second door: discoverable, and it can be lost (carriage disconnect), which is a real DT stake. |

Points budget to tune against: with ~7 tiers in a long run, 1 point per tier plus XP top-ups gives roughly 10 to 14 points per run, so a full class (5 nodes, some multi-rank) costs about 9. Nobody completes two classes in one life; one full class plus a dabble is the expected shape.

**Non-combat point sources.** So a pacifist run is not point-starved, points also come from:
- **Spare Enemies tier:** reaching a new tier with zero kills this life grants a bonus point (tracked like the other `*_this_life` attachments).
- **First photo** of each new band style (Exposure camera) grants a point, once per band.
- **First book read** from a shared library per band grants a point.
A kill-heavy run and a kill-free run should land on roughly the same total.

---

## 2. Tree shape: ten classes

The tree is framed as **classes**, nine active, Explorer and Ranger parked. Each class is one lane of five nodes from a shared root: two stat ranks, two mechanics perks, one capstone. Players will mostly go deep in one class and dabble in a second, so the class name is the identity they carry through the run ("I'm playing Lover this life"). Only one class, Knight, is about hitting things. The Engine at the centre is the starting point, not a perk: it does nothing.

```
                      [THE ENGINE: start, no effect]
   ______________________/ | | | | | | \______________________
  /       /       /       /  |   |  \       \        \        \
Archivist Lover Explorer Speedster Tank Hoarder Ranger Knight Trader Crafter Builder
```

Order around the sky, clockwise from the top: Archivist, Lover, Speedster, Knight, Tank, Crafter, Builder, Hoarder, Trader (Explorer and Ranger parked). Knight sits between the two movement and toughness classes, well away from Archivist.

Cross-links (dashed). Two-way unless marked:
- Lover 4 <-> Trader 3 (Spare Enemies <-> Neutral Truce)
- Trader 4 <-> Hoarder 2 (Bartering <-> Magnet)
- Hoarder 3 <-> Crafter 2 (Luck <-> Enchant Cost)
- Crafter 4 <-> Tank 2 (Enchant Max <-> Armour)
- Tank 3 -> Speedster 2 (Totem -> Sprint Speed), **one-way**
- Speedster 6 <-> Knight 3 (Haste <-> Execute)

UI impact: ten lanes instead of seven. The lane layout in `perks-tree-ui.md` still works but pans vertically at GUI scale 2 as well as 4; the UI doc needs a pass once the class list is final.

### Name ideas for the three you flagged

| Working name | Options | Lean |
|---|---|---|
| Tank | Tank, Tank, Bruiser, Ironside, Stalwart, Wall | **Tank**. It is funny, and DT's tone can carry it. Tank is the safe fallback. |
| Fighter | Knight (chosen) | **Knight.** |
| Trader | Trader, Merchant, Haggler, Diplomat, Peddler, Broker | **Haggler**. Says what the perks do (prices and truces) and has personality. Diplomat if the class leans more on truces than prices. |

Hoarder is spelt with an a.

---

## 3. Classes and perks

Names are deliberately literal during ideation (the stat or thing the perk touches). Flavour names come later, once the set is settled.

Costs are in points. "Rank" perks can be bought up to the listed max. Hook column names the existing code a perk would attach to.

### 3.1 Archivist (books, photos, memory)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Free Books | A free signed book each tier | 1 | book item grant on tier |
| 2 | Free Camera | A free camera with film at the start of the run; film regenerates one shot per tier | 1 x 2 | Exposure bridge |
| 3 | Track Author | Find books and photos by an author more. Add more authors with every tier | 1 x 3 | shared book / photo pick weighting |
| 4 | Free Live Eye | A free Live Eye each tier | 1 | Live Eye item grant |
| 5 | Live Eye Duration | An untaken Live Eye stays open 10 min longer | 1 x 2 | Live Eye close-call timer |
| 6 | Vote Weight | Your votes in Live Eyes count double; rank 2 triple | 1 x 2 | relay vote weight |
| 7 | Archive Carryover (tier 3) | Every book and photo you collect this life is kept in a bound album that appears in your next run's starter carriage (read-only, no items) | 3 | per-player store, starter prefab |

### 3.2 Lover (care for others)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Befriend Faster | Making friends is easier: taming takes half the tries, villagers warm to you twice as fast, wolves and cats approach you | 1 x 2 | tame chance, villager reputation |
| 2 | Heal Friends | Right-click a player or tame with food to feed and heal them; rank 2 also gives Regeneration I for 5 s | 1 x 2 | interact event, `FoodData` |
| 3 | Tame Animals | You can tame animals at all (untamable without this perk in a run) | 1 | tame gate, `RunTameTracker` |
| 4 | Spare Enemies | Hostiles you drop under 20% health flee and pay the XP a kill would have; rank 2 raises it to 35% | 1 x 2 | damage event, target-selector mixin |
| 5 | Save Ally (tier 3) | Once per tier, a player who dies within 12 blocks of you is left at one heart beside you; in singleplayer your tames respawn at the starter carriage next tier | 3 | death event, `RunTameTracker` |

### 3.3 Explorer

Parked for now. Its perks (Biome XP, Night Vision, No Fall Damage, Preview Next Band) are held back; Chest Sense moved to Hoarder.

### 3.4 Speedster (move)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Speed | +6% base speed per rank | 1 x 3 | `Attributes.MOVEMENT_SPEED` |
| 2 | Sprint Speed | Sprinting is 15% faster per rank | 1 x 2 | sprint speed modifier |
| 3 | Sprint Jump | Sprint-jumps carry 1 block further per rank | 1 x 2 | jump velocity while sprinting |
| 4 | Sprint Hunger | Sprinting costs 50% less hunger; rank 2 none | 1 x 2 | `FoodData` exhaustion |
| 5 | Wind Resistance | Wind push halved; rank 2 immune | 1 x 2 | `wind/WindPush`, `wind/TrainExposure` |
| 6 | Haste (tier 3) | Permanent Haste II; moving carriages give Speed II | 3 | permanent `MobEffect`, carriage deck |

### 3.5 Tank (take hits)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Health | +2 hearts per rank | 1 x 2 | `Attributes.MAX_HEALTH` |
| 2 | Armour | +2 armour per rank, ignores the diamond-over-leather penalty | 1 x 2 | `combat/ArmorReductionCurve` |
| 3 | Totem | Totem of Undying effect without the item, 10 min cooldown; rank 2 5 min | 2 x 2 | death event, vanilla totem animation |
| 4 | Hunger | Hunger drains 30% slower; food poison halved | 1 | `FoodData` |
| 5 | Death Save (tier 3) | Once per tier, lethal damage leaves half a heart and 3 s of Resistance V | 3 | death event, `MobEffectOffsets` |

### 3.6 Hoarder (carry everything)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Storage | +1 inventory row per rank. Everyone starts a run with a reduced inventory; Hoarder earns it back and more | 1 x 3 | inventory size gate, `EnderChestExpansion` |
| 2 | Magnet | Items within 4 blocks drift to you; XP orbs within 8 | 1 | item entity tick |
| 2b | Chest Sense | The next chest within 32 blocks glints through walls for 5 s when you enter a new chunk row | 1 x 2 | loot container scan on chunk cross |
| 3 | Luck | 1 in 20 containers rolls with Luck per rank (2 in 20, 3 in 20, ...) | 1 x 3 | loot roller luck |
| 4 | Extra Drops | Mobs drop 1 extra item 25% of the time | 1 | loot roller |
| 5 | Death Chest (tier 3) | Items dropped on death stay in a locked chest at the death spot for your next life | 3 | death drops, per-player store |

### 3.7 Ranger

Parked for now. Held back: Arrow Accuracy, Pet Health, Forage Drops, Animals Follow, Wolf Pack.

### 3.8 Knight (hit things)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Damage | +1 attack damage per rank | 1 x 2 | `Attributes.ATTACK_DAMAGE` |
| 2 | Debuff Aura | Hostiles within 8 blocks get Weakness I; rank 2 adds Slowness I | 1 x 2 | server tick, `MobEffectOffsets` |
| 3 | Execute | Hits on mobs under 25% health deal double damage | 1 | damage event |
| 4 | Kill Buff | Kills grant 2 s of Strength I and heal half a heart | 1 | kill event |
| 5 | Aura Range (tier 3) | Debuff Aura reaches 16 blocks and affects bosses | 3 | aura |

### 3.9 Trader (deals and truces)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Trade Values | Hovering any item shows what it trades for; rank 2 also shows the best nearby buyer | 1 x 2 | `TradeValueTable` tooltip |
| 2 | Trade Restock | Villagers never lock a trade; restock twice as fast | 1 | villager trade data |
| 3 | Neutral Truce | Neutral mobs (piglins, endermen, zombie piglins) ignore you unless attacked | 2 | target-selector mixin |
| 4 | Bartering | Piglin bartering yields rarer results; Echoes offer a trade instead of attacking | 1 x 2 | barter loot table, `echo/` |
| 5 | Recruit Villager (tier 3) | One villager per carriage can be recruited; recruits restock and stay aboard | 3 | villager AI, carriage save (Sable test) |

### 3.10 Crafter (make and mend)

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Craft Level Stats | Items you craft get the stats of the level you are in, as if looted there | 1 x 2 | `difficulty/BakedItemStats`, craft event |
| 2 | Enchant Cost | Enchanting costs 1 level less per rank, lapis halved | 1 x 2 | enchant cost hook |
| 3 | Re-enchant | Already-enchanted items can go back on the table and keep what they have | 2 | enchant table gate |
| 4 | Enchant Max | Enchantments can roll one level past their normal max; rank 2 two | 2 x 2 | enchant level cap |
| 5 | Enchant Bonus (tier 3) | Worn armour and held weapons act one enchantment level higher on everything they already have | 3 | enchantment lookup mixin |

### 3.11 Builder (drifting carriages)

Drifting carriages are the rooms players build by hand that travel the relay into other trains (shared carriages). Builder is about meeting more of them, sending yours further, and being kind in them.

| # | Perk | Effect | Cost x ranks | Hook |
|---|---|---|---|---|
| 1 | Drift Chance | More drifting carriages roll into your train each band | 1 x 3 | `SharedCarriageRolls` |
| 2 | Own Carriage Return | Carriages you built come back around to you more often | 1 x 2 | `SharedCarriageRolls#bucket` own weight |
| 3 | Build Materials | A chest of building blocks in every drifting carriage you enter, refilled each tier | 1 | shared carriage enter hook |
| 4 | Drift Gift | Things you leave in a drifting carriage count double toward a friendlier echo | 1 | `event/DriftGenerosity` ledger |
| 5 | Drift Reach | Your builds travel to more players' trains | 1 x 2 | relay pool weight |
| 6 | Keep Carriage (tier 3) | One drifting carriage you have edited is pinned and follows you into your next run | 3 | per-player store, `TrainAssembler.tryLeaseShared` |

---

## 4. Seed-idea mapping

| Brennan's idea | Where it landed |
|---|---|
| Permanent enemy debuff in radius by X level | Knight 2 Debuff Aura, capstone widens it |
| Totem effect with X cooldown | Tank 3 Totem, one-way link into Speedster |
| Wind resistance | Speedster 2 Wind Resistance; Ranger 1 for arrows |
| Speed | Speedster 1, 5 |
| Health | Tank 1 |
| Storage | Hoarder 1 (everyone starts reduced) |
| Armour | Tank 2 |
| Damage | Knight 1 |
| Friendliness | Lover (care), Trader (truces, deals), Ranger (animals) |
| Hunger | Tank 4, Speedster 4, Ranger 3 |
| Mining speed | Speedster 3 Haste |
| Permanent enchantment effects | Crafter 2 to 5 |

---

## 5. Risks and constraints

- **Leaderboard fairness.** Perks are part of a normal run, so they must not flip `run_cheated` or the Free Play partition. Points come only from in-run progress, never from commands.
- **Mob effect caps.** Resistance and Regeneration V+ are soft-capped globally (`MobEffectOffsets`). Perk effects should stay at I or II so they stack under the cap rather than fight it.
- **Sable.** Any perk that moves entities (Recruit Villager, Animals Follow, Wolf Pack, Pet Health follow) needs the standing-on-a-moving-carriage test. Attribute and effect perks are safe.
- **Enchantment perks must be virtual.** Bump the effective enchantment level in the lookup, never write NBT, so items keep trade values and shared-carriage snapshots unchanged.
- **Death reset.** Perk state belongs with the other `*_this_life` attachments so a new life starts clean and the stat book can report "perks bought this life". The two capstones that reach into the next life (Archive Carryover, Death Chest) are the exception and need their own per-player store; check them against the per-run scope decision in section 1 before building.
- **Baseline nerfs.** Two perks only work if the run starts worse than vanilla: Storage (reduced starting inventory for everyone) and Tame Animals (no taming without it). Both change the game for players who never open the tree, so call them out in the Gate 1 plan and the changelog.
- **Changelog.** When this ships, the note stays at outcome level ("Perks tree") and never lists hidden capstones.

---

## 6. Suggested first slice

Ship the tree with the Engine plus three classes: Tank and Speedster for the stat-rank nodes, Totem and Wind Resistance, and Lover for Heal Friends and Befriend Faster so the compassionate path is there from day one. That exercises the full loop (points, purchase packet, attachment, reset, UI) with hooks that already exist, and leaves the remaining mechanics perks and the mercy-tier point source for a second PR.
