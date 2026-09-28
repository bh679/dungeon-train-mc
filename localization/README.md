# Translation Provenance

Every translated string committed in this repo carries a `{author, reviewer, source_hash}`
record — who produced the current translation, who human-reviewed it, and a digest of the
English it was produced from — so anyone can see at a glance which text is machine-translated,
which a human has checked, and which the English has moved on from since. Three bodies of
content are tracked, all repo-side only (see below):

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
player-visible counterparts are the [manifests](#shipped-manifests) and
`src/main/resources/assets/dungeontrain/localization_credits/<locale>.json`:

- the locale-LEVEL `human_reviewed` flag (hand-maintained judgment call) that drives
  the faded "AI" logo in the language list. **Flip it back to `false` when a machine
  translation wave adds unreviewed lines to a locale that was flagged `true`** — the flag
  is a claim to the player that a person stood behind this translation, and it stops being
  true the moment AI writes lines nobody has checked. `check-provenance.py` emits an
  advisory WARNING for exactly that state; treat the warning as the to-do. Set it back to
  `true` when a translator has worked the queue down (see
  [Handing work to a translator](#workflows)), and
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
  "echo.dungeontrain.mob_name": {"author": "老本願", "reviewer": "老本願", "source_hash": "9c0b2e7f1d4a6b38"},
  "gui.dungeontrain.book_vote.ask_prefix": {"author": "Opus 4.8 (Claude)", "reviewer": "", "source_hash": "3a1f0c9e5b7d2a41"}
}
```

- `author` — who produced the **current** value of the line. A model name for machine
  translation, a person's name for human translation. Never empty.
- `reviewer` — who human-reviewed the current value. `""` = not human-reviewed.
- `source_hash` — the first 16 hex chars of SHA-256 of the **English** value this line was
  last stamped against (`provenance_io.source_hash`). `""` = unknown: the English is not in
  this repo (the sibling namespaces). Every stamp — `--author`, `--reviewer`, or a new key
  under `--sync` — records the current English, because a translator worked from it or a
  reviewer read it. `--sync` **leaves existing hashes alone**: when `en_us` is edited, the
  mismatch between the recorded hash and the current English is exactly the signal that the
  translation may no longer say what the English says (see the manifests below).
- Changing a line's translation **resets its reviewer to `""`** — a review attests one
  specific value, not the key. (`stamp-provenance.py` enforces this on `--author`
  restamps.)
- `en_us` is the source language, not a translation — it has no sidecar, and CI
  rejects one.

## Shipped manifests

The credit counts above answer *how much* of a locale is machine-translated. The in-game
translation editor needs *which lines* — so `stamp-provenance.py` also generates
`src/main/resources/assets/dungeontrain/localization_provenance/<locale>.json`, the one
part of this per-line system that enters the jar:

```json
{
  "_note": "Generated from … — do not hand-edit. \"*\" = every unit in that body. …",
  "locale": "de_de",
  "lang": { "dungeontrain": "*", "adventureitemnames": "*", "playermob": "*", "discordpresence": "*" },
  "source_changed": { "dungeontrain": ["command.dungeontrain.report_carriage.success", "…"], "adventureitemnames": [], "playermob": [], "discordpresence": [] },
  "books": "*",
  "books_source_changed": ["death_lore/default", "…"]
}
```

- One manifest per locale spans **both** bodies — every lang namespace plus the narrative
  books — because the editor lists them in one screen.
- `lang` / `books`: which units are AI-authored and unreviewed (the editor's `AI` badge).
  `source_changed` / `books_source_changed`: which units' **English was edited after they
  were last translated or reviewed** — every entry whose `source_hash` no longer matches the
  current English (the editor's amber `↻` badge, and the "edited after this was translated"
  heading on the edit screen). Both kinds land in the editor's "Needs a human" queue. A
  sibling namespace always lists `[]` here: its English is not in this repo to compare.
- Each value is `"*"` (every unit in that body), `[]`, or an explicit list of keys / book
  paths. `"*"` is what keeps these small: 17 locales are wholly machine-translated, so their
  `lang` collapses to a few hundred bytes; only zh_cn and zh_tw carry real lists there.
- **Editing `en_us.json` (or an English book) changes the manifests** even though it changes
  no sidecar — so after an English edit, re-run `stamp-provenance.py --sync` (no `--author`
  needed when no key is new) and commit the refreshed manifests, or `check-provenance.py`
  fails on drift. `import-english-edits.yml` does this itself before its checks.
- A body with **no sidecar** for that locale is omitted entirely, so "absent" (DiscordPresence
  has no `zh_cn` here) stays distinguishable from "nothing needs review".
- Generated, like the credit counts — **never hand-edit**. Both `stamp-provenance.py` and
  `stamp-narrative-provenance.py` rebuild every manifest on every run, whatever their
  `--locale`/`--namespace` filter, and `check-provenance.py` **hard-fails** on drift, a
  missing manifest, or an orphan. It checks both bodies (`check-narrative-provenance.py`
  has no view of the lang namespaces, so it cannot be the one to do it).
- Build/compact rules are tested in `scripts/localization/test_provenance_io.py`.

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
| `Opus 5 (Claude)` | ai | Machine translation by Claude Opus 5 (the v0.528.0 wave, 2026-08 — see [Backfill notes](#backfill-notes-july-2026); and the v0.669.x gap-fill that brought all nineteen locales up to the full en_us key set, ~4,225 lines) |
| `老本願` | human | Community translator — original zh_cn tree (#754 seed, #759 v0.458.0 drop), its review (#770), and the v0.516.0 revision pass (2026-07) that took zh_cn to zero AI-unreviewed lines (reopened by the v0.528.0 machine wave — see [Backfill notes](#backfill-notes-july-2026)) |
| `阿世xAsh` | human | Community translator — the #823 zh_cn/zh_tw Support-page revision pass |

To use a new model or translator name, add it to `authors.json` first —
`stamp-provenance.py` refuses unregistered names, so the registry can't drift behind
the sidecars. Name future models `<Model> (Claude)` etc., and humans by their
preferred credited name.

### Renames

A translator can change the name they are credited under from the in-game **Credits** page (an
**Edit** button appears beside any name they have submitted under). The relay rewrites their rows
on the spot and logs the rename; the repo catches up on the next translation import, where
`apply-translator-renames.py` runs *before* `import-approved-translations.py` and renames the
`authors.json` key (keeping the object form and `url`) and every `author`/`reviewer` string in
the sidecars, after which `stamp-provenance.py --sync` regenerates the shipped credits. It will
not merge onto a name already in `authors.json`, and cannot rename a name the repo never credited;
both are reported in the import PR's body for a person to resolve. Translators who delivered a zip
rather than using the editor have no relay rows and are renamed by hand.

### Opt-outs — `"credit": false`

The same **Edit** button offers **Remove**: the translator keeps their count but is listed as
*Anonymous* everywhere the relay names them (`credits.js` on the relay — reversible with
**Restore**). The jar's baked credits catch up on the same import: after the renames,
`apply-translator-renames.py` reads `GET /<ADMIN_CAP>/credits/optouts?section=translations` and
writes the object form with `"credit": false` for every registered name that uuid was credited
under (`{"kind": "human", "credit": false}`; a `url` is kept). The sidecars are **not** touched —
the work is still theirs and `check-provenance.py` still needs the name registered — but
`build_contributors` leaves the name out of `translation_contributors.json`. A restore drops the
flag on the next import (`"restored"` in the report), and the PR body lists both.

```bash
python3 scripts/localization/apply-translator-renames.py --dry-run          # what would change
python3 scripts/localization/apply-translator-renames.py --from-file r.json  # a saved log
```

## Sibling-mod namespaces

AdventureItemNames, PlayerMob and DiscordPresence are separate mods whose *source* repos are
English-only — their community translations are committed **here in DT** (under
`assets/<namespace>/lang/`), so their provenance lives here too, at
`provenance/<namespace>/<locale>.json`. These use the **same per-line schema, scripts and
author registry** as dungeontrain, with two differences:

- They have **no shipped credit files or in-game AI-ring** (bookkeeping only) — so no
  `total_keys`/`ai_authored`/`ai_unreviewed` counts and no `translation_contributors.json`.
- Their English is not in this repo, so every entry's `source_hash` is `""` and they never
  appear in a manifest's `source_changed` list — the in-game editor cannot tell a translator
  that a sibling's English moved on.
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
  "random_books/deathnote": {"author": "老本願", "reviewer": "老本願", "source_hash": "5d2e9a17c4b3f086"},
  "starting_books/questions": {"author": "Opus 4.8 (Claude)", "reviewer": "", "source_hash": "b7a0c3e6d1f24958"}
}
```

Same `{author, reviewer, source_hash}` semantics, same author registry, same on-disk format
and the same "restamp author resets reviewer" rule. A book's `source_hash` digests the English
book's **editable fields only** (`provenance_io.book_source_hash`, the same walk as
`NarrativeBookFields.flatten`), so a structural or whitespace-only edit to the English file
does not flag every locale. There is no `en_us` locale here (the English books
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

**Handing work to a translator** — build the review queue, one CSV per locale per body
(dungeontrain UI / sibling UI / narrative books), each row carrying the English, the current
translation, and empty `new_translation` + `verdict` columns to fill in:

```bash
python3 scripts/localization/build-review-package.py            # -> localization/review/ (gitignored)
python3 scripts/localization/apply-review-csv.py --reviewer 老本願 \
    localization/review/zh_cn/dungeontrain-ui.csv               # the return leg
```

The package flags two conditions, both straight from the sidecars. `needs_first_review` is
machine translation with an empty reviewer. **`source_changed_since_review`** is a line that
*is* human-reviewed, but whose `en_us` has been edited since — its `source_hash` no longer
matches the current English, so the review attests text that no longer exists. (The same
comparison the shipped manifests carry in-game; git history is consulted only to *date* the
`english_changed_at` / `reviewed_at` columns.) Proved by `test_build_review_package.py`.

`apply-review-csv.py` writes `verdict: fixed` rows into the lang file (translator becomes author
**and** reviewer) and stamps `verdict: ok` rows as reviewed with their original author kept — both
through `stamp-provenance.py`, so the registry and credit-count rules stay in one place.
Sibling namespaces can only report `needs_first_review`: their English lives in their own repos.

**Importing what players fixed in-game** — the other return leg. Translations submitted from the
in-game editor and approved on the explorer's `#/translations` page are served to players by the
relay, but they change nothing here: the shipped text, the sidecars and the generated Credits page
all stay as if the work never happened. `import-approved-translations.py` closes that loop.

```bash
export DUNGEONTRAIN_RELAY_ADMIN_BASE='https://…/<admin cap>'   # env only — it carries the cap
python3 scripts/localization/import-approved-translations.py --dry-run
python3 scripts/localization/import-approved-translations.py [--register-new]
```

It reads the approved queue per locale, writes each approved value into its lang file (or the
dotted field of its book), and stamps the translator through `stamp-provenance.py` /
`stamp-narrative-provenance.py` — so the shipped `translation_contributors.json` behind the
Credits page picks up their name *and* their per-language percentage on the next build. Run it
before cutting a release; review the `git diff`, which is the whole change.

Three refusals are the point of the script:

- **An unregistered name fails the run.** `--register-new` adds them as plain `"human"`; a
  translator who gave a profile URL still needs the object form written in by hand.
- **A stale approval is skipped**, not shipped: every submission stores the English it was
  translated from, and when `en_us` has changed since, the approval answers a question no longer
  being asked (the relay-side twin of `source_changed_since_review` above).
- **Nothing is reformatted.** Every write is a text-level edit of one value, so the blank-line
  grouping, the CRLF files and the untouched lines all survive.

**It also runs on its own.** `.github/workflows/import-approved-translations.yml` does the same
import every Monday (and on `workflow_dispatch`, with `dry_run` / `locale` inputs), reading the
admin base from the `RELAY_ADMIN_BASE` repo secret, and **opens a PR** rather than pushing —
player-written text going into the jar gets a human read, and a bot commit on main would re-anchor
the auto-release cascade. It runs without `--register-new` on purpose: an unregistered translator
fails the run, so somebody adds them with their profile URL instead of a bot flattening them to a
bare name. Note that `build.yml` doesn't fire on a PR opened by the default token, so the job runs
`check-provenance.py`, `check-narrative-provenance.py` and `validate-locale.py` itself first.

An approval whose submitter unticked "credit me" arrives with no name; those lines are stamped to
a registered **`Anonymous contributor`** rather than left reading as machine translation. A book
whose every editable field an import replaced takes the translator as its author; one where they
fixed a line or two records them as its reviewer, since narrative provenance is per book.

## Backfill notes (September 2026) — `source_hash`

`source_hash` was added to the schema on 2026-09-13 and seeded into every sidecar by
`scripts/localization/backfill-source-hash.py` (committed as the audit trail; one-off, like
the July backfill below). For each entry it hashed the English **as of the commit that last
changed that entry's author or reviewer** — i.e. what that stamp actually attested — rather
than today's English, so lines whose English had already moved on surfaced as stale instead
of being absorbed. Result at seeding: 82–108 stale lines per dungeontrain locale (all of them
AI-unreviewed — English rewrites that never got re-translated), 22–25 stale books per locale,
0 across the siblings (`""`, no English here). Entries with no sidecar history yet (a few
dozen in pl_pl/ro_ro/ru_ru/zh_*) fell back to today's English.

## Backfill notes (July 2026)

The initial sidecars were generated by `scripts/localization/backfill-provenance.py`
(committed as the audit trail; its commit map is closed — never rerun it after new
lang commits). Judgment calls baked into that backfill:

- **zh_cn reviewer coverage** originally came from the PR #770 `human_reviewed` flip: the
  806 keys that existed then (values verified unchanged since) carried `reviewer: 老本願`,
  leaving the keys machine-added afterwards (#809, #821) unreviewed at ~94.6%.
  **Closed by 老本願's v0.516.0 revision pass (2026-07)**, which revised 84 of those
  Opus-authored lines (becoming author *and* reviewer) and reviewed the remaining 26
  as-is (author stays `Opus 4.8 (Claude)`, reviewer 老本願). That took zh_cn to **0
  AI-unreviewed**, with the advisory WARNING firing for one line only —
  `support.subtitle`, see the next bullet — not for a machine-translation gap.
  **No longer true as of the v0.528.0 wave (2026-08):** ~103 machine-translated lines
  (`Opus 5 (Claude)`, unreviewed) put zh_cn at ~10% AI-unreviewed, so its
  `human_reviewed` flag was flipped back to **`false`** — the faded "AI" logo is showing on
  Simplified Chinese again. Flip it back when 老本願 has worked through the review queue.
- **`gui.dungeontrain.support.subtitle`** (zh_cn + zh_tw) is authored by 阿世xAsh but
  ships Claude's 力所能及 idiom fix "pending translator confirmation" (PR #823's own
  words), so it stays **unreviewed** until the translator confirms — at which point:
  `stamp-provenance.py --locale zh_cn --locale zh_tw --reviewer 阿世xAsh --keys gui.dungeontrain.support.subtitle`
- **The 3 `.modpack` sibling keys** were Claude-derived variants (模组→整合包) of
  阿世xAsh's revised copy that the translator never saw: credited `Opus 4.8 (Claude)`,
  unreviewed. Still true for **zh_tw**; for **zh_cn** they were rewritten from scratch in
  老本願's v0.516.0 pass and are now `老本願 / 老本願`.

### Sibling namespaces + narrative books (July 2026)

The sibling-namespace sidecars (`provenance/{adventureitemnames,playermob,discordpresence}/`)
and the narrative sidecars (`narrative_provenance/`) were first backfilled with a plain
`stamp-*-provenance.py --sync --author 'Opus 4.8 (Claude)'` — every entry credited to Opus and
unreviewed, the conservative default. These bodies have no closed commit-map backfill script —
a fresh `--sync` is the correct way to pick up new siblings/books.

**`zh_cn` correction (2026-07-26).** That blanket default was wrong for Chinese. Revision
history shows the `zh_cn` **sibling UI** (AdventureItemNames, PlayerMob) and **narrative books**
are 老本願's community translation, not machine output:

- Both sibling `zh_cn` lang files were introduced solely by **#755** (`01e4ad2a`, "auto-load
  Chinese"), bundled from 老本願's `dev/chinese-localization-autoload` branch under the
  "Localized by 老本願" credit — genuine human translation, not en_us copies.
- The 45 `zh_cn` narrative books trace to **#759** (`d229aa73`, 老本願's v0.458.0 drop, 43
  books) and #755 (2 books). `random_books/deathnote`'s prose is 老本願's; its one-line title
  field (死亡笔记) was appended by #766 — a trivial term, so the book is credited 老本願.
- The earlier "#755 = assumed Opus" call applied only to the scattered **dungeontrain UI draft
  keys** ("draft — confirm with 老本願"), never to this sibling/narrative bundle.
  DiscordPresence's `zh_cn` was *removed* at #755 (DP self-localizes), so it has no sidecar.

Operator decision (confirmed 2026-07-26): those `zh_cn` entries are **author 老本願, reviewer
老本願** (translated and reviewed by 老本願). Re-stamped with
`stamp-*-provenance.py --namespace … --locale zh_cn --author 老本願 --reviewer 老本願 --all`.
All **other** locales (de/fr/ja/zh_tw/…) came from the Opus AI waves (#768/#776/#766) and
correctly remain `Opus / unreviewed`.
