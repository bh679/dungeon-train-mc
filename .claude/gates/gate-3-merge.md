<!-- gate: gate-3-merge (Dungeon Train add-on) | version: 2.0.0 -->
# Gate 3 — Merge Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-3-merge.md` first.** This file only adds the Dungeon Train
steps below; everything else (PR, report structure, approval, cleanup, docs) comes from there.

## Extra Agent Actions (before entering plan mode)
**A. Draft + log the changelog entry** — unless the change is purely non-player-facing
(CI/tooling/docs/refactors), draft a curated, player-facing summary and run
`scripts/release-notes/append-entry.py` on the feature branch (plus `--highlight` bullets and
`--pr <number>`), then commit and push so the entry is in the PR diff. The shipped version is
computed automatically. See `.github/release-notes/README.md`.
- **Pick the tags yourself, from the diff** — you know what the change is *about* better than any
  keyword rule, and there is none. Run `append-entry.py --tag-guide`, answer its one question per
  tag, and pass `--tag <tag>` for each yes (or `--no-topical-tags` if only the type-derived tag
  applies — the script refuses to run without one or the other). Tag the subject, not what the
  prose mentions: "rides the train" is not `train`, "other players' books" is not `multiplayer`.
- **Addresses:** if the change fixes or improves a player-reported issue the death-screen bug
  report offers — lag, or the train vanishing / derailing / duplicating — pass
  `--addresses lag` and/or `--addresses train_vanished`. Players who report that issue on an
  older version are then told this release addresses it.

**B. Modpack whitelist check** — if the diff adds an entry to `modpack/modpack.config.json`
(`optional_mods[]`), or changes an entry's `mod_ids` / pin: every mod the modpack ships is
**auto-approved for fair play** (the `generateApprovedMods` build task reads `mod_ids`), unless
the entry says `"whitelist": false`. Run `python3 scripts/modpack/check-mod-ids.py --fill` (new
entry) or `--verify` (bumped pin), then ask the user in the report: "`<name>` (`<mod_ids>`) will
be whitelisted for fair play — keep, or mark `whitelist: false`?" Creative/building-focused mods
are the usual candidates for an opt-out. Record the answer in the entry before merging.

## Extra Report Content
- **The changelog entry first** (as the template requires), with its tags and `--addresses`
  choice. The user must confirm the notes before the merge — leaving them in the diff is not enough.
- The modpack whitelist answer from step B, if it applied.

Approval includes sign-off on the changelog notes.
