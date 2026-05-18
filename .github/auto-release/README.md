# Auto-Release Cascade

After every real release of Dungeon Train, a tapering cascade of ~22 micro-releases
fires over the following 14 days. Each micro-release applies one purely-additive change
(from `queue.json`) or, when the queue is empty, ±1 to a sandbox loot table
(`src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json`) labelled
**auto-balancing**.

The cascade's purpose is to keep the mod fresh on Modrinth/CurseForge "recently updated"
feeds and to exercise the release pipeline regularly.

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
- `state.json` — `{ schedule_anchor, last_auto_release_at, last_anchor_source }`. The
  source of truth for cascade timing.
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

## Pausing the cascade

To pause: disable the `auto-release.yml` workflow in the GitHub Actions UI
(Actions → Auto-Release Cascade → ⋯ → Disable workflow).

To skip a single tick: leave `queue.json.pending` empty and accept the auto-balancing
default, or temporarily set `state.json.last_auto_release_at` to a recent timestamp so
`should-fire.sh` decides not enough time has elapsed.

## Cancelling on real release

`auto-release-reset.yml` fires on every successful `Release` workflow run whose `auto`
input is `false`. It rewrites `state.json` with the new anchor.

## How a real release is distinguished from an auto release

`release.yml` accepts an `auto: bool` workflow_dispatch input (default `false`). The
auto-release workflow always dispatches with `auto=true`. Manual releases (via
`gh workflow run release.yml -f tag=v...`) default to `auto=false`. The reset
workflow inspects this input.
