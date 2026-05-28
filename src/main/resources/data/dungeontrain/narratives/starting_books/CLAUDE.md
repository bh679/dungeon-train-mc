# Starting Books — Writing Guide

Authoritative instruction set for drafting Dungeon Train **starting books** (the welcome / lightning-strike books) — schema, page mechanics, context routing, and the per-book drafting workflow.

Follow this guide when writing starting-book content.

> **What a starting book is:** A one-off written book handed to the player at a defining lifecycle moment — first login, new world, joined world, respawn. Delivered via in-game lightning strike, opened once, then burned on close. The player sees ONE variant of ONE book per fire, picked from a context-specific pool with fallback to the DEFAULT pool. Unlike narrative letters there is no progression; unlike random books there is no chest-loot context. Starting books are the **mod creator speaking directly to the player** at a charged moment.

---

## 1. Context routing — the most important thing to know first

This folder is partitioned by **lifecycle context** via subfolders. The subfolder a book lives in determines when it can be rolled.

| Subfolder | Context | Fires when |
|---|---|---|
| (root) | `DEFAULT` | First-ever play of the mod on this machine. Also the fallback when any other context pool is empty / zero-weight. |
| `new_world/` | `NEW_WORLD` | Player has played the mod before (gamedir marker present) but this world has no other welcomed players yet. |
| `joined_world/` | `JOINED_WORLD` | First login on a multiplayer world where at least one other player has already been welcomed. |
| `respawn/` | `RESPAWN` | Every non-End-conquered respawn. RESPAWN pool **cycles** — each (book, variantIndex) tuple is only shown once per world until exhausted; then widens permanently to RESPAWN + DEFAULT. |

Fallback rule: an empty or zero-weight context pool falls through to DEFAULT. So if you ship no `respawn/` books, every respawn rolls from DEFAULT.

**Pick the right subfolder before drafting.** A book about "joining your friend's world" belongs in `joined_world/`. A book about "ahhh a brand new world" belongs in `new_world/`. A book about "back from the dead" belongs in `respawn/`. A generic welcome belongs in the root (DEFAULT).

Resolution code: `StartingBookEvents.resolveLoginContext` decides the context at strike-fire time, not enqueue time. Registry sub-pools: `StartingBookRegistry.POOLS`.

---

## 2. The JSON shape

Same schema as random books (the registry shares the `RandomBookFile` record):

```json
{
  "id": "filename_slug",
  "title": "Book Title",
  "author": "Author Name",
  "generation": 0,
  "weight": 1,
  "variants": [
    "First variant body — a complete welcome…",
    "Second variant — same moment, different take…"
  ]
}
```

Rules:
- **Filename must be a valid Minecraft ResourceLocation path** — `[a-z0-9._-]` only. No spaces, no uppercase, no punctuation outside that set. The registry will skip-and-warn on invalid filenames (e.g. `"lighting copy.json"`) rather than crashing — but the book won't load. **Author paper-cut to watch.**
- **Filename matches `id`** — snake_case.
- `title` omitted or `"Untitled"` → basename is used as the title.
- `author` omitted → defaults to `"Anonymous"`. In practice every shipped starting book is authored by `"Brennan Hatton"` — see Section 8.
- `generation` — vanilla 0..3 (silently clamped). Use `0` unless the conceit calls for an aged / passed-down book (rare for starting books).
- `weight` — integer ≥ 0. `1` is baseline. See Section 7.
- `variants[]` — **required, non-empty**. Each variant is a **complete book body**; the runtime picks ONE per fire.

**Read the existing corpus first.** Before drafting anything new, read every `.json` file in this folder ([starting_books/](.)) end-to-end, including the subfolders ([new_world/](new_world), [joined_world/](joined_world), and [respawn/](respawn) when it has content). The shipped corpus is the style reference — absorb the voice (recursive trinity questions, meta self-awareness, Brennan-as-narrator), page composition, and tone. Treat this as mandatory context, not optional background.

Loader path: `RandomBookCodec.java` → `RandomBookFile` → `StartingBookFactory.buildUnstampedBook`.

---

## 3. Newline semantics — DIFFERENT FROM NARRATIVE / RANDOM BOOKS

Starting books use **`paginateExplicit`** instead of the flow paginator. This means **`\n\n` is a hard page break**, fully author-controlled.

| In JSON | Meaning | In rendered book |
|---|---|---|
| `\n` | Single line break | Forces a new line on the current page |
| `\n\n` | **Hard page break** | Always starts a new page |
| `\n\n\n\n` (or `\n\n \n\n`) | **Blank page slot** | Two `\n\n` with empty / whitespace between → an empty page between two real pages |

Practical consequences:
- **You design the pagination directly.** Every `\n\n` is a page boundary you authored. The paginator does NOT pack short paragraphs together — each chunk between page breaks is its own page, full or sparse.
- **Blank pages are a tool.** See `blank_pages.json` for the canonical pattern: dense use of `\n\n.\n\n` to create deliberately-dotted "blank" pages between thoughts, as a rhythmic / comic device.
- **Leading and trailing blanks are auto-trimmed** — opening on a blank page or having dead pages at the end is never useful. Internal blanks are preserved.
- **Overflow safety:** if a chunk between `\n\n`s exceeds 256 chars it spills into additional pages via the flow paginator. So oversize paragraphs still don't clip, but you lose your hand-placed break.

> The mental model: **every `\n\n` is a "next page" button press by the author.** Compose deliberately.

---

## 4. Hard limits (engine-enforced)

| Limit | Value | Source |
|---|---|---|
| Chars per page (target) | 256 | `BookFactory.MAX_CHARS_PER_PAGE` |
| Visible UI capacity | ~14 lines × ~19 chars | `BookFactory.java:41-43` |
| Pages per book | 100 (truncated with warning) | `BookFactory.MAX_PAGES` |
| Title chars | 32 (silently clamped) | `BookFactory.MAX_TITLE_CHARS` |
| Generation | 0..3 (silently clamped) | `RandomBookFile:42-44` |
| Weight | ≥ 0 (negatives rejected) | `RandomBookFile:36-39` |
| Filename | `[a-z0-9._-]` only | `ResourceLocation` (warn+skip on violation) |

The Minecraft font is proportional. Plan for **~19 chars per line** as the safe width; longer lines auto-wrap. For predictable pages, write short lines or insert `\n` explicitly.

---

## 5. Page composition rules

### Default page = sparse

- **A few short lines per page.** Starting books are a **moment**, not an essay. Leave space for the player to feel the beat.
- The trinity-question opener (Section 8) is a longer block but reads as one unit at the page level — it's an intentional incantation, not a wall of text.

### Special pages (full, 200–256 chars) — **only 1 or 2 per variant**

A "full" page should be earned. Use it for one of these:

- **The pay-off / pivot** — the moment the book stops asking and says something.
- **Key info** — the only directive the player needs ("There's a train ahead. Get on it.").
- **The closing line** — the beat that lands as the player closes the book (and it burns).

### Blank pages (intentional)

`blank_pages.json` is the canonical example. Used for:
- Comic timing (silence between thoughts).
- "This page was intentionally left blank" gag.
- Spacing out a slow read so the player keeps flipping.

Use sparingly. One book per pool with a blank-page conceit is plenty.

### Book length

- **3–10 pages per variant by default.** Some are 1–2 (`more_questions_next_time.json`), some are very long (`infinite_vs_repeat.json`, `a_paper_algorithm.json`).
- The player **only sees one variant per fire**, and the book burns on close. Longer is acceptable if the conceit needs it — the player isn't grinding through a chest.
- Justify any variant > 15 pages in chat first.

---

## 6. Rendering proposed content in chat (CRITICAL)

Before any variant goes into JSON, render it in chat **page by page**, exactly as it will appear in the in-game book. **Because `\n\n` is a hard page break, the rendered preview is a 1:1 layout — what you draw is what the player sees.**

### Format

For each page:

```
Page 1
─────────────────
Welcome to the
Dungeon Train
What is the train?
Why are you here?
What are you meant
to do?
These are all
good questions
─────────────────
```

Rules for the rendered preview:
- Horizontal rule above and below each page.
- Each chunk between `\n\n` is one page — render them in order.
- A line break inside a page = `\n` in the JSON string.
- A blank-page slot (e.g. just `.` alone on a page) gets its own rendered block.
- Keep visible line widths ≤ ~19 chars where possible. Longer lines are fine, but flag that Minecraft will auto-wrap.
- Render each variant separately, page by page.

### After the rendered pages

Show the JSON string form in a fenced code block:

```
"Welcome to the Dungeon Train\nWhat is the train?\nWhy are you here?\nWhat are you meant to do?\nThese are all good questions\n\nNow go explore"
```

And a one-line variant-diff note when applicable:

> What variant B does that A doesn't: B drops the trinity-question opener and instead jumps straight to "Hey, you. You're finally awake." — same lifecycle moment, Skyrim-reference framing.

---

## 7. Variant rules

Each starting book can carry multiple variants. The runtime picks one per fire, deterministically. Unlike random books, **starting books are not cycled at the variant level globally** — but the `RESPAWN` pool does track `(book, variantIndex)` tuples per world for cycling semantics.

### Same lifecycle moment, different vibes (mandatory)

All variants of a book belong to the same lifecycle moment and conceit. A reader who gets variant A and a friend who gets variant B at the same lifecycle moment should agree that "this is the same book / same kind of welcome", just delivered differently.

> Example from `meme_start.json`: 5 variants, all are the "you've just arrived" moment. Variant 1 is Skyrim ("Hey, you. You're finally awake."), Variant 4 is Stanley Parable ("Stanley worked for a company…"), Variant 5 ends "There is cake." (Portal). All play on **arrival as media-reference jokes** — same conceit, different jokes.

### New material per variant (mandatory)

Each variant MUST be substantively new. A variant that paraphrases another is a wasted slot. Acceptable kinds of variation:

- A different **answer** to the trinity questions (see `brennan_intro.json` — 4 variants, each gives different answers to "what is this train?").
- A different **reference / gag** layered on the same moment (see `meme_start.json`).
- A different **mood** of the same arrival.
- A different **focus** — one variant is curious, another is sarcastic, another is somber.

### Counts

- **1–6 variants** is typical. Single-variant books are common and valid (most of the corpus is single-variant).
- For RESPAWN pool books, more variants = more fresh respawns before cycling kicks in. Variants there work harder.

---

## 8. The Brennan voice — recognise it before you write it

Every shipped starting book is authored by `"Brennan Hatton"`. This is intentional. Starting books are **the mod creator speaking directly to the player** at a charged moment (just spawned, just respawned, just joined a friend's world). The corpus has a recognisable voice — match it unless you have a strong reason not to.

### The trinity opener

The phrase

```
Welcome to the Dungeon Train
What is the train?
Why are you here?
What are you meant to do?
These are all good questions
```

…appears (verbatim or near-verbatim) in most starting books. Treat it as the **opening incantation**. Many variants either:
- **Use it straight,** then pivot to the actual content.
- **Subvert it** — drop it, mock it, claim the answers are elsewhere, twist "good questions" into "good questions don't always get good answers".
- **Recurse on it** — interleave it with more questions, or repeat fragments of it for hypnotic effect.

Look at `infinite_vs_repeat.json` for the recursive pattern, `questions.json` for the "I ask the questions, you find the answers" pivot, `description.json` for the "what is the point" escalation, `looking_for_answers.json` for the meta-question pattern.

### Voice traits

Brennan-the-narrator is:
- **Self-aware about being a book.** Lines like "Stop grabbing the paper", "the paper is diminishing", "I'm just a book, so I can't hear you" — the book knows it's a book.
- **Meta about the mod / creator.** "Developed by an award winning VR developer", "Am I as the author of this paper, human?"
- **Question-heavy.** Statements often arrive as questions. "Doesn't a feeling equal trust?" "Is this the meaning of life?"
- **Comfortable with absurdity.** Long lists, fake-out blank pages, escalating nonsense, "There is cake."
- **Warm but unreliable.** Gives the player permission to explore but undercuts its own authority.

### When to depart from Brennan-voice

Acceptable to use a different `author` when:
- The conceit explicitly calls for someone / something else speaking (a found note, a passenger's voice, a system message).
- The lifecycle moment doesn't fit Brennan-as-host (e.g. a RESPAWN book from a dying NPC's perspective).

If you depart, **say so in Step 1** of the drafting workflow.

---

## 9. Weight & generation

### Weight

Drives how often this book is picked from its context pool (proportional to total pool weight).

| Use weight | When |
|---|---|
| `0` | Disabled — won't be picked. Use for variants in progress / blocked. |
| `1` | Default. Every shipped starting book uses `1`. |
| `2`+ | Unusual — justify in chat. Starting books are first-impression content; the pool is meant to feel varied. |

The shipped corpus is uniformly weight `1`. Stay there unless you have a reason.

### Generation

- `0` — fresh, default. Use this.
- `1`–`3` — copy / tattered look. Rare for starting books (the conceit is a fresh delivery, not an artifact).

---

## 10. Drafting workflow — Claude MUST follow this order

When the user asks for a new starting book (or a new variant on an existing one), walk these four steps in order. Step 3 → Step 4 → loop back to Step 3 for the next variant, until the user calls the book done.

### Step 1 — Context, voice & book concept

Before writing any variant, propose to the user:

- **Target context / subfolder** (root DEFAULT, `new_world/`, `joined_world/`, `respawn/`) — see Section 1.
- **Lifecycle moment the book is meant to inhabit** — what is the player feeling when this fires?
- **Author** (default `"Brennan Hatton"` — see Section 8; deviation needs justification).
- **In-world conceit / hook** — recursive questions? Meme reference? Direct address? Blank-page joke?
- **Title** (≤ 32 chars).
- **Filename** (`[a-z0-9._-]` only — verify it's a valid ResourceLocation path).
- **Weight** (default `1`).
- **Generation** (default `0`).
- **Will this book use the trinity opener?** Y/N/subverted — flag it explicitly.

Then **wait for user confirmation** before proceeding.

### Step 2 — Variant scope & overview

Propose the variant set:

- **How many variants** (1–6 typical, more for RESPAWN pool).
- **What axis of variation** distinguishes them (different answers? different gags? different moods?).
- A one-line gist of each variant.
- The page count target for each (varies — `infinite_vs_repeat.json` is one long variant; `meme_start.json` mixes short and long).

Then **wait for user confirmation** before proceeding.

### Step 3 — Present the next variant

For each variant, in order:

1. Render page-by-page using the Section 6 format. **Because `\n\n` is a hard page break, what you render is what the player sees — be precise.**
2. Show the JSON string form in a fenced code block.
3. Add the one-line variant-diff note when applicable.

Then **wait for user approval** on this variant.

Do not batch multiple variants into one response. One variant at a time.

### Step 4 — On approval, persist & link, then move on

The moment the user approves a variant:

1. **Write it to the JSON file** in the chosen subfolder. If it's variant 1 of a new book, create the file (filename = `<id>.json` in the target subfolder). If later, append the new string to the existing `variants[]` array.
2. **Post a clickable link to the file** using the markdown form `[filename.json](src/main/resources/data/dungeontrain/narratives/starting_books/<subfolder>/filename.json)` (or omit `<subfolder>/` for DEFAULT).
3. **Briefly summarise what's in the file now** — "3 variants written of the planned 5" — so the reviewer knows the scope.
4. **Move to the next variant** (loop back to Step 3) without waiting for a fresh prompt.

The user can review the whole book end-to-end at any time via the linked file.

If the user rejects or edits a variant at Step 3, do NOT write to the file. Re-draft in chat, present again, wait for approval.

---

## 11. Serialization checklist

Before writing the JSON file:

- [ ] Filename matches the `id` field (snake_case, `[a-z0-9._-]` only).
- [ ] File is in the correct subfolder for its target context.
- [ ] `title` ≤ 32 chars (or accept silent truncation).
- [ ] `author` set (typically `"Brennan Hatton"`; otherwise justified per Section 8).
- [ ] `generation` ∈ 0..3.
- [ ] `weight` ≥ 0 (typically `1`).
- [ ] `variants[]` non-empty.
- [ ] Every `\n\n` is a deliberate page break — the rendered preview matches the planned layout.
- [ ] No chunk between `\n\n`s exceeds 256 chars (otherwise spills into surprise pages).
- [ ] Each variant ≤ 100 pages.
- [ ] Each variant is substantively distinct from the others.

---

## 12. Auto-release reference

Starting books ship through the same cascade as narrative letters and random books:

- **New whole book** → add a `new_file` queue item to `.github/auto-release/queue.json` `pending[]`, targeting `src/main/resources/data/dungeontrain/narratives/starting_books/<subfolder>/<id>.json` (omit `<subfolder>/` for DEFAULT).
- **New variant on an existing book** → add an `add_variant` queue item targeting the same file. (Verify the `add_variant` schema in `.github/auto-release/README.md` supports the random-book shape — narrative letters expect `letter.index` / `letter.label`; starting books have no letter concept and need either a schema variant or a `new_file` rewrite. Flag this in chat if it bites.)
- Full schema: `.github/auto-release/README.md`.

---

## 13. Anti-patterns

Avoid these:

- **Wrong subfolder.** A "welcome to multiplayer" book in DEFAULT will fire for solo first-spawns. Put context-specific content in the right subfolder.
- **Invalid filenames.** `lighting copy.json` won't load — capital letters and spaces break the ResourceLocation parser. Stick to `[a-z0-9._-]`.
- **Padding pages to fill them.** Half-empty pages are the style.
- **Variants that paraphrase each other.** Each variant must be new content.
- **Generic tutorial voice.** Starting books are a moment with the creator, not a manual.
- **Skipping the trinity opener without thinking about it.** It's the project's recognisable beat. Drop it deliberately, not absentmindedly.
- **Long unbroken chunks between `\n\n`.** They spill and you lose your authored layout.
- **Mixing author voices within one book's variants.** All variants of one book should share author and conceit.
- **Skipping Step 1 or 2** of the workflow. Even when the user pushes for variant drafts, anchor context and conceit first.

---

## 14. References

- Corpus (read all): every `.json` file in this folder and its subfolders.
- Explicit-page-break paginator: `src/main/java/games/brennan/dungeontrain/narrative/StartingBookFactory.java:266-289`
- Starting-book builder: `src/main/java/games/brennan/dungeontrain/narrative/StartingBookFactory.java`
- Schema record (shared with random books): `src/main/java/games/brennan/dungeontrain/narrative/RandomBookFile.java`
- Codec: `src/main/java/games/brennan/dungeontrain/narrative/RandomBookCodec.java`
- Registry / context-routed load: `src/main/java/games/brennan/dungeontrain/narrative/StartingBookRegistry.java`
- Context enum: `src/main/java/games/brennan/dungeontrain/narrative/StartingBookContext.java`
- Lifecycle resolution: `src/main/java/games/brennan/dungeontrain/event/StartingBookEvents.java`
- Auto-release queue schema: `.github/auto-release/README.md`
