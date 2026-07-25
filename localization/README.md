# Translation Provenance

Every translated string committed in this repo carries a `{author, reviewer}` record — who
produced the current translation and who human-reviewed it — so anyone can see at a glance
which text is machine-translated and which a human has checked. Three bodies of content are
tracked, all repo-side only (see below):

| Content | Source | Sidecars | Tooling |
|---|---|---|---|
| dungeontrain UI (flat lang keys) | `assets/dungeontrain/lang/<locale>.json` | `provenance/<locale>.json` | `stamp-provenance.py` / `check-provenance.py` |
| Sibling-mod UI (AIN / PlayerMob / DiscordPresence) | `assets/<namespace>/lang/<locale>.json` | `provenance/<namespace>/<locale>.json` | same, all namespaces by default |
| Narrative books/stories (per book) | `data/dungeontrain/narrative_localizations/<locale>/**` | `narrative_provenance/<locale>.json` | `stamp-narrative-provenance.py` / `check-narrative-provenance.py` |

The rest of this doc describes the dungeontrain per-line system in full; the
[Siblings](#sibling-mod-namespaces) and [Narrative books](#narrative-booksstories) sections
below note only where each differs.

`provenance/<locale>.json` records, for **every** localized string in
`src/main/resources/assets/dungeontrain/lang/<locale>.json`, who produced the current
translation and who human-reviewed it — so anyone can see at a glance which lines are
machine-translated and which a human has checked.

Nothing in this directory ships in the jar (only `src/main/resources` and
`src/main/templates` enter the build). This is repo-side bookkeeping; the shipped,
player-visible counterparts live in
`src/main/resources/assets/dungeontrain/localization_credits/<locale>.json`:

- the locale-LEVEL `human_reviewed` flag (hand-maintained judgment call) that drives
  the faded "AI" logo in the language list, and
- three GENERATED count fields — `total_keys` / `ai_authored` / `ai_unreviewed` —
  summarizing this directory's sidecars, which drive the **blue AI-fraction ring**
  around that logo (filled circumference = `ai_unreviewed / total_keys`). Every
  `stamp-provenance.py` run refreshes them, and `check-provenance.py` **hard-fails**
  (exit 1, in CI) when they drift from the sidecars — never hand-edit them.

## Schema

Flat JSON object, one entry per line, **keys in the same order as the locale's lang
file** (so provenance diffs align line-for-line with lang-file diffs), raw UTF-8:

```json
{
  "echo.dungeontrain.mob_name": {"author": "老本願", "reviewer": "老本願"},
  "gui.dungeontrain.book_vote.ask_prefix": {"author": "Opus 4.8 (Claude)", "reviewer": ""}
}
```

- `author` — who produced the **current** value of the line. A model name for machine
  translation, a person's name for human translation. Never empty.
- `reviewer` — who human-reviewed the current value. `""` = not human-reviewed.
- Changing a line's translation **resets its reviewer to `""`** — a review attests one
  specific value, not the key. (`stamp-provenance.py` enforces this on `--author`
  restamps.)
- `en_us` is the source language, not a translation — it has no sidecar, and CI
  rejects one.

## Author registry — `authors.json`

Every name used in a sidecar must be registered in [`authors.json`](authors.json) as
`"ai"` or `"human"` (enforced by CI, including that a **reviewer must be a registered
human** — an AI cannot human-review). The registry is what makes the headline metric
computable: a line is **AI-unreviewed** iff its author is registered `ai` and its
reviewer is empty; `check-provenance.py --report` shows the count and percentage per
locale.

| Name | Kind | Meaning |
|---|---|---|
| `Opus 4.8 (Claude)` | ai | Machine translation by Claude Opus 4.8 (waves #768, #776, #809, #821; the early zh_cn commits #754/#755/#763 didn't record their model and are **assumed Opus 4.8** per operator decision, 2026-07-23) |
| `老本願` | human | Community translator — original zh_cn tree (#754 seed, #759 v0.458.0 drop) and its review (#770) |
| `阿世xAsh` | human | Community translator — the #823 zh_cn/zh_tw Support-page revision pass |

To use a new model or translator name, add it to `authors.json` first —
`stamp-provenance.py` refuses unregistered names, so the registry can't drift behind
the sidecars. Name future models `<Model> (Claude)` etc., and humans by their
preferred credited name.

## Sibling-mod namespaces

AdventureItemNames, PlayerMob and DiscordPresence are separate mods whose *source* repos are
English-only — their community translations are committed **here in DT** (under
`assets/<namespace>/lang/`), so their provenance lives here too, at
`provenance/<namespace>/<locale>.json`. These use the **same per-line schema, scripts and
author registry** as dungeontrain, with two differences:

- They have **no shipped credit files or in-game AI-ring** (bookkeeping only) — so no
  `total_keys`/`ai_authored`/`ai_unreviewed` counts and no `translation_contributors.json`.
- Their locale set is whatever each namespace's lang dir actually contains — e.g.
  DiscordPresence has no `zh_cn` here (it lives in its source repo), so it simply has no
  `zh_cn` sidecar. Nothing is hardcoded; the tooling discovers each namespace's locales.

`stamp-provenance.py` and `check-provenance.py` process **all namespaces by default**
(dungeontrain + the three siblings). Use `--namespace <name>` to restrict, or the explicit
`--lang-dir`/`--provenance-dir` for a one-off dir.

## Narrative books/stories

The long-form books under `data/dungeontrain/narrative_localizations/<locale>/` are tracked
**per book** (a book is translated as one unit), not per line. Each locale's sidecar
`narrative_provenance/<locale>.json` is a flat map keyed by the book's path relative to the
locale dir (sans `.json`):

```json
{
  "random_books/deathnote": {"author": "老本願", "reviewer": "老本願"},
  "starting_books/questions": {"author": "Opus 4.8 (Claude)", "reviewer": ""}
}
```

Same `{author, reviewer}` semantics, same author registry, same on-disk format and the same
"restamp author resets reviewer" rule. There is no `en_us` locale here (the English books
live in `data/dungeontrain/narratives/`, not as a locale) and no shipped credits. Driven by
`stamp-narrative-provenance.py` / `check-narrative-provenance.py` (same verbs as the lang
scripts, but `--files` selects books and `--report` counts books):

```bash
# new/changed book translations:
python3 scripts/localization/stamp-narrative-provenance.py --sync --author 'Opus 4.8 (Claude)'
# a translator reviewed a book:
python3 scripts/localization/stamp-narrative-provenance.py --locale zh_cn \
    --author 老本願 --reviewer 老本願 --files random_books/deathnote
# coverage report:
python3 scripts/localization/check-narrative-provenance.py --report
```

## Workflows

These commands cover the dungeontrain + sibling lang files. For the narrative books, use the
`*-narrative-provenance.py` equivalents shown above.

**New translation wave** (you added/changed lang keys):

```bash
python3 scripts/localization/stamp-provenance.py --sync --author 'Opus 4.8 (Claude)'
```

CI (`localization-checks` in build.yml) fails any PR whose lang keys lack provenance,
so this step is not optional — which is the point.

**Human review pass** (a translator reviewed and/or revised lines):

```bash
# revised lines — translator becomes author AND reviewer:
python3 scripts/localization/stamp-provenance.py --locale zh_cn \
    --author 阿世xAsh --reviewer 阿世xAsh --keys gui.dungeontrain.support.title ...
# lines confirmed as-is — original author stays, reviewer added:
python3 scripts/localization/stamp-provenance.py --locale zh_cn \
    --reviewer 阿世xAsh --prefix gui.dungeontrain.support.
```

**Coverage report** (how much of each locale is AI-generated without human review):

```bash
python3 scripts/localization/check-provenance.py --report
```

## Backfill notes (July 2026)

The initial sidecars were generated by `scripts/localization/backfill-provenance.py`
(committed as the audit trail; its commit map is closed — never rerun it after new
lang commits). Judgment calls baked into that backfill:

- **zh_cn reviewer coverage** comes from the PR #770 `human_reviewed` flip: the 806
  keys that existed then (values verified unchanged since) carry `reviewer: 老本願`.
  Keys machine-added afterwards (#809, #821) are unreviewed — which is why zh_cn shows
  ~94.6% instead of 100%, and why the check emits an advisory WARNING about the
  locale-level flag.
- **`gui.dungeontrain.support.subtitle`** (zh_cn + zh_tw) is authored by 阿世xAsh but
  ships Claude's 力所能及 idiom fix "pending translator confirmation" (PR #823's own
  words), so it stays **unreviewed** until the translator confirms — at which point:
  `stamp-provenance.py --locale zh_cn --locale zh_tw --reviewer 阿世xAsh --keys gui.dungeontrain.support.subtitle`
- **The 3 `.modpack` sibling keys** (zh_cn + zh_tw) are Claude-derived variants
  (模组→整合包) of 阿世xAsh's revised copy that the translator never saw: credited
  `Opus 4.8 (Claude)`, unreviewed.

### Sibling namespaces + narrative books (July 2026)

The sibling-namespace sidecars (`provenance/{adventureitemnames,playermob,discordpresence}/`)
and the narrative sidecars (`narrative_provenance/`) were backfilled with a plain
`stamp-*-provenance.py --sync --author 'Opus 4.8 (Claude)'` — every entry credited to Opus
and **unreviewed** (`reviewer: ""`), the conservative default. No per-book review history was
reconstructed; real human reviews get stamped going forward via the review pass. These
bodies have no earlier waves to attribute, so there is no closed commit-map backfill script
for them — a fresh `--sync` is the correct way to pick up new siblings/books.
