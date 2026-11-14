# Development hours

How many hours of work are behind the mod. This directory produces that number, and
`docs/dev-hours/hour-index.json` on main is the **one place it lives**. Every surface reads it:

| Surface | How it reads the file |
|---|---|
| In-game Contribute card (death screen → DONATE → **Contribute**) | baked at build time by `build.gradle` `devHours` |
| Website Support page + Update page roadmap note (dp-relay) | `totalHours`, fetched from raw GitHub by `public/dungeontrain/dev-hours.js` |

Never hard-code the figure anywhere else; point the new place at this file.

## What the number means

**Clock hours in which a commit landed, de-duplicated across repos.** Not commits, not repo-hours:
an hour in which commits went to `dungeon-train-mc` *and* `playermob-mc` *and* `dp-relay` is **one**
hour. Every stage works on a set of hour buckets and unions them — nothing sums two counts.

On 2026-11-15 that was **2,626 hours** across 22 repos, against 3,673 if the per-repo counts were
naively added, so de-duplication is doing real work.

All commits count, bot commits included: the auto-release cascade's commits are the tail of release
work that was itself done by hand.

## Why a committed snapshot

The sibling repos are not checked out when Dungeon Train builds — not in CI, not on another
machine — so their hours can only arrive pre-computed. The build therefore unions **two** sources:

| Source | Covers | Freshness |
|---|---|---|
| `docs/dev-hours/hour-index.json` (committed) | every repo in `repos.json` | as of the last run of `collect.py` |
| this checkout's live `git log` (build time) | dungeon-train-mc | always current |

Because the snapshot already carries DT's full history, a shallow CI clone is harmless — the live
pass only ever adds hours it can see.

## Refreshing it

**Automatic, weekly.** `.github/workflows/dev-hours.yml` runs Mondays 03:00 UTC: it clones every
repo in `repos.json` from GitHub (blobless, history only), re-runs `collect.py`, and commits
`hour-index.json` to main when the figure moved. The private `dp-relay` is read with the
`RELAY_REPO_TOKEN` secret. Run it now with `gh workflow run dev-hours.yml` (add `-f dry_run=true`
to only print the totals).

By hand:

```bash
GH_TOKEN=$(gh auth token) python3 scripts/dev-hours/collect.py --clone-dir /tmp/dev-hours  # from GitHub
python3 scripts/dev-hours/collect.py --dry-run   # local `path` checkouts only; print, write nothing
```

**The total never shrinks.** A lower total than the committed one means a repo was skipped (failed
clone, missing token), so `collect.py` refuses to write and the workflow goes red. `--allow-shrink`
overrides it for a deliberate drop (e.g. removing a repo from the list).

Edit `repos.json` to add or drop a repo: `repo` is the GitHub `owner/name`; `path` is an optional
local checkout for offline runs. **New sibling mod ⇒ add it here.** `MortalMe` is deliberately
absent (different game).

## Where it surfaces

`collect.py` → `docs/dev-hours/hour-index.json` → `devHours` closure in `build.gradle` →
`dev_hours` in `dungeontrain_version.properties` → `VersionInfo.DEV_HOURS` →
`client/support/DevHours` → `DonationOptionsScreen`.

`docs/dev-hours/hour-index.json` → `totalHours` → dp-relay `public/dungeontrain/dev-hours.js`
(`DtDevHours.load()`) → Support page (`support.js`) and Update page (`update/timeline.js`). The
pages keep a hard-coded fallback for when the fetch fails.

Every link degrades rather than breaks: a missing snapshot falls back to DT-only, no git falls back
to the snapshot, and both failing bakes `0` — which means **unknown**, so the page omits the line
entirely rather than claiming zero hours.

Tests: `scripts/dev-hours/test_collect.py` (dedup + delta codec) and `DevHoursTest` (the
show-nothing-when-unknown rule).
