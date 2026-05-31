# Random Books — Writing Guide

Authoritative instruction set for drafting Dungeon Train **random books** — schema, page mechanics, variant rules, and the per-book drafting workflow.

Follow this guide when writing random-book content.

> **What a random book is:** A standalone in-world book that spawns in chests / item rolls. Unlike narrative letters there is no letter sequence and no per-player story progression. Each file is one logical book, and each variant inside that file is one complete take on it. The runtime picks ONE variant per spawn, deterministically per `(worldSeed, carriageIndex, localPos, slot)`, and tracks unseen `(book, variantIndex)` tuples so a player never sees the same combo twice until they've exhausted the corpus.

---

## 1. The JSON shape

Every file in this folder is one random book. Schema:

```json
{
  "id": "filename_slug",
  "title": "Book Title",
  "author": "Author Name",
  "generation": 0,
  "weight": 1,
  "variants": [
    "First variant body — a complete short book…",
    "Second variant — same concept, different take…"
  ]
}
```

Rules:
- **Filename matches `id`** — snake_case, no spaces. e.g. `musings_of_faulthurst.json`.
- `title` omitted or `"Untitled"` → the basename is used as the title.
- `author` omitted → defaults to `"Anonymous"`.
- `generation` — vanilla 0..3 (silently clamped). `0` = original (default), `1` = copy, `2` = copy of copy, `3` = tattered. Use `0` unless the in-world conceit calls for a worn / passed-down book.
- `weight` — integer ≥ 0 driving the in-pool weighted pick. `1` is baseline. Higher = appears more often. `0` = effectively disabled.
- `variants[]` — **required, non-empty**. Each variant is a **complete book body**, not a draft of the same text.

**Read the existing corpus first.** Before drafting anything new, read every `.json` file in this folder ([random_books/](.)) end-to-end. The shipped corpus is the style reference — absorb its narrative voice, page composition, variant patterns, and tone. Treat this as mandatory context, not optional background.

Loader path: `RandomBookCodec.java` → `RandomBookFile` → `RandomBookFactory.buildVanillaBook`.

---

## 2. Newline semantics

Three sequences do all the layout work. Same as narrative books.

| In JSON | Meaning | In rendered book |
|---|---|---|
| `\n` | Single line break | Forces a new line on the current page |
| `\n\n` | Soft paragraph break | Paginator **may pack** multiple short paragraphs onto one page until 256 chars |
| `\n\n\n` (3+) | Hard page break | **Forces a new page**, regardless of content length |

The narrative paginator (`BookFactory.paginate`) first splits the body on **three or more consecutive newlines** (hard page breaks). Each resulting section then runs through a greedy packer that splits on `\n\n` (soft paragraph break) and packs as many paragraphs onto each page as the 256-char budget allows. Oversize paragraphs spill via sentence → word fallback into additional pages.

> Practical consequence: **use `\n\n\n` between beats you want on their own page**. Use `\n\n` for paragraphs that can share a page. Use `\n` for line breaks within a beat.

---

## 3. Hard limits (engine-enforced)

| Limit | Value | Source |
|---|---|---|
| Chars per page (target) | 256 | `BookFactory.MAX_CHARS_PER_PAGE` |
| Visible UI capacity | ~14 lines × ~19 chars | `BookFactory.java:41-43` |
| Pages per book | 100 (truncated with warning) | `BookFactory.MAX_PAGES` |
| Title chars | 32 (silently clamped) | `BookFactory.MAX_TITLE_CHARS` |
| Generation | 0..3 (silently clamped) | `RandomBookFile:42-44` |
| Weight | ≥ 0 (negatives rejected) | `RandomBookFile:36-39` |

The Minecraft font is proportional. Plan for **~19 chars per line** as the safe width; longer lines auto-wrap and your visual layout drifts. For predictable pages, write short lines or insert `\n` explicitly at the break point you want.

---

## 4. Page composition rules

### Default page = sparse

- **4–7 short lines, ~80–140 chars.** Negative space gives each beat weight.
- **One thought per page.** Random books are found objects — they should feel like overheard thoughts, not essays.
- Half-empty pages are correct. Resist the urge to fill.

### Special pages (full, 200–256 chars) — **only 1 or 2 per book**

A "full" page should be earned. Use it for one of these:

- **Conclusion** — the closing line that lands.
- **Key info** — a name, place, or list the player should notice.
- **Highlight** — the moment the book has been building toward.

### Book length

- **1–4 pages per variant by default.** Most random books are 2–3.
- Single-page random books are valid (see `quiet_rules.json` — one dense page, one variant only).
- Longer is allowed when the conceit demands it (`musings_of_faulthurst.json` has several 4-page variants), but justify in chat first.

---

## 5. Rendering proposed content in chat (CRITICAL)

Before any variant goes into JSON, render it in chat **page by page**, exactly as it will appear in the in-game book.

### Format

For each page:

```
Page 1
─────────────────
There are rules to this train,
though no-one will tell you them.
─────────────────
```

Rules for the rendered preview:
- Horizontal rule above and below each page.
- A blank line in the preview = `\n\n` in the JSON string.
- A line break in the preview = `\n` in the JSON string.
- Keep visible line widths ≤ ~19 chars where possible (so what the user sees matches what Minecraft will render). Longer lines are fine — just flag that Minecraft will auto-wrap them.
- Render each variant separately, page by page.

### After the rendered pages

Show the JSON string form in a fenced code block:

```
"There are rules to this train, though no-one will tell you them.\n\nDon't open a chest you've already opened."
```

And a one-line variant-diff note when applicable:

> What variant B reveals that A doesn't: B drops the "rules" framing and instead lists three observations from the same narrator's perspective — same voice, different angle.

---

## 6. Variant rules

Each random book can carry multiple variants. The runtime picks one per spawn, deterministically per `(worldSeed, carriageIndex, localPos, slot)`. The world tracks `(book, variantIndex)` tuples it has shown — unseen ones are picked first, so variants are progressively revealed.

### Same book, different facets (mandatory)

All variants of a book belong to the same logical work — same author voice, same conceit, same world-stance. A reader who finds variant A and a friend who finds variant B should agree that "this is the same book / same author / same kind of writing", just with different content.

> Random book variants are **NOT** rewrites of the same text. They are different entries in the same book, different musings by the same narrator, different fragments from the same notebook. Look at `musings_of_faulthurst.json` — 27 variants, each a self-contained Faulthurst observation, all unmistakably the same character.

### New material per variant (mandatory)

Each variant MUST be substantively new content. A variant that paraphrases another is a wasted slot. Acceptable kinds of variation:

- A different **observation** by the same narrator (Faulthurst's pattern).
- A different **fragment** from the same notebook (Passenger Fragments' pattern).
- A different **rule** from the same in-world ruleset (Quiet Rules' pattern — though this file ships just one variant; expansion would mean a fresh list, not a rewrite of the existing one).
- A different **mood** of the same character on a different day.

### Counts

- **2–10+ variants** is fine. Random books reward larger variant pools because the unseen-cycling exposes more before repeats start.
- Single-variant books are valid for one-shot conceits (see `quiet_rules.json`).
- Don't ship two variants that say the same thing in different words.

---

## 7. Weight & generation — get these right

### Weight

Drives how often this book appears in chest rolls (proportional to total pool weight).

| Use weight | When |
|---|---|
| `0` | Disabled — won't be picked. Use for variants in progress / blocked. |
| `1` | Default. One-off finds, rare fragments. |
| `2` | Recurring author with multiple files, or a stronger framing voice. |
| `3` | The core narrator-voice book(s) the player should encounter often. |
| `4`+ | Unusual — justify in chat. The pool is meant to be varied, not dominated. |

Current corpus weights for reference:
- `musings_of_faulthurst.json` → 3 (core narrator)
- `faulthursts_asides_i.json` → 2 (same narrator, supplemental)
- `passenger_fragments_i.json` → 1 (found object)
- `quiet_rules.json` → 1 (one-off conceit)

### Generation

- `0` — original, fresh-looking book (default).
- `1` — copy. Use if the in-world fiction implies the book has been transcribed.
- `2` — copy of a copy.
- `3` — tattered. Use sparingly, for ancient / much-handled finds.

When in doubt, use `0`.

---

## 8. Author voice & framing (the heart of a random book)

Random books are first-person world-building, not exposition. The strongest pattern in the corpus is the **recurring named narrator** (Faulthurst) who never explains the world but reacts to it, asks unanswered questions, and lets the player infer their nature.

Patterns that work:

- **Named narrator across multiple files.** Faulthurst gets `musings_of_faulthurst.json` (weight 3), `faulthursts_asides_i.json` (weight 2), and (implicitly) more "asides_ii", "asides_iii" to come. Each file is its own pool entry but they share voice and worldview.
- **Anonymous found object.** `passenger_fragments_i.json` is a notebook found between cars. The author is `"Anonymous"` and the conceit is **we don't know who wrote this**.
- **In-world artifact with a stated purpose.** `quiet_rules.json` presents itself as a list of survival rules. The voice is whoever set them down.

Patterns to avoid:

- **Tutorial voice.** The book should never explain mechanics directly. If you want to teach the player something, embed it as an observation a character makes.
- **Generic "lore book" voice.** Random books are personal documents. They have an `author`. They are someone's writing — never a third-person omniscient game manual.
- **Twist endings on a single page.** A random book is a fragment, not a short story. Let it end mid-thought if that's the honest shape.

---

## 9. Drafting workflow — Claude MUST follow this order

When the user asks for a new random book (or a new variant on an existing one), walk these four steps in order. Do not skip steps. Step 3 → Step 4 → loop back to Step 3 for the next variant, until the user calls the book done.

### Step 1 — Author voice & book concept

Before writing any variant, propose to the user:

- **Author name** (named, or deliberately `"Anonymous"`).
- **In-world conceit** — what kind of book is this? A notebook? A musing collection? A list? A letter never sent?
- **Voice rules** — sentence length, vocabulary range, recurring images, the narrator's relationship to the train.
- **Title** (≤ 32 chars).
- **Weight** (with justification — see Section 7).
- **Generation** (usually `0`).

Then **wait for user confirmation** before proceeding.

### Step 2 — Variant scope & overview

Propose the variant set:

- **How many variants** the book will ship with (2–10+).
- **What axis of variation** distinguishes them (different observations? different fragments? different days? different moods?).
- A one-line gist of each variant, just enough to confirm scope before drafting.
- Which (if any) variant carries a special-page moment (conclusion / key info / highlight).

Then **wait for user confirmation** before proceeding.

### Step 3 — Present the next variant

For each variant, in order:

1. Render page-by-page using the Section 5 format.
2. Show the JSON string form in a fenced code block.
3. Add the one-line variant-diff note ("What variant B does that A doesn't…") when applicable.

Then **wait for user approval** on this variant.

Do not batch multiple variants into one response. One variant at a time.

### Step 4 — On approval, persist & link, then move on

The moment the user approves a variant:

1. **Write it to the JSON file** in this folder. If it's variant 1 of a new book, create the file (filename = `<id>.json`, snake_case). If it's a later variant or an `add_variant` on an existing book, append the new string to the existing `variants[]` array.
2. **Post a clickable link to the file** so the user can open the assembled book so far. Use the markdown form `[filename.json](src/main/resources/data/dungeontrain/narratives/random_books/filename.json)`.
3. **Briefly summarise what's in the file now** — "3 variants written of the planned 6" — so the reviewer knows the scope of the current snapshot.
4. **Move to the next variant** (loop back to Step 3) without waiting for a fresh prompt — the approval is the green light.

The user can review the whole book end-to-end at any time by opening the linked file. The file grows variant by variant; each approval is a checkpoint.

If the user rejects or edits a variant at Step 3, do NOT write to the file. Re-draft in chat, present again, wait for approval.

---

## 10. Serialization checklist

Before writing the JSON file:

- [ ] Filename matches the `id` field (snake_case).
- [ ] `title` ≤ 32 chars (or accept silent truncation).
- [ ] `author` set (or deliberately omitted for "Anonymous").
- [ ] `generation` ∈ 0..3 (will be silently clamped if outside).
- [ ] `weight` ≥ 0, justified per Section 7.
- [ ] `variants[]` non-empty.
- [ ] No paragraph in any variant exceeds 256 chars (otherwise it spills into a surprise page).
- [ ] Each variant's longest path ≤ 100 pages.
- [ ] Each variant is substantively distinct from the others.

---

## 11. Auto-release reference

Random books ship through the same cascade as narrative letters:

- **New whole book** → add a `new_file` queue item to `.github/auto-release/queue.json` `pending[]`, targeting `src/main/resources/data/dungeontrain/narratives/random_books/<id>.json`.
- **New variant on an existing book** → add an `add_variant` queue item targeting the same file. (Verify the `add_variant` schema in `.github/auto-release/README.md` supports the random-book shape — narrative letters expect `letter.index` / `letter.label`; random books have no letter concept and need either a schema variant or a `new_file` rewrite of the whole file. Flag this in chat if it bites.)
- Full schema: `.github/auto-release/README.md`.

---

## 12. Anti-patterns

Avoid these:

- **Padding pages to fill them.** Half-empty pages are the style — not a defect.
- **Variants that paraphrase each other.** Each variant must be new content.
- **Tutorial voice.** Random books are first-person world-building, not exposition.
- **Twist endings.** A random book is a fragment, not a short story.
- **Generic Anonymous voices.** Even an Anonymous found-object book has a *specific* unknown author. Give them quirks.
- **Long paragraphs.** A 200-char paragraph becomes one cramped page. Break it.
- **Mixing voices across variants of the same book.** All variants of `musings_of_faulthurst.json` must sound like Faulthurst. A different voice means a different book / file.
- **Skipping Step 1 or 2** of the workflow. Even when the user pushes for variant drafts, anchor voice and scope first.

---

## 13. References

- Corpus (read all): every `.json` file in this folder.
- Flow paginator: `src/main/java/games/brennan/dungeontrain/narrative/BookFactory.java:252-282`
- Random-book builder: `src/main/java/games/brennan/dungeontrain/narrative/RandomBookFactory.java`
- Schema record: `src/main/java/games/brennan/dungeontrain/narrative/RandomBookFile.java`
- Codec: `src/main/java/games/brennan/dungeontrain/narrative/RandomBookCodec.java`
- Registry / load path: `src/main/java/games/brennan/dungeontrain/narrative/RandomBookRegistry.java`
- Auto-release queue schema: `.github/auto-release/README.md`
