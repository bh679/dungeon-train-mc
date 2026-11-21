# Perks review: status, key stats, descriptions (2026-10-27)

Working doc for going through every built perk with Brennan. Sources:
- `origin/dev/perks` at 0.1547.0, which includes the menu (#2348);
- the waiting branches (foundation-tier1, builder-tier2, tank, speedster-tier2-3, hoarder-tier3);
- the handoff `docs/design/perks-status.md` on `claude/dungeon-train-perks-tree-df4ac7`.

r = points in the perk (its rank). r = 0 is inert for every perk that has code.
Hearts = HP / 2.

**Status key**
- **DP**: the effect is on dev/perks.
- **BR**: the effect is on a waiting branch, not merged.
- **REG**: the perk is registered but has no effect anywhere.
- **NONE**: the perk is only in the tree.

Proposed descriptions say *what* the perk does. The chips carry the numbers, so the
descriptions don't repeat them.

---

## 0. Things to decide before the per-perk pass

1. **DECIDED (Brennan, 2026-10-27): code-driven stats.** Each perk's effect code registers its own
   stat functions. Background follows. **The stats model is linear only.** `stats.json` holds `per_point × points`, but most built
   perks are curves: Kill Buff halves each step, Live Eye time steps down, and others have
   floors or approach a limit. A linear chip would show wrong numbers for most of them.
   **Proposal:** each perk's effect code registers its own stat functions (for example
   `PerkStats.register("kill-buff", "duration", Unit.SECONDS, r -> KnightPerks.killBuffTicks(r) / 20.0)`).
   The chip then shows exactly what the effect does and can never drift from it. `stats.json`
   either goes away or keeps only the order and labels.
2. **Units.** Only `""` and `seconds` exist today. The built perks need these:

   | Unit | Example |
   |---|---|
   | `seconds` | `3s` |
   | `minutes` | `5 min` |
   | `percent` | `25%` |
   | `hearts` | `+0.5 ♥` |
   | `multiplier` | `×1.2` |
   | `blocks` | `+0.1 blocks` |
   | `levels` | `−1 level` |
   | a roman effect level | `Strength I` |
3. **Descriptions today are the editor's design notes.** They come in through lang
   `perk.dungeontrain.<id>.desc` from `tree.json`. They have typos ("teir", "BookQill",
   "vilalgeer"), placeholders ("X", "X(10-20?)"), and numbers that disagree with the code
   (Health, Damage, Pet Health, Vampirism, See Trade Values). Two questions:
   - Where does player text live? Option A: a separate player-text field that
     `tree-from-editor.py` doesn't overwrite. Option B: the editor's `desc` becomes the player
     text.
   - The rewrite is a lang change, so all 20 locales are needed before the PR.
4. **Unbuilt perks can be bought.** 21 perks have no code at all, and Extra Journal plus
   Re-enchant do nothing at any rank. All of them still take Advancement Points. Options: hide
   them, draw them as "Coming soon" and refuse the unlock, or leave them.
5. **The no-caps rule versus what was built.** These perks hit a hard limit:

   | Perk | Limit |
   |---|---|
   | Cooking Time | 25% at r4 |
   | Wind Resistance | immune at r10 |
   | Tank Hunger | 50% at r10 |
   | Totem | 10 min floor at r10 |
   | Own Carriage Return | maxed at r6 |
   | Drift Gift Repaid | 90% at r12 |
   | Luck | 100% at r20 |
   | Sprint Hunger | free at r19 |

   The unlock limit grows past 10 as AP is spent (`10 × (1 + AP spent / 100)`), so ranks past
   a cap are wasted purchases. Two perks are **on/off**, where only rank 1 matters: Gift Live Eye
   and Enchant Bonus.

---

## 1. Archivist

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Stackable Book and Quill (1) | BR foundation | Blank book and quills stack to 1 + r (99 max) | **Stack** 2 | Blank books and quills stack. |
| Live Eye Safe Duration (2) | DP, **needs relay #961 (open)** | Protected minutes, stepping 5, 4, 3, 2, 1.5, then smaller: r1 5, r3 12, r5 15.5, r10 22.6 | **Protected** 5 min | While you hold the Live Eye, it can't be taken from you for longer. |
| Gift Live Eye (2) | DP, on/off | Any rank lets you `/offereye <viewer>` to a named viewer | none | Hand your Live Eye to a viewer you choose. |
| Favourite Author (2) | NONE | | | |
| Extra Journal (2) | NONE (work level 4) | | | |
| Extra Cam-Shots (3) | DP, needs Exposure | Each camera gets 0.5·r extra shots on average (floor, plus a 50% chance of one more) | **Extra shots** +0.5 | Cameras sometimes take extra photos. |
| Vote Weight (3) | DP, **needs relay #961** | Your votes count 1 + r (takeover and book votes) | **Vote weight** ×2 | Your votes count for more in Live Eye takeovers and book votes. |

**Decisions (Brennan, 2026-10-27):**
- **Gift Live Eye is removed.** It leaves the tree and the registry. `/offereye <viewer>` (gifting
  to a named viewer) becomes free for everyone.
- **Extra Cam-Shots is no longer random.** Each point adds +1 shot on every second camera,
  starting with the first:

  | Points | Extra shots |
  |---|---|
  | 1 | +1 on cameras 1, 3, 5, … |
  | 2 | +1 on every camera |
  | 3 | +1 on every camera, +2 on cameras 1, 3, 5, … |

  This keeps the 0.5-per-point average. The chip shows the average, "Extra shots +0.5".
  Description: "Your cameras take extra photos. One point gives every second camera an extra
  shot."
- **Live Eye Safe Duration shows two stats, each from its 10 min base:**

  | Points | Protected | Community Vote |
  |---|---|---|
  | 0 | 10 min | 10 min |
  | 1 | 12.5 min | 12.5 min |
  | 2 | 14.5 min | 14.5 min |

  Each window gets half of the perk's total. The relay's defaults are
  `LIVE_TAKEOVER_HOLDER_VOTE_MS` 600000 and `LIVE_TAKEOVER_MIN_MS` 1200000; the client must
  either hardcode the 10 min bases or read them from the relay.
  - **Bug to fix in the mod:** relay #961 treats both extras as deadlines counted from the
    start of tenure.
    - The Protected window is `holderVoteMs + holderVoteExtraMs`.
    - The Community Vote window ends at `takeoverMinMs + takeoverExtraMs`.
    - `LiveEyeSafeDuration` currently sends half the total to each, so the Community Vote
      window never grows (at r1 it gets 12.5 + 10, not 12.5 + 12.5).
    - Fix: send the full total as `takeoverExtraMs` (`firstWindowExtraMs`) and half as
      `holderVoteExtraMs` (`secondWindowExtraMs`). The relay needs no change.
- **Unbuilt perks show as "Coming soon"** and refuse the unlock. This applies to every class.
  `gui.dungeontrain.perks.not_built` already refuses; only the look and the wording change.
- **Approved descriptions:**

  | Perk | Description |
  |---|---|
  | Stackable Book and Quill | Blank books and quills stack. |
  | Live Eye Safe Duration | While you hold the Live Eye, it can't be taken from you for longer. |
  | Vote Weight | Your votes count for more in Live Eye takeovers and book votes. |
  | Favourite Author (coming soon) | Follow authors to find more of their books and photos. |
  | Extra Journal (coming soon) | Journals take more photos before they burn. |

  Extra Cam-Shots' description is listed under its own decision above.

Notes:
- The tree name "Stackable BookQill" needs fixing.
- Gift Live Eye: either set its max points to 1, or give extra ranks something to do.

## 2. Builder

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Drift Chance (1) | BR foundation | Group and room drift chance `1 − (1−c)·0.98^r`: groups 10% → 11.8%, rooms 6.7% → 8.5% at r1 | **Drift groups** 11.8%, **Drift rooms** 8.5% | More drifting carriages and rooms join your train. |
| Own Carriage Return (2) | BR builder, **won't compile against the retuned foundation** | Moves 10%·r from the pool to your own builds, with a 10% pool floor: own 30% → 40% at r1, maxed at r6 (85%) | **Your builds** 40% | Carriages you built come back to your train more often. |
| Drift Gift Repaid (2) | BR builder | Pays 35% + 5% per rank after the first (90% max) of the trade value, in emeralds | **Repaid** 35% | Things you leave in other players' drifting carriages pay you back in emeralds. |
| Bonus Blocks, Drift Reach, Keep Carriage | NONE | | | |

**Decisions (Brennan, 2026-10-27):**
- **Drift Chance is locked for now.** It shows as "Coming soon" and refuses the unlock, even
  though its code exists on the foundation branch. If it is ever shown, the bar would show the
  group chance only, never rooms.
- **Own Carriage Return is locked for now**, the same way.
  - Dev note (redesign): let the player *pick* whether drifting carriages favour their own
    builds or other players' builds. It becomes a preference, not just "more of yours".
  - Write this into the tree's `dev` field for the perk.
- **Drift Gift Repaid starts at 35%** at 1 point, as built.
- **The Builder wording is approved as drawn:**

  | Perk | Description | Chip |
  |---|---|---|
  | Drift Gift Repaid | Items you leave in other players' drifting carriages pay you back in emeralds. | Repaid 35% → 40% |
  | Bonus Blocks (coming soon) | A chest of building blocks waits in each drifting carriage you find, once per carriage each life. | |
  | Drift Reach (coming soon) | Your builds drift into more players' trains. | |
  | Keep Carriage (coming soon) | Drifting carriages you have edited follow you into your next run. | |

  The coming-soon chip numbers are placeholders.

Notes:
- The builder branch merged the *old* foundation. Its calls to `DriftChance.multiplierFor` and
  `boosted(double, double)` no longer exist after the retune (7d589656f), so it must be
  re-merged after the foundation PR lands.
- Open call: should the base be 35% at r1, or 40%?
- Known gap: when a hopper pulls a paid gift out, the payout isn't charged back.

## 3. Crafter

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Craft Level Stats (1) | BR foundation | Crafted gear rolls the tier of the level you're in, below chest loot. The best roll reaches 50% of the level at r1 and 81% at r10; the average is 33% at r1 and 74% at r10 | **Best roll** 50%, **Average** 33% | Gear you craft gets stats from the level you're in, a little below what chests give. |
| Enchant Cost (2) | DP | Table and anvil level cost −r, never below 1 | **Cost** −1 level | Enchanting and anvil work cost fewer levels. |
| Cooking Time (2) | DP, **capped at r4** | Cook time 100 − 20r %, with a 25% floor | **Cook time** 80% | Furnaces and campfires you use cook faster. |
| Enchant Max (3) | DP | Max-level rolls and the anvil cap go +r past the vanilla max | **Past max** +1 level | Enchantments can go past their normal maximum level. |
| Enchant Bonus (3) | DP, on/off | Every enchantment on worn or held gear acts +1 level | **Bonus** +1 level | Enchantments on gear you wear or hold work one level higher. |
| Re-enchant (3) | Parked, inert | | | Hide, or mark "Coming soon". |

**Decisions (Brennan, 2026-10-27):**
- **Cooking Time becomes diminishing.** The chip is in %, because cook times differ by item and
  by cooker.
  - **Approved curve:** `time = 80% × 0.88^(r−1)`. That gives 80%, 70%, 62%, 55%, 48% at r5
    and 25% at r10. It never reaches 0.
  - Description: "All furnace types and campfires you use cook faster."
- **Enchant Bonus and Enchant Max share one structure:**
  - **Level:** `+floor((r−1)/10) + 1` (+1 at r1–10, +2 at r11–20, and so on).
  - **Which enchantments it covers:** each point inside a band of 10 adds one enchantment group.
    At r1 the current level covers group 1. At r10 it covers all ten groups. At r11 group 1
    moves up to +2 while the rest stay at +1, and so on.
  - **Chips:** "Bonus +1 level" (or "Past max +1"). "Works on <groups>", with the next group
    previewed in gold.
  - **Descriptions:**
    - Enchant Max: "Enchantments can go past their normal maximum level. Each point adds more
      enchantments."
    - Enchant Bonus: "Enchantments on gear you wear or hold work higher. Each point adds more
      enchantments."
  - **Approved group order, the same for both perks:**

    | # | Group | Enchantments |
    |---|---|---|
    | 1 | protection | Protection, Fire, Blast and Projectile Protection |
    | 2 | melee damage | Sharpness, Smite, Bane of Arthropods, Density, Breach, Impaling |
    | 3 | Efficiency | Efficiency |
    | 4 | Unbreaking | Unbreaking |
    | 5 | ranged | Power, Punch, Piercing, Quick Charge |
    | 6 | luck | Fortune, Looting, Luck of the Sea, Lure |
    | 7 | movement | Feather Falling, Depth Strider, Respiration, Soul Speed, Swift Sneak, Frost Walker |
    | 8 | fire | Fire Aspect, Flame |
    | 9 | knockback | Knockback, Sweeping Edge, Wind Burst |
    | 10 | trident and thorns | Thorns, Loyalty, Riptide |

    Single-level enchantments (Silk Touch, Mending, Infinity, Channeling, Aqua Affinity,
    Multishot) are left out.
- **Craft Level Stats keeps both chips,** Best roll and Average.

Notes:
- Enchant Bonus: scale it (for example +1 per 10 ranks, like Kill Buff's Strength), or set its
  max points to 1.

## 4. Hoarder

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Bigger Backpacks (1) | BR foundation | Eating a backpack apple gives 0.25·r extra copies of its slots (floor, plus that chance of one more) | **Extra slots** +25% | Backpack apples sometimes give extra slots. |
| Chest Sense (2) | NONE | | | |
| Luck (3) | BR hoarder (pushed, needs Gate 2 re-presented) | r/20 chance that a chest or vase you open rolls with Luck | **Lucky chance** 5% | Some containers you open roll with Luck. |
| Looting (3) | BR hoarder | 1% per rank chance per kill of +1 copy of a drop | **Extra drop** 1% | Mobs you kill sometimes drop an extra item. |

**Decisions (Brennan, 2026-10-27):**
- **Luck stacks past 100%.** Each point adds 5%. Up to 20 points that is the chance of rolling
  as Luck I. Past 20, Luck I is always on and the extra % is a chance of Luck II (which
  `LuckyBonusRoller` already pays more: 5 to 10 bonus items instead of 3 to 7). The pattern
  continues upward.
  - Chips: "Luck I chance 5%"; past 100%, "Luck I always" and "Luck II chance x%".
- **Looting becomes real vanilla Looting**, not +1 copy of a drop.
  - With a chance of `10% × r` per kill, the kill counts as if your weapon had +1 Looting, on
    top of its own Looting. Past 100% it is always +1, plus a chance of +2, and so on.
  - Implement through `GetEnchantmentLevelEvent` (the same seam as Crafter's Enchant Bonus), so
    the loot tables apply it: more drops and better rare-drop odds.
  - Name stays **Looting**.
  - Description: "Your kills sometimes count as if your weapon had one more level of Looting."
  - Chips: "Looting +1 chance 10%"; past 100%, "Looting +1 always" and "+2 chance x%".
  - Note that it stacks with Enchant Bonus's luck group.
- **Approved descriptions** (no edits given):
  - Bigger Backpacks: "Backpack apples sometimes give extra slots." Chip: "Extra slots +25%".
  - Luck: "Some containers you open roll their loot with Luck."
  - Chest Sense (coming soon): "Every few minutes, sense nearby chests through walls."

Notes:
- The tree text for Bigger Backpacks ("Apples have a 25% chance to be worth 2") reads as a
  different perk.
- Looting shares a name with the vanilla enchantment. Rename it "Extra Drops", the original
  name?

## 5. Knight

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Damage (1) | BR foundation | +0.1 attack damage per rank (the tree says +0.5) | **Damage** +0.1 | You hit harder. |
| Debuff Aura (2) | NONE | | | |
| Crits (2) | DP | r% chance of a 1.5× crit. Past 100% it stacks to 2×, 2.5× and so on | **Crit chance** 1%, **Crit** ×1.5 | Your hits sometimes land as critical hits. |
| Kill Buff (3) | DP | On a kill you get Strength `floor((r−1)/10)+1`. Duration is 3 s; each rank adds half the last gain (4.5, 5.25, …, 6 s), then resets to 3 s at the next Strength level | **Strength** I, **Duration** 3s | Kills give you a short burst of Strength. |
| Vampirism (3) | DP | On a kill, a 25% + 5%·(r−1) chance of half a heart. Past 100% it is guaranteed, plus a chance of more | **Heal chance** 25% | Kills heal you. |

**Decisions (Brennan, 2026-10-27):**
- **Hearts are shown as icons, never as text.** Chips draw vanilla's HUD heart sprites
  (`hud/heart/full`, `hud/heart/half`) in place of "½ ♥" or "hearts". This applies to every
  heart stat: Vampirism, Tank Health, Healing, and Lover's Pet Health.
  - The stat unit becomes `hearts`, rendered as a count of full and half sprites, or as
    "N ×" plus the sprite when the count is large.
- **Vampirism is retuned to 5% per point.** The chance of healing half a heart on a kill is
  `5% × r`: 5% at 1 point, 10% at 2, 50% at 10, and guaranteed at 20.
  - Past 100% it stacks: half a heart guaranteed, plus a chance of a second half heart.
  - This replaces the built `25 + 5(r−1)`.
  - Chip: "Heal [half heart] chance 5% → 10%".
- **Damage (+0.1 per point) and Crits (1% per point) stay as built.**
- **Knight wording is taken as approved as drawn** (no edits given):

  | Perk | Description |
  |---|---|
  | Damage | You hit harder. |
  | Crits | Your hits sometimes land as critical hits, even without jumping. |
  | Kill Buff | Kills give you a short burst of Strength. |
  | Vampirism | Kills sometimes heal you. |
  | Debuff Aura (coming soon) | Hostile mobs near you are weakened for a while. |

Notes:
- Damage at +0.1 per rank is small. Vampirism at r1 is a 25% chance of half a heart, which is
  weak, and the tree says "half a heart per kill per point".

## 6. Lover

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| Friendliness (1) | BR foundation | Extra tame rolls: wolf 33% → 56% at r1. PlayerMob feeling closes 6% of the gap to max at r1 (46% at r10) | **Tame chance** 56%, **Warmth** +6% | PlayerMobs like you more, and animals tame more easily. |
| Pet Health (2) | DP, **bug** | Pets get +0.5 heart per rank (the tree says 1 heart) | **Pet health** +0.5 ♥ | Your pets have more health. |
| Good Gifter (2) | DP | Offering strength `1 + 0.25r`, so a wolf tames at 42% with the right food at r1. More kinds of food are accepted at higher ranks. PlayerMob feeling +0.1r | **Tame chance** 42%, **Gift warmth** +0.1 | Animals accept more kinds of food and tame in fewer tries. PlayerMobs like your gifts more. |
| Heal Friends, Shared Aura, Spare Enemies, Save Ally | NONE | | | |

**Decisions (Brennan, 2026-10-27):**
- **Friendliness and Good Gifter split by action.**
  - **Friendliness is passive:** extra taming rolls, and PlayerMob warmth.
    - Chips: "Tame chance 56% → 70%" (wolf), "Warmth +6% → +12%".
  - **Good Gifter is gifts only:** which foods animals accept, the strength of each offering,
    and PlayerMob gift credit. The code already only fires on a gift.
    - Its chips change to "Gift strength ×1.25 → ×1.5" and "Gift warmth +0.1 → +0.2", so only
      Friendliness shows a tame chance.
- **Pet Health stays at half a heart per point.** Chip: "Pet health +[half heart] → +[full
  heart]", drawn with the heart icons.
- **The Lover wording is approved as drawn:**

  | Perk | Description |
  |---|---|
  | Friendliness | PlayerMobs like you more, and animals tame more easily. |
  | Pet Health | Your pets have more health. |
  | Good Gifter | Animals accept more kinds of food and tame in fewer tries. PlayerMobs like your gifts more. |
  | Heal Friends (coming soon) | Feed a player or a pet to heal them. |
  | Shared Aura (coming soon) | Players near you share some of your buffs. |
  | Spare Enemies (coming soon) | Hostile mobs you leave at low health flee, and you still get their XP. |
  | Save Ally (coming soon) | Once in a while, a friend who dies near you is saved with one heart. |

Notes:
- Pet Health bug: a rank change through the menu doesn't reach pets that are already loaded.
  The only re-apply path is the unused `PerkDevShim.set`.
- Friendliness and Good Gifter both raise taming and PlayerMob feeling. Check how they stack,
  and whether the two chips read clearly side by side.
- Good Gifter's food unlock ranks (5, 10, 20, 30) are worth keeping off the bar, per the
  "never reveal how many needed" rule.

## 7. Speedster (whole class is BR speedster; needs a moving-Sable test)

| Perk (T) | What the code does | Proposed chips | Proposed description |
|---|---|---|---|
| Speed (1, BR foundation) | +1% movement speed per rank | **Speed** +1% | You move faster. |
| Sprint Hunger (2) | Sprinting costs 10% less hunger, +5% per rank after that; free at r19 | **Sprint hunger** −10% | Sprinting costs less hunger. |
| Haste (2) | Mining ×(1 + 0.2r) | **Mining** ×1.2 | You mine faster. |
| Sprint Speed (3) | +5% per rank while sprinting | **Sprint speed** +5% | Sprinting is faster. |
| Sprint Jump (3) | +0.1 block per rank on sprint jumps | **Jump distance** +0.1 blocks | Sprint jumps carry you further. |
| Wind Resistance (3) | Wind push −10% per rank; immune at r10 | **Wind push** −10% | The wind pushes you around less. |

Note: the branch carries its own shim, which needs dropping on merge.

**Decisions (Brennan, 2026-10-27):**
- **Wind Resistance becomes diminishing.** Push is `×0.9^r`: −10%, −19%, −27%, …, −65% at r10.
  It never reaches full immunity.
- **Sprint Hunger becomes diminishing.** Sprint cost is `×0.9^r`, with the same numbers. It is
  never free.
  - This replaces the built "10% + 5% per point".
  - Chip at 1 point: "Sprint hunger −10% → −19%".
- **Speed stays at +1% per point.**
- **The Speedster wording is approved as drawn:**

  | Perk | Description |
  |---|---|
  | Speed | You move faster. |
  | Sprint Hunger | Sprinting costs less hunger. |
  | Haste | You mine faster. |
  | Sprint Speed | Sprinting is faster. |
  | Sprint Jump | Sprint jumps carry you further. |
  | Wind Resistance | The wind pushes you around less. |

## 8. Tank (whole class is BR tank; own shim; ids not registered)

| Perk (T) | What the code does | Proposed chips | Proposed description |
|---|---|---|---|
| Health (1, BR foundation) | +0.5 heart per rank (the tree says +1 heart) | **Health** +0.5 ♥ | You have more health. |
| Armour (2) | +2 armour per rank, no cap (r10 = +20, a full diamond set) | **Armour** +2 | You have more armour. |
| Hunger (2) | 5% per rank less hunger drain, 50% max | **Hunger drain** −5% | You get hungry more slowly. |
| Totem (3) | Free totem save; cooldown 20 − r min, 10 min floor | **Cooldown** 19 min | Once in a while, a hit that would kill you saves you like a Totem of Undying. |
| Healing (3) | Half a heart every 60 − 5r s, 5 s floor | **Every** 55s | You slowly heal over time. |

Notes:
- The tree text "ignores the diamond-over-leather penalty" doesn't match anything in the
  branch, so drop it.
- Healing at r1 is weak. Totem: keep 20/10 min, or use the suggested 30/15?

**Decisions (Brennan, 2026-10-27):**
- **Armour and Health chips use vanilla HUD icons:** the armour icon (one icon = 2 armour) and
  the hearts.
- **Hunger becomes diminishing.** Drain is `×0.95^r`: −5%, −10%, −14%, …, −40% at r10. It
  never reaches zero.
- **Totem becomes diminishing from 30 min.** Cooldown is `30 × 0.93^r` min: 27.9, 25.9, 24.1,
  …, 14.5 at r10. It keeps shrinking.
- **Healing becomes diminishing from 120 s.** The interval is `120 × 0.85^(r−1)` s per half
  heart: 120, 102, 87, 74, 63 at r5, 28 at r10.
  - Rank 1 is now deliberately slower than the built 55 s.
  - Chip: "Heals [half heart] every 120s → 102s".
- **Tank wording:**

  | Perk | Description |
  |---|---|
  | Health | You have more max health. |
  | Armour | You have more armour. |
  | Hunger | You get hungry more slowly. |
  | Totem | Once in a while, a hit that would kill you saves you like a Totem of Undying. |
  | Healing | You heal over time. |

## 9. Trader

| Perk (T) | Status | What the code does | Proposed chips | Proposed description |
|---|---|---|---|---|
| See Trade Values (1) | BR foundation | Tooltip shows the value of items worth up to `r + 0.4r(r−1)` emeralds (1, 2.8, 5.4, 13, 46). Needs Trade Everything | **Shows up to** 1 emerald | Hover an item to see what it trades for. More points reveal pricier items. |
| Persuade Enemy (2) | NONE | | | |
| Reduced Reduction (2) | DP, needs TE | Each sale knocks a villager's price down to 85% of the usual drop at r1, 50% at r5, approaching 25% | **Price drop** −15% | Villagers' prices drop more slowly as you sell to them. |
| Restock Villager (3) | DP | r restocks per villager per life (the ↻ button) | **Restocks** 1 per villager | Restock a sold-out villager's trades. |
| Reroll Trades (3) | NONE | | | |

Note: the old tree text "rank 2 also shows the best nearby buyer" is gone from the code.

**Decisions (Brennan, 2026-10-27):**
- **See Trade Values' chip rounds down to whole emeralds** (1, 2, 5, 13) and uses the emerald
  icon. Chip: "Shows items up to 1 [emerald] → 2 [emerald]".
- **Trader wording:**

  | Perk | Description | Chip |
  |---|---|---|
  | See Trade Values | Hover an item to see what it trades for. More points reveal pricier items. | |
  | Reduced Reduction | Items you sell to villagers lose their value more slowly. | Value loss −15% → −27% |
  | Restock Villager | Restock a villager's sold-out trades. | Restocks per villager 1 → 2 |
  | Persuade Enemy (coming soon) | Neutral mobs leave you alone unless you attack them, and gifts can calm angry mobs. | |
  | Reroll Trades (coming soon) | Reroll a villager's trades. | |

---

## Chip icon rule (all classes)

Wherever a stat is a vanilla quantity with a HUD or item sprite, the chip draws the sprite in
place of the word:

| Quantity | Sprite |
|---|---|
| Hearts | `hud/heart/full`, `hud/heart/half` |
| Armour | `hud/armor_full`, `hud/armor_half` (one icon = 2 armour) |
| Emeralds | the emerald item |

## 10. Warlock

Nothing is built. `stats.json` has the only stats in the whole file, and they are on Throwables
(damage +1 and effect +2 s per point), which has no effect code. That entry looks like a
placeholder to remove or keep until Throwables exists.

---

## Other findings

- **`PerkDevShim` on dev/perks is dead code.** Nothing calls it, so it can go.
- The tank, speedster and hoarder branches each bring their own `Perks.java` shim against an
  older foundation. Expect conflicts, which should resolve to dev/perks' structure.
- **Merge order:**
  1. foundation (it holds all nine tier-1 effects);
  2. builder, re-merged on top of the foundation;
  3. tank, speedster and hoarder, in any order.
- **Death-screen menu branch** (`dev/perks-death-screen-menu`, 1 commit, not in the handoff):
  - opens the menu first on death, unlocking only;
  - adds a death page with stat tiles;
  - carries unlocks over on "Board anew".
- dev/perks is ~87 commits behind main (from the handoff).
