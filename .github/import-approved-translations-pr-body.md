Translations players submitted from the in-game editor and approved on the explorer's
`#/translations` page, pulled back into the jar by
`scripts/localization/import-approved-translations.py` — so the shipped text, the provenance
sidecars and the Credits page all agree with what the relay is already serving players.

The diff is the whole change: translated values, their provenance sidecars, and the generated
credit counts. Nothing was reformatted — every write is a text-level edit of one value.

**Read the translations before merging.** This is player-written text going into the jar.

⚠️ `build.yml` does not run on a PR opened by the default token, so `localization-checks` is
absent below. The same guards (`check-provenance.py`, `check-narrative-provenance.py`,
`validate-locale.py`) ran in the import job that opened this PR; push an empty commit to this
branch if you want the full CI suite on it too.

A translator who is new to the repo is registered in `localization/authors.json` as part of
this diff rather than in a hand-written PR merged first — the import only adds people whose work
actually landed here, and never a name the registry already calls `"ai"`. When it registered
anyone, they are listed below.

Opened by `.github/workflows/import-approved-translations.yml`.
