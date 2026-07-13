# Prompt for next Claude Code session (Opus 4.7, 1M context)

Copy everything below the `---` into the initial message of a fresh session
started in `/Users/brennanhatton/Projects/DungeonTrainCraft`.

---

You're continuing work on **Dungeon Train**, a Minecraft mod port of the
itch.io VR game at https://brennanhatton.itch.io/dungeontrain. The original
game is "explore endless and varying train carriages, battling enemies and
collecting loot," inspired by the Adventure Time infinite-train episode —
a train designed outside the laws of physics that goes on forever, with no
conventional world outside.

**Current project state (already in main, do not redo):**
- Repo: https://github.com/bh679/dungeon-train-mc
- Mod loader: **Forge 1.20.1 (47.4.2)**, Java 17, mojmap mappings
- Key dep wired and verified: **Valkyrien Skies 2 (2.4.11)**, plus Cloth
  Config and Kotlin for Forge (both pulled transitively via VS)
- Mixin infrastructure: `org.spongepowered.mixin` gradle plugin active,
  empty `dungeontrain.mixins.json` ready to receive mixins
- `./gradlew runClient` launches MC 1.20.1 with all mods loaded, Dungeon
  Train included in the in-game Mods list
- Entry point: `src/main/java/games/brennan/dungeontrain/DungeonTrain.java`
  (just logs "common setup" / "client setup" — no registered content yet)
- GitHub Project board: https://github.com/users/bh679/projects/16
- 4-gate workflow is MANDATORY (see `CLAUDE.md` and `.claude/gates/*.md`)

**This session's task: Gate 1 for the first real feature — the Train entity.**

Your goal is **ONLY Gate 1** (plan + approval) — do not write any
implementation code. Follow `.claude/gates/gate-1-plan.md` exactly. The
feature at a high level:

> An always-moving Valkyrien Skies ship shaped like a train carriage. MVP
> scope: one hardcoded carriage shape (e.g. 9×5×4 block hollow box with
> floor, walls, ceiling, door gaps), spawnable via `/dungeontrain spawn`
> command, moves at constant velocity along +X. Player can stand/walk
> inside while it moves (that's the magic VS buys us). No procgen yet,
> no enemies, no loot, no linked carriages — just prove a moving train
> room works.

Before you write the plan:

1. **Re-read `CLAUDE.md`** in this repo (project rules) and any rule files
   it points at.
2. **Read `.claude/gates/gate-1-plan.md`** for the exact gate procedure.
3. **Follow Research & Reuse first** — search GitHub + VS docs for existing
   examples of "programmatically create a VS ship from a block template in
   Forge 1.20.1." The Valkyrien-Skies-2 repo (branch `1.20.1/main`) has API
   docs and test mods. Do NOT reinvent anything you can copy-port.
4. **Search the GitHub Project board #16** for existing items on "train"
   or "carriage" before creating new ones.
5. **Explore the codebase** — read `build.gradle`, `gradle.properties`,
   `src/main/java/games/brennan/dungeontrain/DungeonTrain.java`,
   `src/main/resources/META-INF/mods.toml`, and the empty
   `dungeontrain.mixins.json` — understand what's already in place.
6. **Enter plan mode**, write the plan covering:
   - What will be built (sub-components: command handler, carriage template,
     ship-assembly logic, constant-velocity applier)
   - Which files will be added/changed (e.g. new package
     `games.brennan.dungeontrain.train`, new `TrainCommand.java`,
     `CarriageTemplate.java`, `TrainShipController.java`, registration in
     `DungeonTrain.java`, new event listener for auto-applying velocity)
   - **Mod-impact check** (required per `CLAUDE.md`): are we adding a mixin?
     a new registered entity? a new command? networking packets? any VS
     API usage that might break on VS updates?
   - Risks: VS API changes between versions; ship persistence across
     world reloads; server-client sync for velocity; existing dev env
     caveats we should document.
   - Effort estimate (T-shirt size)
   - Test plan for Gate 2 — what must work in `./gradlew runClient` for
     this feature to be considered done.
7. **Present via `ExitPlanMode`** and WAIT for my approval. Do not write
   code, do not create files beyond the plan document, do not skip ahead
   to Gate 2.

Key constraints to bake into the plan:
- **Must not touch VS's own code or mixins** — only use its public API.
- **Keep the VS dep usage minimal and well-abstracted** so a future VS
  version bump doesn't cascade through our codebase.
- **MVP stays MVP** — resist scope creep into procgen, enemies, multiple
  carriages, save/load, multiplayer sync. Those are future features with
  their own gates.
- The train carriage at this stage is a **ship**, not a vanilla entity —
  that's the whole reason VS is in the dep tree.

Version bump policy (from `CLAUDE.md`): after Gate 3 merge you'd bump
`mod_version` in `gradle.properties` (`0.1.0` → `0.2.0` for this new
feature). Just note this in the plan; don't bump until after merge.

Ready? Start with re-reading `CLAUDE.md`, then go.
