# Development hours

The Contribute page (death screen → DONATE → **Contribute**) shows how many hours of work are
behind the mod. This directory produces that number.

## What the number means

**Clock hours in which a commit landed, de-duplicated across repos.** Not commits, not repo-hours:
an hour in which commits went to `dungeon-train-mc` *and* `playermob-mc` *and* `dp-relay` is **one**
hour. Every stage works on a set of hour buckets and unions them — nothing sums two counts.

At the time of writing that is **1,394 hours** against 1,844 if the per-repo counts were naively
added, so de-duplication is doing real work.

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

```bash
python3 scripts/dev-hours/collect.py          # rewrites docs/dev-hours/hour-index.json
python3 scripts/dev-hours/collect.py --dry-run  # print the totals, write nothing
```

Commit the regenerated `hour-index.json`. Worth doing before a real release; DT's own hours need no
refresh at all. A repo listed in `repos.json` that isn't checked out locally is skipped with a
warning — the run still succeeds, the number is just missing those hours.

Edit `repos.json` to add or drop a repo. `MortalMe` is deliberately absent (different game).

## Where it surfaces

`collect.py` → `docs/dev-hours/hour-index.json` → `devHours` closure in `build.gradle` →
`dev_hours` in `dungeontrain_version.properties` → `VersionInfo.DEV_HOURS` →
`client/support/DevHours` → `DonationOptionsScreen`.

Every link degrades rather than breaks: a missing snapshot falls back to DT-only, no git falls back
to the snapshot, and both failing bakes `0` — which means **unknown**, so the page omits the line
entirely rather than claiming zero hours.

Tests: `scripts/dev-hours/test_collect.py` (dedup + delta codec) and `DevHoursTest` (the
show-nothing-when-unknown rule).
