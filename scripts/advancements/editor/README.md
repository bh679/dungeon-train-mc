# Advancement editor

Edit Dungeon Train's advancement tabs on a copy of the real advancements screen: the vanilla window,
tab sprites and item icons, read straight out of the game jars. Click an advancement to change its
**parent**, **tab**, **required value**, **icon** (its search box looks through every item in the game), and whether it **counts towards the Everything
Burrito** and is **reset by It's Not That Simple**. On a tab's first advancement you can also change
the **tab name**, **background** (any block texture, via **+**), **tab order** and what it is
**unlocked by**. **New tab** makes a tab and opens it with those fields first. You can also drag an
advancement onto another one, or onto a tab. **Full screen** fills the window with the advancements
screen, tabs included; Esc leaves it.

```bash
python3 scripts/advancements/editor/serve.py        # → http://127.0.0.1:8833
```

The page opens on the layout the repo ships right now. Edits are kept in the browser until you press
**Save to repo**, which writes them into the working tree. Review with `git diff` and commit it
yourself. It binds to `127.0.0.1` only and refuses cross-origin writes.

Needs Pillow (`pip install pillow`) for the icons, and a workspace that has built once, so
`~/.gradle/caches` holds the Minecraft client jar and the Exposure / Edible Backpacks jars.

## What a save writes

| Edit | Where it lands |
|---|---|
| Parent / tab | the advancement's `"parent"` line in `data/dungeontrain/advancement/**.json` |
| Required value | the `threshold*` number on its criterion |
| Icon | `display.icon.id` (the page stores picks as item ids) |
| Background | the tab root's `display.background` |
| Tab name, tab order, tab copies | `src/main/resources/dungeontrain/advancement_tabs.json` (+ an `en_us` key for a name) |
| "Open a copy as a new tab" | a new hidden, silent copy advancement, listed under `copies` |
| Unlocked by | `advancement_tabs.json` → `unlockedBy` (tab head → the advancement that unlocks it) |
| Visibility | `advancement_tabs.json` → `visibility` (`parent` / `always` / `earned`), only where it differs from the default |
| Everything Burrito / It's Not That Simple | `advancement_tabs.json` → `burrito` / `startAgainReset`, only where it differs from the default |

The default for both comes from the mod (`CompletionistAdvancement.isRequiredId`; Start Again resets what
the burrito needs). `capstone_rules.py` mirrors it for the page, and a golden list
(`src/test/resources/advancement/burrito_required.txt`) checked by both a Java and a Python test keeps the
two in step. After changing the rule, run `test_apply.py --regen-golden`.

**Ids never change.** A move only rewrites `parent`, so players keep everything they earned. Edits to
existing files are text-level, so each change is one line in the diff.

## Tab copies

An advancement has one parent, so a tab headed by an advancement that also appears in another tab
(Dungeon Train Explorer → Train Explorer, Others? → Others) is two advancements: the original, earned
by play, and a hidden copy with a `minecraft:impossible` criterion. `TabGateways` earns the copy when
the original is earned, and re-syncs on login. The copy being hidden is what keeps its tab locked
until then. Copies never count towards the Everything Burrito.

## Visibility

Each advancement has a **Visibility**: *hidden until parent* (the default: shown once it or its
parent is earned), *always visible* while its parent is (a whole branch shows with its head), or
*hidden until earned*. A tab's first advancement defaults to hidden-until-earned when its JSON says
hidden, otherwise always. `AdvancementVisibilityRule` applies it in game. **Progress: Not earned**
fades what a player who has earned nothing would not see.

## Unlocked tabs

A tab head can instead be **unlocked by** another advancement and keep its own name and icon (Challenges
is unlocked by Dungeon Train Explorer). `TabGateways` treats it like a copy: earned with its source,
re-synced at login, never counted on its own. A new tab's id is named after the tab when you save
(`dungeon_train/tab_challenges`); after that it never changes.

## Things a save cannot finish

`apply.py` lists these as "todo" after a save:

- **New text** (a new tab name, or a brand-new advancement's title) is written in English only.
  Translate it with `scripts/localization/merge-locale-keys.py`, then stamp provenance. CI's
  localization checks fail until every locale has the key.
- **A brand-new advancement that is neither a copy nor unlocked by another** is created with an
  `impossible` criterion. Give it a real trigger (or pick **Unlocked by** for a tab head).
- **The band journey** (`reached_*` and friends) is re-parented in band order at load. Only the first
  band's parent (`reached_nether`) comes from its JSON, so moving that one moves the whole chain.

## Other ways in

```bash
python3 scripts/advancements/editor/build.py --artifact out.html   # one self-contained page to share
python3 scripts/advancements/editor/apply.py changes.json          # apply a "Copy changes" export
python3 scripts/advancements/editor/test_apply.py                  # tests (stdlib only; runs in CI)
```

A shared page saves edits to its own database rather than the repo. Its **Copy changes** button
gives the JSON that `apply.py` takes.

| File | Role |
|---|---|
| `serve.py` | local server: rebuilds the page per load, `POST /api/apply` saves |
| `build.py` | reads the working tree and jars into the page's data bundle |
| `textures.py` | item icons (isometric blocks included) and backgrounds from the jars |
| `apply.py` | writes a change set into the repo |
| `web/` | the page: `index.html`, `editor.css`, `js/{model,view,menu,panels}.js` |
