# Version history

The death screen's donation page shows, once the running costs are met, **how many updates have
shipped** and **how long ago the most recent one was**. This directory produces the first figure's
source data.

## What counts as an update

**A MINOR version bump** — `0.762.x` → `0.763.0` — one per Gate 3 merge, one shipped change. The
auto-release cascade's PATCH ticks are *not* updates: they are the tail of an update that already
happened, and counting them would treble the number with automation noise (1,600 versions against
765 updates at the time of writing).

## Where the window comes from

The tile says "Updates, Last 5 months" rather than "Updates This Year" while the project is younger
than a year — the first version landed 2026-04-20, and a five-month-old game claiming a year reads
as a stretch. The window is the project's own age in months, **rounded up** so it still covers
every update ever shipped, and becomes the calendar year to date once the project turns one.

That rule lives in four places, deliberately duplicated rather than shared across a repo boundary:
`window_months` here, the `updateStats` closure in `build.gradle`, `UpdateStats.java`, and
dp-relay's `updates.js`. Only this file's `daily` map and `firstVersionDate` cross the wire, so
every consumer re-derives the window on the day it renders — the switch to "this year" happens on
the project's first birthday with no jar and no deploy.

## Refreshing it

```bash
python3 scripts/version-history/collect.py            # rewrites docs/version-history/index.json
python3 scripts/version-history/collect.py --dry-run  # print the totals, write nothing
```

`.github/workflows/version-bump.yml` runs it inside the auto-bump commit, so the index moves with
every Gate 3 merge and no one has to remember. The collector counts the version sitting in
`gradle.properties` as well as the committed history, because during that commit the new version
isn't in `git log` yet.

## Where it surfaces

`collect.py` → `docs/version-history/index.json` → **two** consumers:

| Consumer | Path | Freshness |
|---|---|---|
| dp-relay (live) | `updates.js` polls the raw file over HTTP every 6h → `GET /donations/summary` → `DonationSummaryClient.Updates` | current, and re-derived per request |
| the jar (fallback) | `updateStats` in `build.gradle` → `dungeontrain_version.properties` → `VersionInfo.UPDATES_*` → `client/support/UpdateStats` | frozen at build time |

The relay wins when it answers; the baked numbers are what an offline player sees. The build
unions the committed index with the live history of whatever checkout is building (per-day MAX),
so a shallow CI clone leans on the index and a dev build still counts today's merges.

Every link degrades rather than breaks: a missing index falls back to git, no git falls back to
the index, and both failing bakes `0` — which means **unknown**, so the page omits the row rather
than showing a would-be donor a zero. Same contract as `scripts/dev-hours/`.

Tests: `scripts/version-history/test_collect.py` (the minor-bump filter and the window rule at its
edges) and `UpdateStatsTest` (source precedence and the show-nothing-when-unknown rule).
