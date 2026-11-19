<!-- gate: gate-2-test (Dungeon Train add-on) | version: 1.1.0 -->
# Gate 2 — Testing Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-2-test.md` first.** This file adds the Minecraft
steps: how to build, run and capture screenshots.

## Agent Actions
1. `./gradlew build` must pass; note warnings. Run `./gradlew test`.
2. Test in the dev client (`./gradlew runClient`), or headless with the RCON harnesses for
   server-side changes. Never compile in a worktree while its client is running.
3. Screenshots: **F2** in the client saves to `run/screenshots/`. Copy the ones that matter into the
   feature's folder, `~/Projects/Screenshots/DungeonTrainCraft/<feature-slug>/` (template
   § Screenshots). Do not use macOS `screencapture`.
4. Show the screenshots rendered in chat (`~/.claude/playbooks/ui-prototype.md` § Gate 2).
5. Changes touching block physics, entity collision or world interaction: test standing on a
   moving Sable train.

## Extra Report Content
- **Run command first**, as a fenced ```` ```bash ```` block so the app gives it a Run button
  (it renders in plan mode too). Use the feature worktree's absolute path:
  ```bash
  cd <absolute-worktree-path> && ./gradlew runClient
  ```
- Build: result, jar path (`build/libs/dungeontrain-<version>.jar`), size
- Step-by-step in-game test instructions: which world, what to do, what to look for
- Compatibility: Sable tested? multiplayer? dedicated server?
