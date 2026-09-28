# Book editor

A local authoring tool for the mod's three book corpora. The vanilla book page **is** the editor:
you type on the page you are looking at, with the same pagination, the same 256-char budget and the
same ~19×14 visible page the player gets, and what you save is the JSON the mod loads.

```bash
python3 scripts/books-editor/serve.py        # → http://127.0.0.1:8795
```

Stdlib only — no npm, no build step, no network. It binds to `127.0.0.1` and edits your working
tree, so treat a save like any other edit: review it with `git diff` and commit it yourself.

## What it edits

| Corpus | Pagination | Notes |
|---|---|---|
| `narratives/starting_books/` | `StartingBookFactory.paginateExplicit` — **a `%PAGE%` line is the page break**; newlines are content | grouped by lifecycle context folder, which is the routing (see that folder's `CLAUDE.md`) |
| `narratives/random_books/` | `BookFactory.paginate` — flow | blank-line paragraphs greedy-packed to 256 chars |
| `narratives/stories/` | `BookFactory.paginate` — flow | letter picker; title from `story`, author from `character` |

`originals/` and `unused/` are ignored — the mod does not load them.

## How editing works

Each rendered page carries the `[start, end)` span of the source text it came from
(`web/js/paginate.js`), so typing on page 4 splices only that span: nothing else in the file moves.
For starting books a page is exactly one `%PAGE%`-delimited chunk, so pages are independent and any newlines inside one stay inside it. For flow books a
page is the paragraph(s) that packed onto it, and an edit reflows across pages exactly as the game
would. When one oversize chunk spills across several pages the span is shared — the editor says so,
and editing rewrites the whole chunk.

Page tools (insert, blank slot, delete, reorder, and merge for flow books) work on those same spans.

Read-only by design:

* **Locale view** — the ~20 mirrors under `data/dungeontrain/narrative_localizations/<locale>/` are
  rendered in the same frame so you can see a translation, but translations belong to the import
  pipeline, not to this tool. A badge warns when editing the English prose leaves them stale.
* **`%TOKEN%` preview** — the note-story books under `cursed*/` and `loved*/` are templates;
  toggling the preview substitutes stub values the way `CursedBookFactory` does (and shows the
  32-char title clamp landing *after* substitution). Substituted text is never written back.

## Saving

The writer's job is to leave alone everything you did not touch. On load it probes each file for the
serialisation style that reproduces it byte-for-byte (indent, ASCII escaping, trailing newline, the
blank line some books keep between variants) and re-uses it on save, so an edit to one page shows up
as a one-line diff. `random_books/musings_of_faulthurst.json` indents one key by hand and cannot be
reproduced — the editor badges it, because saving that file reformats it.

Writes are refused when the file changed on disk since you opened it, when a filename is outside the
ResourceLocation charset, or when the book would fail the loaders' rules (empty variants, negative
weight, generation outside 0–3, duplicate letter indices).

## Textures

`book.png` and the page arrows are Mojang assets and are **not** committed here. They are read at
request time from the loom `client-extra.jar` in your gradle cache (matching `minecraft_version`),
falling back to a `~/Projects/dp-relay` checkout. With neither, the page falls back to a CSS
parchment and CSS arrows and everything still works. Monocraft (OFL) comes from the same dp-relay
checkout; without it the page uses your system monospace and line breaks stop matching the game.

## Tests

```bash
node --test scripts/books-editor/web/js/paginate.test.mjs   # pagination parity + span/splice invariants
python3 -m pytest scripts/books-editor/test_corpus.py       # byte-identical round trip over the whole corpus
```

The pagination port is kept in lockstep with `BookFactory` / `StartingBookFactory` (and with
dp-relay's `web/js/book-paginate.js`, which it was ported from). If you change the algorithm in the
mod, change it in all three.
