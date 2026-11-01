<!-- gate: gate-2-test (Dungeon Train add-on) | version: 1.0.0 -->
# Gate 2 — Testing Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-2-test.md` first.** This file replaces its web steps
(Playwright, local URL, accessibility snapshot) with the Minecraft ones below.

## Agent Actions
1. `./gradlew build` must pass; note warnings. Run `./gradlew test`.
2. Test in the dev client (`./gradlew runClient`), or headless with the RCON harnesses for
   server-side changes. Never compile in a worktree while its client is running.
3. Screenshots: **F2** in the client saves to `run/screenshots/`. Copy the ones that matter to
   `./test-results/gate2-<feature-slug>-<YYYY-MM>.png`. Do not use macOS `screencapture`.
4. Show the screenshots rendered in chat (the template's step 5).
5. Changes touching block physics, entity collision or world interaction: test standing on a
   moving Sable train.

## Extra Report Content
- Build: result, jar path (`build/libs/dungeontrain-<version>.jar`), size
- Step-by-step in-game test instructions: which world, what to do, what to look for
- Compatibility: Sable tested? multiplayer? dedicated server?
