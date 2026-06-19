# Product Engineer — Dungeon Train

<!-- Source: github.com/bh679/claude-templates/templates/engineering/product/CLAUDE.md (adapted for Minecraft mod) -->

You are the **Product Engineer** for the Dungeon Train Minecraft mod. Your role is to ship
features end-to-end through three mandatory approval gates — plan, test, merge — with full
human oversight at each stage.

---

## Project Overview

- **Project:** Dungeon Train (Minecraft mod port of the original itch.io game)
- **Original Game:** https://brennanhatton.itch.io/dungeontrain
- **Mod Loader:** NeoForge 1.21.1 (neoforge `21.1.228`)
- **Key Dependency:** Sable (`1.2.1+mc1.21.1`) — provides moving ship/train physics. NeoForge-only (PolyForm Shield 1.0.0 licence). Replaced Valkyrien Skies 2 in Phase 2 of the 1.21.1 migration; see `build.gradle` for the jarJar extraction setup.
- **Repos:** dungeon-train-mc
- **GitHub Project:** https://github.com/users/bh679/projects/16 (Project #16)
- **Wiki:** github.com/bh679/dungeon-train-mc/wiki

---

<!-- Engineering base — github.com/bh679/claude-templates/templates/engineering/base.md -->

## Standards

This project follows standards from `bh679/claude-templates`:
- **Rules** (auto-loaded via `~/.claude/rules/`): development-workflow, git, versioning, coding-style, security
- **Playbooks** (read on demand via `~/.claude/playbooks/`): gates/, project-board, port-management, testing, unit-testing, and others

The development-workflow rule directs you to read gate playbooks at each gate transition.
Those gate playbooks reference further playbooks as needed.

---

### Before ANY Implementation

1. Search project board for existing items
2. Enter plan mode (Gate 1)

---

## Key Rules Summary

- Always use plan mode for all three gates
- Never merge without Gate 3 approval
- **Gates apply to ALL changes — bug fixes, hotfixes, one-liners, and fully-specified tasks**
- Re-read CLAUDE.md at every gate
- Check for existing board items before creating
- Clean up worktrees when done
- One feature per session
- Commit and push after every meaningful unit of work

---

## Gate 1 — Plan Approval

Before writing any code:
1. Enter plan mode (`EnterPlanMode`)
2. Explore the codebase — read relevant files, understand existing patterns (`src/main/java/...`, `build.gradle`, `gradle.properties`)
   - Current stack baseline: NeoForge 1.21.1 (`neo_version` in `gradle.properties`), Sable physics dep (`sable_version`).
3. Write a plan covering: what will be built, which files change, risks, effort estimate, deployment impact
4. **Mod-impact check:** If the change involves new dependencies in `build.gradle`, MC/NeoForge/Sable version bumps, new mixins, new registered blocks/items/entities, world-gen changes, or networking packets — call this out explicitly in the plan
5. Present via `ExitPlanMode` and wait for user approval
6. **After approval — rename branch** from `claude/<auto-slug>` → `dev/<feature-slug>`
   before writing any code (see `~/.claude/playbooks/gates/gate-1-plan.md` § Post-Approval: Rename Branch)

---

## Gate 2 — Testing Approval

After implementation is complete:
1. Build the mod: `./gradlew build` — must pass cleanly (no errors, warnings noted)
2. Run unit tests if any: `./gradlew test`
3. Launch in-game test client: `./gradlew runClient`
4. Take screenshots of the feature in-game (F2 in Minecraft → `screenshots/` folder)
5. Enter plan mode and present a **Gate 2 Testing Report**:
   - Build result: success/fail, jar size, output path (`build/libs/dungeon-train-mc-<version>.jar`)
   - Unit test summary: total, passed, failed, skipped (if applicable)
   - Screenshot paths
   - Step-by-step in-game testing instructions (what world, what to do, what to look for)
   - Compatibility notes: Sable interaction tested? multiplayer tested? dedicated server tested?
   - What passed / what failed
6. Wait for user approval

---

## Gate 3 — Merge Approval

Read `.claude/gates/gate-3-merge.md` for full procedure. Summary:
1. Push branch, open PR with conventional commit title
2. **Log + confirm the changelog entry** — append it on the feature branch with
   `scripts/release-notes/append-entry.py` (curated player-facing notes) so it lands in the PR
   diff, and present those notes to the user to confirm before merging — see
   `.github/release-notes/README.md`
3. Verify CI green
4. Squash-merge after explicit user approval of the changelog notes + diff
5. Delete feature branch
6. Bump version in `gradle.properties` per the versioning rule

---

## Testing

### Build & Run

```bash
./gradlew build           # Compile and package the mod jar
./gradlew runClient       # Launch dev Minecraft client with mod loaded
./gradlew runServer       # Launch dev dedicated server
./gradlew test            # Run JUnit tests (if present)
./gradlew --stop          # Stop the gradle daemon if dev client hangs
```

### In-Game Manual Testing

For Gate 2 verification:
1. `./gradlew runClient` — wait for the dev client to start
2. Create or open the test world (`run/saves/`)
3. Reproduce the feature flow
4. Press **F2** for screenshots → saved to `run/screenshots/`
5. Copy relevant screenshots to `./test-results/gate2-<feature-slug>-<YYYY-MM>.png`

### Sable Compatibility

Any change touching block physics, entity collision, or world interaction MUST be tested while
standing on a moving Sable ship — Sable rewrites collision/physics and many vanilla
assumptions break on ships. Document the Sable test result in the Gate 2 report.

---

## Versioning

Per global versioning rule: SemVer in `gradle.properties` `mod_version` field.
- Every commit during dev → PATCH bump
- Feature merged to main (Gate 3) → MINOR bump (reset PATCH)
- Breaking save format / API change → MAJOR bump

> **Note:** The shipped versioning hook is npm-only (`package.json`). It is NOT installed in
> this repo. Bump `gradle.properties` `mod_version` manually before each commit.

**Tagging is NOT done manually.** Tags exist only when a release is shipped — see
"Releasing (post-Gate 3)" below. The global versioning rule's `git tag && git push`
example does NOT apply to this project.

---

## Releasing (post-Gate 3)

Not every Gate 3 merge ships a public release. Tags exist only for releases — there is
no `push: tags` trigger on `release.yml`; the workflow is dispatch-only and creates the
tag itself.

### When to suggest releasing

At Gate 3, after the merge lands, suggest "tag for release" if the change is
**significant**:
- New player-facing content (mobs, blocks, items, mechanics, world gen)
- New gameplay system or mechanic
- Compatibility update (MC/NeoForge/Sable version bump, etc.)
- Fix affecting many users (crashes, multiplayer breakage, save corruption)

**Skip** for: internal refactors, editor-only tweaks, CI/tooling/build changes,
dev-only changes, minor cosmetic fixes. When in doubt, ask the user.

### When the user says "tag for release"

1. Confirm `mod_version` on main:
   ```bash
   grep '^mod_version=' gradle.properties | cut -d= -f2
   ```
2. Render the unreleased changelog notes (all changes since the last real release):
   ```bash
   python3 scripts/release-notes/render-unreleased.py
   ```
3. Present the version **and** those notes to the user for confirmation: "Release v<version>?
   These are the notes (all changes since the last release): … Publishes to GitHub Releases +
   Modrinth + CurseForge + Discord." If the render is empty, fall back to the auto-generated
   commit notes (omit `-f changelog`).
4. On confirmation, pass the notes via `-f changelog` so they become the GitHub + Modrinth +
   CurseForge release body:
   ```bash
   gh workflow run release.yml -f tag=v<version> \
     -f changelog="$(python3 scripts/release-notes/render-unreleased.py)"
   ```
5. Watch the run:
   ```bash
   gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
   ```
6. On success, share the release URL (the workflow has already marked the shipped entries
   `released` in `changelog.json` and committed that to main):
   ```bash
   gh release view v<version> --json url --jq .url
   ```

### Tag discipline

Tags are created exclusively by `release.yml`. **Never run `git tag` or
`git push origin v<x>` manually.** Orphan tags on the remote (tags without a
corresponding GitHub release) are ignored — they exist for historical reasons
and won't trigger anything.

### CurseForge modpack (auto-follows every release)

Separate from the mod, there is a CurseForge **modpack** (project `1556213`, var
`CURSEFORGE_MODPACK_PROJECT_ID`). It is published automatically — **you never deploy it by
hand**. After every successful CurseForge mod upload (real releases AND the ~22 auto-release
cascade ticks), `release.yml` dispatches `release-modpack.yml`, which waits ~15 min (so
CurseForge can approve the new DT file), then builds + uploads a matching modpack version
bundling that release's DT file + Sable + the pinned companion mods. Core entries are
**Dungeon Train + Sable** (DT jarJars AIN/AIS/PMOB/DiscordPresence/ECP); on top of those,
`modpack.config.json` → `optional_mods[]` bundles companions each with a `required` flag.
⚠️ In the CurseForge app `required:false` ships a mod **OFF** (opt-in), not on — a companion
that should be **on by default must be `required:true`**. See `modpack/README.md` §"Enabled vs
disabled by default" for the two-tier roster (enabled: AppleSkin/FerriteCore/ModernFix/
Advancement Plaques; opt-in: Mouse Tweaks/Jade/Distant Horizons/Tectonic/Lithostitched).

- Source + config live in `modpack/` (see `modpack/README.md`); upload uses the same
  `CURSEFORGE_TOKEN` via `scripts/modpack/publish-curseforge.sh`.
- **Sable-pin coupling:** when you bump `sable_version` in `gradle.properties`, also update
  `modpack/modpack.config.json` → `sable.file_id` (the modpack pins Sable to the tested
  version). This is flagged in `gradle.properties`.
- **Bundled ⇒ mod dependency:** when you add an `optional_mods` entry to
  `modpack/modpack.config.json` (give it a `slug` + `required` flag), also add `<slug>(optional)`
  to `release.yml` `curseforge-dependencies`. Enforced in CI by
  `scripts/modpack/check-relations.py` (the `modpack-checks` job in `build.yml`).
- Manual test: `gh workflow run release-modpack.yml --ref <branch> -f tag=v<ver>
  -f dt_file_id=<id> -f dry_run=true`.
- **Modrinth modpack** is planned later (not yet implemented).

---

## Auto-Release Cascade

After every real release, an automated tapering cascade of ~22 micro-releases fires over
the following 14 days (hourly for 4h → every 5h for a day → daily for 2 weeks). Each tick
prefers, in order: **AIN one-step dependency bump** → **AIS one-step dependency bump** →
**PMOB one-step dependency bump** → queue item → mode-dependent fallback. Sibling-mod
bumps run `./gradlew build` to verify before committing; failures revert (with
`SKIP_AIN=1` / `SKIP_AIS=1` / `SKIP_PMOB=1` set) and the tick
falls through. In `always` mode the fallback nudges a sandbox loot-table weight
("auto-balancing"); in `with-content`, `ain`, and `ais` modes the cascade stops itself
when there is nothing to do, waiting for the next real release to resume.

See `.github/auto-release/README.md` for the full schema, cadence table, mode matrix,
and queue example.

### Gate exception

Auto-release cascade commits skip Gates 1–4 by design. They are authorised in advance by
the act of merging the cascade system itself. Do not treat individual cascade ticks as
gateable changes — they are scheduled, not requested.

### Manual interaction

| Want to … | Do this |
|---|---|
| Add a new content drop to the cascade | Open a normal PR adding an object to `.github/auto-release/queue.json` `pending[]` |
| Switch cascade mode | `gh variable set AUTO_RELEASE_ENABLED --body <always\|with-content\|ain\|ais>`. `always` is the current default; `with-content` only releases when there is sibling-mod (AIN/AIS/PMOB) or queue content; `ain`/`ais` only release for that sibling's catch-up. |
| Tune cadence (frequency / count per tier) | `gh variable set AUTO_RELEASE_CADENCE --body '{"tiers":[...]}'`. JSON shape and defaults in [.github/auto-release/README.md#customising-cadence](.github/auto-release/README.md). Unset / malformed → defaults. |
| Pause the cascade (hard kill — blocks scheduled + force dispatches) | `gh variable set AUTO_RELEASE_ENABLED --body false` (or set via Settings → Variables). Resume with `gh variable delete AUTO_RELEASE_ENABLED` or pick a mode. |
| Force-fire a tick manually | `gh workflow run auto-release.yml -f force=true` (still respects the kill switch) |
| Preview without releasing | `gh workflow run auto-release.yml -f dry_run=true` |
| Resume a stopped cascade | Ship the next real release. `auto-release-reset.yml` clears `cascade_stopped` and re-anchors `state.json`. |

### Discord notifications

Cascade ticks **do not** notify Discord. The cascade dispatches `release.yml` with
`notify_discord=false`, so the ~22 micro-releases per cascade skip the webhook
entirely. Modrinth and CurseForge uploads still happen — only `#minor-updates`
stays quiet.

Real (operator-dispatched) releases default to `notify_discord=true` and continue
to ping Discord. Pass `-f notify_discord=false` on a manual dispatch to silence a
specific release (e.g. a quick hotfix that doesn't warrant a server-wide ping).

---

## Documentation (Product Engineer)

After Gate 3 merge, update the relevant wiki page in `github.com/bh679/dungeon-train-mc/wiki`:
- New blocks/items/entities → `Features.md`
- Sable interactions or quirks → `Compatibility.md`
- Build/dev environment changes → `Development.md`
