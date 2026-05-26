# Auto-Release Cascade

After every real release of Dungeon Train, a tapering cascade of ~22 micro-releases
fires over the following 14 days. Each tick prefers, in order:

1. **AIN dependency catch-up** — if `adventureitemnames_version` in `gradle.properties`
   is behind the latest [Adventure Item Names](https://github.com/bh679/adventureitemnames-mc/releases)
   release, bump it by **one** version step. The workflow runs `./gradlew build` to
   verify; on build failure the bump is reverted and the cascade falls through.
2. **Queue item** — pop `pending[0]` from `queue.json` and apply by type (`new_file` or
   `add_variant`).
3. **Mode fallback** — depends on the cascade mode (see [Cascade modes](#cascade-modes)).
   In `always` mode this is the auto-balancing loot weight nudge
   (`src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json`); in
   `with-content` and `ain` modes the cascade stops until the next real release.

The cascade's purpose is to keep the mod fresh on Modrinth/CurseForge "recently updated"
feeds, exercise the release pipeline regularly, and roll AIN updates out incrementally.

## Cadence

Anchored on the timestamp of the last real release:

| Phase | Window since anchor | Interval | Approx. fires |
|---|---|---|---|
| A — hourly | 0–4h | 1h | 4 |
| B — every 5h | 4–28h | 5h | 5 |
| C — daily | 28h–14d | 24h | 13 |
| stopped | > 14d | — | — |

Each fire bumps **PATCH only** (`0.193.0` → `0.193.1` → `0.193.2`…). A new real release
resets the anchor and starts the cascade over.

## Files

- `queue.json` — `{ pending: [...], applied: [...] }`. The workflow pops `pending[0]`,
  applies it, and appends the popped id to `applied[]`.
- `state.json` — `{ schedule_anchor, last_auto_release_at, last_anchor_source, cascade_stopped }`.
  The source of truth for cascade timing. `cascade_stopped: true` short-circuits
  `should-fire.py` and is cleared by `auto-release-reset.yml` on the next real release.
- `schema/queue-item.schema.json` — JSON Schema for queue items.

## Adding a queue item

Edit `queue.json` and add an object to `pending[]`. Each item must match
`schema/queue-item.schema.json`. Two types are supported:

| `type` | Effect | Use for |
|---|---|---|
| `new_file` | Writes `content` as a brand-new file at `target`. Refuses to overwrite. | One-shot additions: a single random_book, a single starting_book, a one-letter narrative. |
| `add_variant` | Appends `variant` to `letters[<letter.index>].variants[]` in the story file at `target`. Creates the file and/or letter if missing. | Multi-letter character stories — schedule one variant per cascade fire so the story file grows visibly across releases. |

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
| `always` | Fire every cadence tick. Priority: AIN bump → queue item → auto-balance loot nudge. Same behaviour as before this mode selector existed. |
| `with-content` | Fire only when there is something to ship. Priority: AIN bump → queue item → **stop**. When the cascade has nothing to do it sets `state.cascade_stopped=true` and waits for the next real release. |
| `ain` | Fire only when AIN can be bumped. Priority: AIN bump → **stop**. Queue items are ignored — push them via the next real release if you want them to ship. |
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

## AIN dependency catch-up

DT bundles [Adventure Item Names](https://github.com/bh679/adventureitemnames-mc) via
NeoForge jarJar — the resolved version comes from `adventureitemnames_version` in
`gradle.properties`. AIN ships its own cascade and outpaces DT, so each DT auto-tick
checks whether DT's `adventureitemnames_version` is behind the latest AIN release. If
so:

1. `apply-change.py` reads `gradle.properties`, runs `gh release list --repo bh679/adventureitemnames-mc`,
   finds the **smallest** semver greater than current (always one step, never a leap),
   and rewrites the line.
2. The workflow runs `./gradlew build` to verify the new AIN jar resolves and DT still
   compiles. The build cache from `gradle/actions/setup-gradle@v3` keeps this fast on
   repeat ticks.
3. On build success, the cascade commits the bump alongside the PATCH bump and
   dispatches `release.yml`. Commit message: `chore(auto): bump AIN <old> -> <new>`.
4. On build failure, the workflow runs `git checkout -- gradle.properties .github/auto-release/state.json`
   to revert and re-runs `apply-change.py` with `SKIP_AIN=1` so the tick falls through
   to its mode-dependent fallback.

Failures (network blips, AIN repo unreachable, malformed tag) are treated as
"no bump available" — they are logged as warnings and the cascade falls through
without erroring.

## Cancelling on real release

`auto-release-reset.yml` fires on every successful `Release` workflow run whose `auto`
input is `false`. It rewrites `state.json` with the new anchor.

## How a real release is distinguished from an auto release

`release.yml` accepts an `auto: bool` workflow_dispatch input (default `false`). The
auto-release workflow always dispatches with `auto=true`. Manual releases (via
`gh workflow run release.yml -f tag=v...`) default to `auto=false`. The reset
workflow inspects this input.
