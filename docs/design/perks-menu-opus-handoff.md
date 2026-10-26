# Opus takeover prompt: perks constellation menu design

Use this if the Fable session "Perks: constellation menu design" runs out of credits.
Start a fresh session with model Opus 5.5, medium effort, in that session's worktree
(`/Users/brennanhatton/Projects/DungeonTrainCraft/.claude/worktrees/dimension-band-visibility-layer-17b94e`,
branch `claude/perks-menu-design-1ef2aa`, PR #2298), sharing the folder (no new worktree).

## Background

Dungeon Train (NeoForge 1.21.1 mod) is getting a perks tree. Brennan designed it as a night-sky
constellation: ten classes (Archivist, Lover, Warlock, Knight, Tank, Crafter, Speedster, Builder,
Hoarder, Trader) around a centre node "The Engine" (not a perk); each perk is a star sized by tier
(1/2/3, unlock cost 1/5/10), linked by faint requirement lines plus dashed cross-links ("chords");
class titles sit on the line from the class's first perk to the centre. The reference web page is
https://brennan.games/dungeontrain/perks/ (Discord login), source in
`~/Projects/dp-relay/public/dungeontrain/perks/` (perks.js, perks.css, index.html): a "sky" look and
a Minecraft-textured "mc" look, a test mode faking states, and a 10-slot ring of arc segments around
each star showing active points. Perk states: locked; unlocked (bought with unlock points earned
from advancements, persists across deaths); active with N points shown as that ring (active points
come from eating Cosmic Debris items, per life, reset on death; eating one opens this menu).

Parallel sessions: "perks-tier1" builds the foundation (PerkState synced to the client,
`Perks.rank(Player, id)`, `PerkRegistry`, debug command); "perks-earning" builds unlock/active point
crediting and the Cosmic Debris item, with an `openPerksMenu(player)` seam and a packet to open the
menu; nine class sessions implement perk effects. Follow CLAUDE.md gates for committing docs
(Gate 1 plan mode, then PR). No long dashes in player-visible text.

## Prompt

A previous session (Fable 5.1, out of credits) wrote `docs/design/perks-menu.md` on this branch and
opened PR #2298 (docs only). Take over from there:

1. Read `git log origin/main..HEAD`, the PR, and `docs/design/perks-menu.md` in full, then the web
   page source in `~/Projects/dp-relay/public/dungeontrain/perks/` so the game menu matches it.
2. Section 9 of the doc lists open questions for Brennan. Present them to him in plan mode as a
   short numbered list with your recommended answer for each, and wait for his answers.
3. Fold his answers into the doc (replace the open questions with decisions), keep the
   implementation notes current with whatever the foundation and earning branches actually expose
   (check `origin/main` and the open PRs for package `games.brennan.dungeontrain.perks`), commit and
   push to the same branch.
4. Gate 3 on PR #2298 per CLAUDE.md: changelog entry is not needed for a design doc (say so), CI
   green, Brennan's approve, squash merge, delete the branch, clean up the worktree.

Done looks like: the design doc merged to main with every open question answered, ready for the
engineer who builds the screen.
