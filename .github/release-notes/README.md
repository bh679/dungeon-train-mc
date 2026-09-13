# Changelog Ledger

`changelog.json` is a curated, per-version record of what changed in Dungeon Train. The agent
appends one entry per **Gate 3 merge** (when it best understands what it just built); at release
time the agent renders **all unreleased entries** (everything since the last real release),
presents them for confirmation, and passes them to `release.yml` as the release notes. The
release workflow then marks the shipped entries released.

This replaces leaning on GitHub's raw `generate-notes` commit diff for player-facing notes — the
ledger captures intent at the moment a feature ships.

## Flow

```
Gate 3 merge (agent, on the feature branch)
   └─ scripts/release-notes/append-entry.py
        appends a curated entry to changelog.json (released:false, version = post-merge mod_version)
        → lands in the PR diff, reviewed at the gate, squash-merged atomically

Release (agent → CLAUDE.md "Releasing")
   ├─ scripts/release-notes/render-unreleased.py   → Markdown of every released:false entry
   ├─ agent shows it to the user for confirmation
   └─ gh workflow run release.yml -f tag=vX.Y.0 -f changelog="<rendered notes>"

release.yml (CI, on success, only when auto == false)
   └─ scripts/release-notes/mark-released.py --released-in vX.Y.0
        flips released:true + stamps released_in/released_at
        → commits "chore(release-notes): mark vX.Y.0 released [skip ci]" to main
```

## The `released` flag is the boundary (not version numbers)

Inclusion in a release is governed by the per-entry **`released`** flag, never by comparing
version numbers. Back-to-back Gate-3 merges can share one `X.Y.0` (`version-bump.yml` skips the
bump when PATCH==0), so a version-comparison boundary would wrongly treat a feature merged right
after a release as already-released. The flag is unambiguous. The `version` field is descriptive
metadata and drives grouping in the rendered notes.

## Cascade interaction

The auto-release cascade dispatches `release.yml` with `auto=true`. The mark step is gated on
`auto == false`, so the ~22 cascade micro-releases never consume the curated entries —
"unreleased" therefore means **since the last *real* (operator-dispatched) release**.

## Entry shape

```json
{
  "entries": [
    {
      "id": "toolsmith-shop-carriage",
      "version": "0.291.0",
      "type": "feat",
      "tags": ["feature", "train", "loot", "balance"],
      "title": "Toolsmith shop carriage + armorer loot rebalance",
      "summary": "A toolsmith shop carriage now rides the train, trading tools…",
      "highlights": ["New toolsmith shop carriage", "Rebalanced armorer chest loot"],
      "pr": 360,
      "date": "2026-06-14",
      "released": false,
      "released_in": null,
      "released_at": null
    }
  ]
}
```

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Unique slug `^[a-z0-9][a-z0-9_-]*$`. Duplicate ids are refused. |
| `version` | yes | `X.Y.Z` the merge ships as (computed = same rule as `version-bump.yml`). Descriptive only. |
| `type` | yes | One of `feat, fix, content, perf, refactor, chore, docs, ci, test`. |
| `tags` | yes | Player-facing classification the in-game Versions page filters by, chosen by the agent from context (see [Tags](#tags)). The type-derived tag is always present; may be `[]` only for chore/ci/refactor/docs/test. |
| `title` | yes | Short headline. |
| `summary` | yes | Player-facing prose. |
| `highlights` | no | Bullet points. |
| `pr` | no | PR number. |
| `date` | yes | UTC `YYYY-MM-DD` set at append time. |
| `released` | yes | `false` until shipped; flipped by `release.yml`. |
| `released_in` / `released_at` | no | Stamped on release (tag + UTC timestamp). |

Validated by `schema/changelog.schema.json` (JSON Schema 2020-12).

## Tags

The in-game Versions page fetches this ledger (raw from `main`) and lets players filter the
notes they've missed by tag. Every entry carries the tag its `type` implies plus any topical tags
that apply — an editor bug fix is `["fix", "editor"]`. Ids are stored in the canonical order below.

| id | Label in game | When |
|---|---|---|
| `feature` | New Feature | automatic for `type: feat` |
| `content` | New Content | automatic for `type: content` — carriages, rooms, books, mobs, narrative |
| `fix` | Bug Fix | automatic for `type: fix` |
| `performance` | Performance | automatic for `type: perf`; also lag/stutter/memory fixes |
| `editor` | Editor | builder, templates, stages, X menu |
| `multiplayer` | Multiplayer | behaviour specific to servers / other players |
| `community` | Community | relay-backed: shared carriages & books, leaderboards, credits, videos, chat, Discord |
| `translations` | Translations | locale additions, i18n fixes |
| `compatibility` | Compatibility | shaders, Distant Horizons, modpack, companion mods, Sable |
| `train` | Train | carriages, flatbeds, tracks, dimensional carriages |
| `world` | World | worldgen, biomes, Nether/End bands, terrain |
| `mobs` | Mobs | echoes, villagers, hostiles, spawning |
| `loot` | Loot | chests, potions, gear, trades |
| `books` | Books | playerbooks, letters, lecterns, narrative |
| `advancements` | Advancements | |
| `ui` | Menus & UI | menu pages, HUD, death screen, screens |
| `balance` | Balance | difficulty, loot/spawn weights, rebalances |

The list lives in three places that must agree: `changelog_io.VALID_TAGS`, the schema enum, and
the client's `ChangelogTag` enum (`client/version/compare/ChangelogTag.java`, with a lang key per
tag).

### Who picks the tags

**The agent, while it writes the entry — from the diff and intent it holds at Gate 3.** There is
no keyword classifier: the 792 entries that predate tags were first classified by a regex pass and
it tagged 50 entries `multiplayer` of which ~5 were about multiplayer, because the ledger's prose
narrates around topics constantly ("the books other players wrote", "most noticeable on
multiplayer servers", "server owners can set this in the config"). That pass has since been
replaced by a contextual re-tag and the script deleted. A tag says what a change is **about**, not
what its summary mentions.

`append-entry.py` refuses to run without a tag decision: pass `--tag` for each topical tag that
applies, or `--no-topical-tags` to state that only the type-derived tag does. Answer one question
per tag (`append-entry.py --tag-guide` prints them; source: `changelog_io.TAG_GUIDE`):

| tag | Is this change about … |
|---|---|
| `performance` | making the game faster, smoother, or lighter on memory? (automatic for `perf`) |
| `editor` | the builder / editor — templates, stages, parts, the X menu, how builds are authored? |
| `multiplayer` | behaviour that only exists with other players on a server — sync, joining, per-player state, server-side rules? |
| `community` | relay-backed shared content — shared carriages & books, leaderboards, credits, videos, menu chat, Discord? |
| `translations` | a locale addition or an i18n fix? |
| `compatibility` | coexisting with something external — shaders, Distant Horizons, Sable, companion mods, the modpack, NeoForge/MC? |
| `train` | the train itself — carriages, flatbeds, tracks, the engine, dimensional carriages, how it moves or is assembled? |
| `world` | the world around the train — worldgen, biomes, bands, terrain, tunnels and bridges? |
| `mobs` | creatures — echoes, villagers, hostiles, animals, spawning, AI, drops? |
| `loot` | what players find or trade — chests, potions, gear, enchantments, shops, brushable loot? |
| `books` | written content — playerbooks, letters, lecterns, narrative books, the Death Note? |
| `advancements` | advancements / achievements — new ones, criteria, hints, the screen? |
| `ui` | a menu, screen, HUD element, popup, tooltip, or keybind the player interacts with? |
| `balance` | difficulty, weights, rates, or numbers that make the game harder or easier? |

Two to four tags is typical. A perf fix to worldgen is `performance` + `world`; a new echo mob is
`content` + `mobs`; a Versions-page filter is `feature` + `ui`. Skip a tag when the change merely
*touches* that area on the way to its real subject.

To correct tags on entries already in the ledger, write a reviewed `{id: [topical tags]}` JSON
mapping and apply it with `set-tags.py` (`--dry-run` reports before → after counts,
`--show-changes` lists each move). The type-derived tag is re-derived; the mapping only carries
topical tags.

## Scripts

| Script | When | What |
|---|---|---|
| `append-entry.py` | Gate 3 (agent) | Append one curated entry. Computes `version` from `gradle.properties` + `date`; a tag decision is mandatory — `--tag` (repeatable) or `--no-topical-tags`; `--tag-guide` prints the questions. |
| `set-tags.py` | Correction pass | Replace the topical tags of listed entries from a reviewed `{id: [tags]}` mapping. `--dry-run` reports, `--show-changes` lists moves, `--report` prints current counts. |
| `render-unreleased.py` | Release (agent) | Print Markdown of all `released:false` entries, grouped by version (newest first). Empty output when nothing is unreleased. |
| `mark-released.py` | Release (CI) | Flip every `released:false` entry to released; stamp the tag + timestamp. |

```bash
# Gate 3 — log what was built (run on the feature branch before merging):
python3 scripts/release-notes/append-entry.py \
  --id toolsmith-shop-carriage --type feat \
  --title "Toolsmith shop carriage + armorer loot rebalance" \
  --summary "A toolsmith shop carriage now rides the train…" \
  --highlight "New toolsmith shop carriage" \
  --highlight "Rebalanced armorer chest loot" \
  --tag train --tag loot --tag balance \
  --pr 360

# Release — preview the notes the user will confirm:
python3 scripts/release-notes/render-unreleased.py
```

Path env overrides (used by the tests): `CHANGELOG_FILE`, `GRADLE_PROPERTIES_FILE`.

Tests: `python3 scripts/release-notes/test_release_notes.py` (stdlib + optional `jsonschema`;
local-only, not CI-gated — matching the auto-release test convention).
