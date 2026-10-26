# Perks: status and handoff (2026-10-27)

Read this first if you are picking up perks work. It records what has landed, what is
waiting, and the rules the work runs under. Design docs live beside it:
`perks-tree.md`, `perks-table.md`, `perks-constellation.html` (the editor),
`perks-menu-opus-handoff.md`.

## Rules (from Brennan)

- **Nothing perk-related merges into `main`.** All DT perk work merges into the
  integration branch **`dev/perks`** (cut from main at 7c87d2171). Read "dev/perks"
  wherever CLAUDE.md or the gate playbooks say main: base PRs on it, keep branches
  current with `origin/dev/perks`, squash merge into it.
- **Every squash merge into `dev/perks` bumps `mod_version` MINOR** on dev/perks
  afterwards, and each PR keeps its own changelog entry, so main gains one MINOR per
  merge when dev/perks finally lands. CI now does this automatically
  (commit b3a0dba9e, "auto-bump MINOR on merges into dev/perks"). The matching main-side
  PR #2333 is still open and conflicting.
- The **relay (dp-relay) goes straight to its main** as usual.
- All four gates still apply to every perk PR. Gate 2 and Gate 3 need Brennan's approve.
- Balancing direction Brennan has given repeatedly: **no hard caps**, use diminishing
  curves instead (each rank adds a bit less than the last, total keeps growing).

## Model

Perks are stars in a constellation of ten classes around "The Engine". Each has a tier
(1/2/3). Players earn **Advancement Points** from advancements (persist across deaths) to
unlock perks, and **cosmic apples** (replaced the earlier "Cosmic Debris"; three tiers)
give active points per life; a perk's **rank** is its active points and scales its effect.
Work level per perk in the editor: 0 ready, 1 balancing, 2 UI, 3 UX, 4 code. Only
balancing perks were implemented in this round.

Website: https://brennan.games/dungeontrain/perks/ (Value Adders, read only; owner edit
mode since relay #957). Editor artifact: https://claude.ai/artifact/1TmwYDpu4eymEYEPLcUUga

## Landed in `dev/perks` (mod_version 0.1539.0)

| PR | What |
|---|---|
| #2303 | Knight: Crits, Kill Buff (3 s, each rank adds half the last), Vampirism |
| #2302 | Trader: Reduced Reduction, Restock Villager ("↻ N" button by "Trades") |
| #2299 | Archivist: Live Eye Safe Duration (diminishing, no cap), Gift Live Eye, Vote Weight, Extra Cam-Shots |
| #2330 | Earning: Advancement Points, rank costs, repeat unlocks; brought in the foundation core (`PerkRegistry`, `PerkState`, `Perks`, `TierOnePerks` registrations, `PerkTree*`, `PerkSpend`, `PerkMenu` seam) |
| #2316 (via earning) | Cosmic apples replace Cosmic Debris, three tiers |
| #2300 | Crafter: Enchant Cost (struck-through old cost), Cooking Time, Enchant Max, Enchant Bonus. **Re-enchant is parked**: code kept, does nothing at any rank, out of the changelog |
| #2301 | Lover: Pet Health (+0.5 heart per rank, no cap), Good Gifter (+0.25 offering strength per rank) |
| #2340 | Bundled tree from the editor: Extra Journal (Archivist tier 2, work level 4), links, positions |

Still on dev/perks: **`PerkDevShim`** (in-memory ranks behind `/dungeontrain debug perk set`).
It was kept as the single stand-in when Trader and Knight both carried one. Remove it once
the real tier-1 effects and rank source are wired (foundation branch below).

## Waiting (branch, last state, who acts)

| Work | Branch | Ahead of dev/perks | State |
|---|---|---|---|
| Foundation + tier-1 effects | `dev/perks-foundation-tier1` | 5 commits, 39 files | Retuned to Brennan's list (7d589656f, 0.1508.2: Health +0.5 heart, Damage +0.1, book stack 1+rank up to 99, uncapped curves). Build green, **not tried in game**. Session idle, needs Brennan's Gate 2 approve, then PR into dev/perks. Tier-1 effects are the 39 files not yet in dev/perks; registrations already are, so expect conflicts in `perks/` to resolve in favour of dev/perks' structure. |
| Builder: Own Carriage Return, Drift Gift Repaid | `dev/perks-builder-tier2` | 6 | Gate 2 report presented; the approve prompt failed to show, Brennan says "approve" in chat. Open calls: Drift Gift base 35% at rank 1 or 40%; +10%/rank own share with 10% pool floor. Has the foundation branch merged in, so its PR must follow the foundation PR. Known gap: hopper extraction of a paid gift is not charged back. |
| Tank: Armour, Hunger, Healing, Totem | `dev/perks-tank` | 1 | Gate 2 walkthrough posted. Open calls: Armour has no cap (fits the no-cap rule); Totem 20 minus rank minutes, 10 min floor (suggested 30/15); Healing rank 1 is weak. Carries its own shim (`Perks.java`, `PerkShimCommand.java`), drop it on merge. |
| Speedster: Haste, Sprint Hunger, Sprint Speed, Sprint Jump, Wind Resistance | `dev/perks-speedster-tier2-3` | 2 | Takeover session crashed (exit 143), resumed, and is at its Gate 2 approve prompt. Movement must be confirmed on a moving Sable carriage. |
| Hoarder: Luck, Looting | `dev/perks-hoarder-tier3` | 1 (8000b00c8) | Takeover session crashed (exit 143) after its Gate 2 report. Branch was local only; **pushed 2026-10-27**. Needs a new session to re-present Gate 2. |
| In-game perks menu | `dev/perks-menu-design`, PR **#2348** | 114 | Fable session; PR set to squash merge into dev/perks when CI passes (build check still running at time of writing). The docs PR #2298 was closed in favour of this. |
| Relay honours Archivist perks | dp-relay PR **#961** (0.281.15) | n/a | Relay reads extra Live Eye time and vote weight. CI and tests pass; waiting for Brennan's Gate 2 in its session, then merge to relay main and deploy. Until then those two Archivist perks have no effect. |
| Integration-branch versioning | PR **#2333** (base main) | n/a | Open, conflicting. Its CI change already runs on dev/perks. |

Warlock has no balancing-level perks, so no session. Explorer and Ranger are parked.

## Risks for whoever lands dev/perks on main

- **dev/perks is 87 commits behind main** and main is already at 0.1546.0 while dev/perks
  is 0.1539.0. Merge main into dev/perks (merge, not rebase) before any final PR, and
  expect the version line to be recomputed.
- Every class PR added a shim at some point; grep for `PerkDevShim`, `PerkShimCommand`,
  "SHIM" and "stand-in" before shipping.
- No perk has been tested together with the others in one client. Do one combined Gate 2
  pass on dev/perks (rank 0 must be inert everywhere; death must reset ranks and keep
  Advancement Points; Free Play must not grant or apply perks).
- Changelog entries from each merge must not name hidden mechanics or bands
  (see the changelog memories).

## Sessions (Claude Desktop, linked from the main perks session)

Opus takeovers: foundation `local_71c4382e`, Builder `local_2016f79d`, Tank `local_5f1f1e80`,
Speedster `local_791d6b12`, Hoarder `local_551b8a7c` (crashed). Earning `local_04d80260`,
menu `local_a50d674d`, relay Archivist `local_d3e26c31`. The original Fable class sessions are
parked or failed and can be archived.
