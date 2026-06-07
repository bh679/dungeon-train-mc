# Auto-Release Cascade

After every real release of Dungeon Train, a tapering cascade of ~22 micro-releases
fires over the following 14 days. Each tick prefers, in order:

1. **Sibling-mod dependency catch-up** — for each sibling mod in declared order
   (**AIN**, then **AIS**, then **PMOB** — see [Sibling-mod dependency catch-up](#sibling-mod-dependency-catch-up)),
   if its `*_version` in `gradle.properties` is behind the sibling's latest GitHub
   release, bump it by **one** version step. First sibling behind wins the tick. The
   workflow runs `./gradlew build` to verify; on build failure the bump is reverted
   and the cascade falls through to the next sibling.
2. **Queue item** — pop `pending[0]` from `queue.json` and apply by type (`new_file` or
   `add_variant`).
3. **Mode fallback** — depends on the cascade mode (see [Cascade modes](#cascade-modes)).
   In `always` mode this is the auto-balancing loot weight nudge
   (`src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json`); in
   `with-content`, `ain`, and `ais` modes the cascade stops until the next real release.

The cascade's purpose is to keep the mod fresh on Modrinth/CurseForge "recently updated"
feeds, exercise the release pipeline regularly, and roll sibling-mod updates out
incrementally.

## Cadence

### Default cadence

Anchored on the timestamp of the last real release. These are the defaults
when the `AUTO_RELEASE_CADENCE` repo variable is unset — see
[Customising cadence](#customising-cadence) to override.

| Phase | Interval | Fires | Cumulative window |
|---|---|---|---|
| A — hourly   | 1h  | 4  | 0–4h    |
| B — every 5h | 5h  | 5  | 4–29h   |
| C — daily    | 24h | 13 | 29–341h (~14.2d) |
| stopped      | —   | —  | > 341h  |

Each fire bumps **PATCH only** (`0.193.0` → `0.193.1` → `0.193.2`…). A new real
release resets the anchor and starts the cascade over.

### Customising cadence

Set the repo variable `AUTO_RELEASE_CADENCE` to a JSON object describing the
tier shape:

```bash
gh variable set AUTO_RELEASE_CADENCE --body '{
  "tiers": [
    {"name": "A", "interval_minutes": 60,   "count": 4},
    {"name": "B", "interval_minutes": 300,  "count": 5},
    {"name": "C", "interval_minutes": 1440, "count": 13}
  ]
}'
```

Or via the UI: **Settings → Secrets and variables → Actions → Variables tab**.

Per-tier fields:

| Field | Type | Notes |
|---|---|---|
| `name` | string | Surfaced as the `phase=` label in workflow logs. |
| `interval_minutes` | integer ≥ 1 | Minimum gap between fires inside this tier. |
| `count` | integer ≥ 1 | Number of fires expected at this cadence. |

Two values are **derived** from the tier list rather than configured separately:

- **Phase upper bound (hours)** — running cumulative sum of
  `count × interval_minutes / 60` up to and including each tier. The last
  tier's upper bound also serves as the cascade **stopped cap** (default
  ~14.2d).
- **Sibling-pending override interval** — `tiers[0].interval_minutes × 60`
  seconds. The first tier's interval is what AIN/AIS/PMOB pending updates pin to.

**Fallback to defaults:** unset, malformed JSON, or any schema violation
(empty `tiers`, missing field, `count < 1`, non-integer `interval_minutes`)
falls back to the defaults table above and emits a `::warning::` annotation
in the Actions log. To restore defaults from the CLI:

```bash
gh variable delete AUTO_RELEASE_CADENCE
```

### Sibling-pending override

While AIN, AIS, or PMOB has a GitHub release that DT is behind on, the cascade
pins to **phase A (the first tier's interval — 1h by default)** regardless of
elapsed time since the anchor — siblings ship updates fast, and we want them in
the bundled jar without sitting in B/C cadence for hours. The override is filtered
by [cascade mode](#cascade-modes): in `mode=ain` only AIN-pending pins to the
first tier, in `mode=ais` only AIS, in `always` and `with-content` any of the
three siblings triggers it.

The **stopped cap still wins** — past the cumulative window (default ~14.2d)
the cascade stops even if siblings remain behind. The next real release
re-anchors the schedule and the override resumes governing cadence.

Transient failures (network, malformed release, missing `gradle.properties`)
fall through to time-based phasing — the cascade is never falsely pinned to
the first tier on a flaky `gh release list` call.

## Files

- `queue.json` — `{ pending: [...], applied: [...] }`. The workflow pops `pending[0]`,
  applies it, and appends the popped id to `applied[]`.
- `state.json` — `{ schedule_anchor, last_auto_release_at, last_anchor_source, cascade_stopped }`.
  The source of truth for cascade timing. `cascade_stopped: true` short-circuits
  `should-fire.py` and is cleared by `auto-release-reset.yml` on the next real release.
- `schema/queue-item.schema.json` — JSON Schema for queue items.

## Adding a queue item

Edit `queue.json` and add an object to `pending[]`. Each item must match
`schema/queue-item.schema.json`. Three types are supported:

| `type` | Effect | Use for |
|---|---|---|
| `new_file` | Writes `content` as a brand-new file at `target`. Refuses to overwrite. | One-shot additions: a single random_book, a single starting_book, a one-letter narrative. |
| `add_variant` | Appends `variant` to `letters[<letter.index>].variants[]` in the story file at `target`. Creates the file and/or letter if missing. | Multi-letter character stories — schedule one variant per cascade fire so the story file grows visibly across releases. |
| `add_random_book_variant` | Appends `variant` to the top-level `variants[]` array in the random_book file at `target`. Creates the file from `random_book_meta` if missing. | Random books (flat `variants[]` arrays) — schedule one variant per cascade fire so the book grows visibly across releases. |

### `new_file` example

```json
{
  "id": "musings-2",
  "label": "Add random book: Musings of Faulthurst II",
  "type": "new_file",
  "target": "src/main/resources/data/dungeontrain/narratives/random_books/musings_of_faulthurst_ii.json",
  "content": {
    "id": "musings_of_faulthurst_ii",
    "title": "Musings of Faulthurst II",
    "author": "Faulthurst",
    "generation": 0,
    "weight": 3,
    "variants": ["..."]
  },
  "commit_message": "content(narrative): add random book 'Musings of Faulthurst II'"
}
```

`apply-change.py` always prepends `chore(auto): ` to your `commit_message` and appends
`(auto v<version>) [skip ci]`. The final commit reads:

```
chore(auto): content(narrative): add random book 'Musings of Faulthurst II' (auto v0.193.4) [skip ci]
```

The `chore(auto):` prefix is what `version-bump.yml` watches for to avoid MINOR-bumping
on top of an auto-release commit.

### `add_variant` example

```json
{
  "id": "pip-l1-a",
  "label": "Pip - Letter 1 variant A (the BBC-accent rat)",
  "type": "add_variant",
  "target": "src/main/resources/data/dungeontrain/narratives/stories/pip_aaro_the_waiting_child.json",
  "story_meta": {
    "id": "pip_aaro_the_waiting_child",
    "character": "Pip Aaro",
    "story": "The Waiting Child"
  },
  "letter": {
    "index": 1,
    "label": "Letter One"
  },
  "variant": "My name is Pip. I am eight.\n\n...\n\nPip",
  "commit_message": "content(narrative): Pip Letter 1 variant A"
}
```

Each variant of a multi-letter story becomes its own queue item, so the story file grows
one variant per cascade fire. Safety: `story_meta` and the letter `label` must match the
existing file once it has been created — divergence is a hard fail.

### `add_random_book_variant` example

```json
{
  "id": "field-notes-a",
  "label": "Add field-notes variant A (chest tips)",
  "type": "add_random_book_variant",
  "target": "src/main/resources/data/dungeontrain/narratives/random_books/adventurers_field_notes.json",
  "random_book_meta": {
    "id": "adventurers_field_notes",
    "title": "Adventurer's Field Notes",
    "author": "Anonymous Adventurer",
    "generation": 0,
    "weight": 1
  },
  "variant": "If you're reading this, you found my chest. Take what's useful.\n\n...",
  "commit_message": "content(narrative): field notes variant A"
}
```

Random books use a flat top-level `variants[]` array (no letters). The first queue item
for a given random_book file creates the file from `random_book_meta` (with defaults
`generation=0`, `weight=1` if omitted); subsequent items append to `variants[]`. Safety:
`random_book_meta.id`, `title`, and `author` must match the existing file once it has
been created — divergence is a hard fail.

Open a normal PR with the queue addition. The cascade will pick it up on the next fire.

## Cascade modes

The repo variable `AUTO_RELEASE_ENABLED` doubles as a mode selector. Set it from the
CLI:

```bash
gh variable set AUTO_RELEASE_ENABLED --body always         # current default
gh variable set AUTO_RELEASE_ENABLED --body with-content   # quiet between drops
gh variable set AUTO_RELEASE_ENABLED --body ain            # only catch AIN up
gh variable set AUTO_RELEASE_ENABLED --body false          # hard kill
gh variable delete AUTO_RELEASE_ENABLED                    # back to default (always)
```

Or via the UI: **Settings → Secrets and variables → Actions → Variables tab**.

| Value | Behaviour |
|---|---|
| `false` | **Hard kill.** The cascade job is skipped entirely (scheduled and dispatch). A `disabled-notice` job emits an annotation so the paused state is visible in the Actions UI. |
| `always` | Fire every cadence tick. Priority: AIN bump → AIS bump → PMOB bump → queue item → auto-balance loot nudge. |
| `with-content` | Fire only when there is something to ship. Priority: AIN bump → AIS bump → PMOB bump → queue item → **stop**. When the cascade has nothing to do it sets `state.cascade_stopped=true` and waits for the next real release. |
| `ain` | Fire only when AIN can be bumped. Priority: AIN bump → **stop**. AIS, PMOB, and queue items are ignored. |
| `ais` | Fire only when AIS can be bumped. Priority: AIS bump → **stop**. AIN, PMOB, and queue items are ignored. |
| unset / `true` / anything else | Treated as `always` (back-compat default). |

Parsing is case-insensitive; `false` is matched literally so the job-level guard still
disables the workflow correctly.

This only controls the **cascade**. Real releases via
`gh workflow run release.yml -f tag=v...` are unaffected.

### Stopped cascade

When a tick in `with-content` or `ain` mode finds nothing to apply, it commits
`state.json` with `cascade_stopped: true` and exits without dispatching a release.
`should-fire.py` then refuses to fire on every subsequent tick. The next manual
release runs `auto-release-reset.yml`, which rewrites `state.json` (re-anchoring the
schedule and clearing the flag), and the cascade resumes.

### Other ways to pause

- **Disable the workflow entirely** via Actions → Auto-Release Cascade → ⋯ →
  Disable workflow. Coarser than the kill switch — also stops manual dispatches from
  even running — but useful if you want the workflow gone from the UI.
- **Skip a single tick** by leaving `queue.json.pending` empty and accepting the
  auto-balancing default (in `always` mode), or by temporarily setting
  `state.json.last_auto_release_at` to a recent timestamp so `should-fire.py` decides
  not enough time has elapsed.

## Sibling-mod dependency catch-up

DT bundles three sibling mods via NeoForge jarJar — their resolved versions come from
`adventureitemnames_version`, `adventureitemstats_version`, and `playermob_version`
in `gradle.properties`:

- **AIN** — [Adventure Item Names](https://github.com/bh679/adventureitemnames-mc)
- **AIS** — [Adventure Item Stats](https://github.com/bh679/adventureitemstats-mc)
- **PMOB** — [PlayerMob / Interactive Player Mobs](https://github.com/bh679/playermob-mc)

Each sibling ships its own cascade and tends to outpace DT, so each DT auto-tick
checks them **in order** (AIN first, then AIS, then PMOB). The first sibling with a
newer GitHub release wins the tick; the rest are deferred to the next tick. If none
is behind, the cascade falls through to a queue item or mode-dependent fallback.

For the picked sibling:

1. `apply-change.py` reads `gradle.properties`, runs `gh release list --repo bh679/<sibling>-mc`,
   finds the **smallest** semver greater than current (always one step, never a leap),
   and rewrites that sibling's version line.
2. The workflow runs `./gradlew build` to verify the new jar resolves and DT still
   compiles. The build cache from `gradle/actions/setup-gradle@v3` keeps this fast on
   repeat ticks.
3. On build success, the cascade commits the bump alongside the PATCH bump and
   dispatches `release.yml`. Commit message: `chore(auto): bump AIN <old> -> <new>`,
   `chore(auto): bump AIS <old> -> <new>`, or `chore(auto): bump PMOB <old> -> <new>`.
4. On build failure, the workflow runs `git checkout -- gradle.properties .github/auto-release/state.json`
   to revert and re-runs `apply-change.py` with the failing sibling's `SKIP_<NAME>=1`
   (`SKIP_AIN=1`, `SKIP_AIS=1`, or `SKIP_PMOB=1`) so the tick falls through to the next
   sibling, the queue, or its mode-dependent fallback.

Failures (network blips, sibling repo unreachable, malformed tag) are treated as
"no bump available" — they are logged as warnings and the cascade falls through
without erroring.

To extend with another sibling mod in the future: add an entry to `SIBLING_MODS`
in [siblings.py](../../scripts/auto-release/siblings.py), add parallel
verify+fallback steps to [auto-release.yml](../workflows/auto-release.yml), and add
the version property + jarJar + mods.toml dep blocks (see how AIS/PMOB were added).

## Cancelling on real release

`auto-release-reset.yml` fires on every successful `Release` workflow run whose `auto`
input is `false`. It rewrites `state.json` with the new anchor.

## How a real release is distinguished from an auto release

`release.yml` accepts an `auto: bool` workflow_dispatch input (default `false`). The
auto-release workflow always dispatches with `auto=true`. Manual releases (via
`gh workflow run release.yml -f tag=v...`) default to `auto=false`. The reset
workflow inspects this input.
