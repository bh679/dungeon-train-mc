# Advancement editor

Edit Dungeon Train's advancement tabs on a copy of the real advancements screen: the vanilla window,
tab sprites and item icons, read straight out of the game jars. Click an advancement and its menu shows it
the way the game does: icon, title, id, then its visibility, description, hint, tab, required value and
Everything Burrito line. **Click the part you want to change** and only that editor opens:

| Click | Changes |
|---|---|
| Icon | the icon (its search box looks through every item in the game) |
| Title, description or hint | the English text |
| The visibility line (*Hidden until earned*…) | when it shows |
| *Tab: …* | which tab it is in; on a tab's first advancement also the tab's **name**, **background** (any block texture, via **+**), **order** and what it is **unlocked by** |
| The id | its **parent** (or pick one on screen, make it a tab, or open a copy as a new tab) |
| *Required: …* | the value it needs |
| The Everything Burrito line | whether it **counts towards the Everything Burrito** and is **reset by It's Not That Simple** |

Clicking the **Everything Burrito**, or a tab-complete advancement (Dungeon Train Explored and friends),
outlines everything it needs that a player can see, and marks the tabs holding any of it. **Paint mode:** tick **Everything Burrito** or **It's Not That Simple** above the tabs, then click
advancements to put them in or out of it; those that are out are faded, and fixed ones (copies, the
capstone pair) are hatched. The **All in / All out** button on the tab does the whole tab at once. Untick
to go back to clicking for the menu. **Duplicate** (in an advancement's menu) makes a new advancement like it — same criteria, icon, text and
Everything Burrito settings, titled "… (copy)" — as its child; rename it and change its value for a next tier.
It is saved under an id taken from its title, unless you set one: a new advancement's id can be edited in the
parent editor (click its id) until it is saved; after that it never changes. **New tab** makes
a tab and opens it. You can also drag an advancement onto another one, or onto a tab. **Full screen** fills
the window with the advancements screen, tabs included; Esc leaves it.

```bash
python3 scripts/advancements/editor/serve.py        # → http://127.0.0.1:8833
```

The page opens on the layout the repo ships right now. Edits are kept in the browser until you press
**Save to repo**, which writes them into the working tree for you to review with `git diff`, or
**Save & commit**, which also regenerates the Everything Burrito golden list, runs `test_apply.py`, bumps
`mod_version`'s PATCH, commits only the files it wrote and pushes the branch (`commit.py`). It refuses on
`main`, and stops at "saved, not committed" when the save left text to translate. It binds to
`127.0.0.1` only and refuses cross-origin writes.

On the shared page there is no repo to reach: **Save & commit** saves the edits to the page's database
and sends a comment to the Claude session watching the page, which commits them and replies in the
thread. It is greyed out while no session is watching.

Needs Pillow (`pip install pillow`) for the icons, and a workspace that has built once, so
`~/.gradle/caches` holds the Minecraft client jar and the Exposure / Edible Backpacks jars.

## What a save writes

| Edit | Where it lands |
|---|---|
| Parent / tab | the advancement's `"parent"` line in `data/dungeontrain/advancement/**.json` |
| Required value | the `threshold*` number on its criterion |
| Icon | `display.icon.id` (the page stores picks as item ids) |
| Background | the tab root's `display.background` |
| Title / description / hint | `en_us.json`, at the advancement's own lang keys (other locales: see below) |
| Tab name, tab order, tab copies | `src/main/resources/dungeontrain/advancement_tabs.json` (+ an `en_us` key for a name) |
| "Open a copy as a new tab" | a new hidden, silent copy advancement, listed under `copies` |
| Unlocked by | `advancement_tabs.json` → `unlockedBy` (tab head → the advancement that unlocks it) |
| Visibility | `advancement_tabs.json` → `visibility` (`parent` / `always` / `earned`), only where it differs from the default |
| Everything Burrito / It's Not That Simple | `advancement_tabs.json` → `burrito` / `startAgainReset`, only where it differs from the default |

The default for both comes from the mod (`CompletionistAdvancement.isRequiredId`; Start Again resets what
the burrito needs): only advancements in the **Dungeon Train tab** count, so the other tabs count through
their tab-complete advancement there, and moving one between tabs changes its default live. `capstone_rules.py` mirrors it for the page, and a golden list
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

## Tab complete

Each player tab has a "tab complete" advancement (Dungeon Train Explored, All Others, Challenge Complete,
The Hero's Handbook, Fully Developed, All Out Of Secrets): earned by having every advancement in that tab,
it sits in Dungeon Train under the tab's entry, with a copy in the tab under its first advancement.
`advancement_tabs.json` → `complete` maps each to its tab; `TabCompleteAdvancements` grants them.

## Unlocked tabs

A tab head can instead be **unlocked by** another advancement and keep its own name and icon (Challenges
is unlocked by Dungeon Train Explorer). `TabGateways` treats it like a copy: earned with its source,
re-synced at login, never counted on its own. A new tab's id is named after the tab when you save
(`dungeon_train/tab_challenges`); after that it never changes.

## Things a save cannot finish

`apply.py` lists these as "todo" after a save:

- **Reworded text** is written in English only; the other locales keep the old wording until it is
  re-translated. The save lists the keys; re-translate them and restamp provenance
  (`stamp-provenance.py --sync`).
- **New text** (a new tab name, a new hint, or a brand-new advancement's title) is written in English only.
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
