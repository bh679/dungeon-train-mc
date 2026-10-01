<!-- gate: gate-3-merge | version: 1.1.0 -->
# Gate 3 — Merge Approval

**Trigger:** After user testing passes Gate 2.

## Agent Actions
1. Ensure branch is up to date with `main` _(enforced by hook — will block `gh pr create` if behind)_
2. Create a PR with a clear title and description
3. **Draft + log the changelog entry** — unless the change is purely non-player-facing
   (CI/tooling/docs/refactors), draft a curated, player-facing summary and run
   `scripts/release-notes/append-entry.py` on the feature branch (plus `--highlight` bullets and
   `--pr <number>`), then commit and push so the entry is in the PR diff. The shipped version is
   computed automatically. See `.github/release-notes/README.md`.
   **Pick the tags yourself, from the diff** — you know what the change is *about* better than any
   keyword rule, and there is none. Run `append-entry.py --tag-guide`, answer its one question per
   tag, and pass `--tag <tag>` for each yes (or `--no-topical-tags` if only the type-derived tag
   applies — the script refuses to run without one or the other). Tag the subject, not what the
   prose mentions: "rides the train" is not `train`, "other players' books" is not `multiplayer`.
   Present the chosen tags with the notes at step 4 — they are part of what the user confirms.
   **Addresses:** if the change fixes or improves a player-reported issue the death-screen bug
   report offers — lag, or the train vanishing / derailing / duplicating — pass
   `--addresses lag` and/or `--addresses train_vanished`. Players who report that issue on an
   older version are then told this release addresses it. Present the choice with the tags.
3b. **Modpack whitelist check** — if the diff adds an entry to `modpack/modpack.config.json`
   (`optional_mods[]`), or changes an entry's `mod_ids` / pin: every mod the modpack ships is
   **auto-approved for fair play** (the `generateApprovedMods` build task reads `mod_ids`), unless
   the entry says `"whitelist": false`. Run `python3 scripts/modpack/check-mod-ids.py --fill` (new
   entry) or `--verify` (bumped pin), then ask the user at step 4: "`<name>` (`<mod_ids>`) will be
   whitelisted for fair play — keep, or mark `whitelist: false`?" Creative/building-focused mods
   are the usual candidates for an opt-out. Record the answer in the entry before merging.
4. Enter plan mode and present for approval:
   - **The changelog entry — surface the curated, player-facing notes explicitly for the user to
     confirm. The changelog must be confirmed before the merge, not merely left in the diff.**
   - File diff summary (which files changed, what changed)
   - PR link
   - Any breaking changes or migration steps
   - The modpack whitelist answer from step 3b, if it applied
5. Wait for approval (which includes sign-off on the changelog notes), then merge.

**Gate requirement:** User clicks Approve, then agent merges the PR.

**Never merge without Gate 3 approval.** Not even for hotfixes.

## Post-Merge Cleanup (mandatory)
1. Delete the remote feature branch (`git push origin --delete dev/<slug>`)
2. Delete the local feature branch (`git branch -d dev/<slug>`)
3. If continuing work, create a new branch (`git checkout -b dev/<next-slug>`)

See `git.md` § Post-Merge Cleanup for worktree variants.

## After Merge: Documentation
Update the relevant wiki:
- **Frontend/client features** → project wiki
- **Backend/API features** → API repo wiki
- **Deployment-impacting changes** → update `Deployment-*.md` wiki pages
- Follow the wiki-writing playbook: read `~/.claude/playbooks/wiki-writing.md`

If deployment docs were flagged in Gate 1:
1. Update affected `Deployment-<Method>.md` pages
2. Create new deployment pages if a new method was introduced
3. Update `Deployment.md` index if pages were added

Then trigger the blog skill if applicable: `trigger-blog`

## Session Title Update
Update title to: `DONE - <Task Name> - <Project Name>`
