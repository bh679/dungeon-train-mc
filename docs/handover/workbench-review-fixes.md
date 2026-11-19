# Handover: Workbench review fixes and reviewer workflow

Status as of 2026-10-27. Branch `dev/workbench-review-fixes`, pushed, at `mod_version` 0.1435.7.
**No PR yet. Not merged.** The branch is 274 commits behind `main` (main is 0.1546.0, protocol 165).

## Goal

The editor's Workbench (merged in #2009, #2011, #2012) stages relay builds of any kind so the developer
can stand in them, edit them, and commit them as templates. This branch started as fixes for a ten-point
code review of those three PRs, then grew into the reviewer workflow Brennan asked for while testing:
review a staged build from where it stands, file it straight after accepting, and see it as it will ride.

## Where the work is

| Part | Where | State |
|---|---|---|
| This branch | `bh679/dungeon-train-mc` `dev/workbench-review-fixes` | 19 commits on top of main 0.1420.0 plus one merge of main at 0.1435.0. No PR, no changelog entry. |
| Send edits back to a build's author | Draft PR [bh679/dungeon-train-mc#2227](https://github.com/bh679/dungeon-train-mc/pull/2227), branch `claude/relay-shared-edits-efdde9` | **Stacked on this branch** (uses `WorkbenchStagedBuild.live`). Calls relay endpoints that do not exist yet; the PR body spells out the relay contract. Not tested in game. Merge this branch first, then retarget #2227 to `main`. |

## What changed

### The ten review fixes (commits 0.1420.1 to 0.1420.5)

1. **Door pointer** only matches Dimensions rooms while Dimensions is resident. A tall staged build reaches
   the Dimensions layer, so a door click could rewrite an unstamped room's door offsets.
2. **Stage dedupe** keys on relay id *and* relay. `WorkbenchStagedBuild` gained `live` (`meta.json`
   `"live"`, absent reads as dev). The dev and live relays number rows independently.
3. **Test the Carriage scratch** files a room's sidecars as `PORTAL_ROOM` even when it arrived as
   `track/portal_room`, so its Copies and contents-allow files come along.
4. **Pool move** rolls back the destination copy when removing the source fails; the contents branch
   forgets its size entry on a failed save.
5. **Pool move** names a dropped builder credit (a move to Whole Half has no relay naming).
6. **Scratch ids** are a truncated SHA-256, not `String.hashCode`, so two staged builds never share one.
7. **Staged-room settings** are parsed once per sidecar document and cached (`WorkbenchStagedPlot`).
8. **Slot allocation** checks chunk sections for air before walking blocks (`WorkbenchEditor.holdsBlocks`).
9. **Plot record** lookups read an unmodifiable view instead of deep-copying the map every tick.
10. **Test commands** import `WorkbenchTestScratch` instead of spelling the package inline.

### Added during in-game testing (0.1420.6 onward)

- **Move to a Whole destination works.** The commit command's `parent` argument was a bare word and
  refused the `whole:room` sentinel. It is now greedy.
- **Whole carriage half** is a third destination in Load under and the Workbench Move picker
  (`whole:half`). `BuilderRelayWholeRoom` takes a `WholeKind`; default shells moved to `PoolScratch`.
  New lang key in all 20 locales.
- **Commit as a taken name** lands under `name_2`, `name_3`, and says so (new chat key, 20 locales).
- **Contents builds stand inside their tier's default shell**, as in the Contents editor. The plot record
  is now the build's *frame* (shell box for contents, the build itself otherwise), the bedrock cage goes
  round the frame, and old records migrate on the next Workbench entry. Save still captures only the interior.
- **Shell placeholders fade to 15%** around a staged contents build, nowhere else. New packet
  `WorkbenchShellFadePacket`; the client marks placeholders inside the boxes through model data.
- **Review a staged build from its template sheet.** The sheet gets the Submitted answers page and the
  Accept / Feedback / Resubmit / Decline row (also at the foot of page 1). The row comes from the relay
  listing the build was staged from; when none has carried it this session, the sheet asks the server for
  the pending queue itself (throttled to once per 15 s).
- **Accept leads into the commit picker**, so accepting means choosing where the build lands.
- **Entering the Workbench category switches the relay to live** and leaving puts it back. Hooked on the
  stamped category arriving in the roster, so the Nav tile, the editor menu row and the title-screen picker
  all get it. Independent of the Workbench tab's own switch.
- **Whole rooms and groups spawn their mob cells.** `WholeOverlay` had no mob pass, so mob picks were
  aired and skipped, **on the train as well as in tests**. This is the one player-facing fix on the branch.

## Things the next person needs to know

- **Never resolve a staged build through My Builds.** Dev and live relay ids collide; a live build with
  the same id as one of your own dev builds read as "mine" and hid the verdict row. A staged build resolves
  only through `EditorCreatorBuilds.byId` (with its `SEEN` cache and `liveOf`).
- **The server's hints answer is cached by relay id alone** (`BuilderSubmitHintsRequests`). The sheet
  forgets it on each selection of a staged build for that reason.
- **The review row needs the relay to answer.** On 2026-10-21 the in-game ask timed out along with every
  other relay call, so the row did not appear. A "fetching / relay unavailable" line on the sheet was
  offered and not built.
- **Protocol** was bumped to 156 (shell fade) and to 158 when main was merged. Merging main again needs a
  new value above main's 165.
- **Locale conflicts on merge:** take main's generated files (`localization_credits/*`,
  `translation_contributors.json`), keep the branch's lang keys, then run
  `scripts/localization/stamp-provenance.py --sync` and `check-lang-parity.py`.
- **Do not compile while this worktree's client runs**; stop it first.
- **Three uncommitted files** were written by the dev client during testing:
  `src/main/resources/data/dungeontrain/contents/weights.json`, `templates/weights.json` and
  `prefabs/loot/wood.json`. Brennan has not said whether to keep them. They are left out of every commit.

## Verified so far

- Full `./gradlew build` at 0.1420.5 (after the ten review fixes): 8136 tests, 0 failures, 1 skipped.
- Each later change: `compileJava` plus the touched tests (`WorkbenchStagedBuildTest`,
  `WorkbenchTestScratchTest`, `WorkbenchCommandNamesTest`, `WorkbenchShellFadeTest`,
  `CreatorParentPickerScreenTest`, `BuilderRelayWholeRoomTest`, `PoolSidecarsTest`,
  `EditorScreenActionsTest`, `TranslationVariableCoverageTest`), all green.
  `check-protocol-version.py` and `check-lang-parity.py` green.
- In game, Brennan confirmed: the Move-to failure, the shell around contents, the missing bedrock border and
  red shell (both then fixed), and the missing verdict row (fixed in three steps; final state not yet seen
  because the relay timed out).

**Not verified:** a full build since 0.1420.5; in game, the whole-group mob cells, Accept leading into the
commit picker, the relay switching to live on entering the category, and the verdict row appearing once
the relay answers. No Sable, multiplayer or dedicated-server testing (editor-only paths, except the mob fix).

## Next steps

1. Decide the three uncommitted data files.
2. Merge `origin/main` (conflicts expected in `gradle.properties`, `DungeonTrainNet.java` and the locale
   files above). Bump protocol above main's.
3. `./gradlew build` and full tests.
4. In game: the unverified items above, plus Test the Carriage on a whole group with mob cells.
5. Gate 2 report and approval.
6. Gate 3: changelog entry via `scripts/release-notes/append-entry.py` (mostly editor/internal; the
   whole-room mob fix is the player-facing line), PR, CI, squash-merge.
7. Retarget [#2227](https://github.com/bh679/dungeon-train-mc/pull/2227) to `main`, and start the dp-relay
   work its PR body describes.
