# Narrative Letters — Writing Guide

Authoritative instruction set for drafting Dungeon Train story letters — schema, page mechanics, variant rules, and the per-letter drafting workflow.

Follow this guide for writing story content

---

## 1. The JSON shape

Every file in this folder is one story. Schema:

```json
{
  "id": "filename_slug",
  "character": "Author Name",
  "story": "Story Title",
  "letters": [
    {
      "index": 1,
      "label": "Letter One",
      "variants": [
        "First variant text…",
        "Alternative take on the same letter…"
      ],
      "notes": [
        { "variant": 0, "offset": 528, "text": "Optional editor note" }
      ]
    }
  ]
}
```

Rules:
- **Filename matches `id`** — snake_case, no spaces. e.g. `corren_vale_three_heads.json`.
- `character` omitted → defaults to `"Anonymous"`.
- `story` omitted → defaults to `"Untitled"` (then the letter `label` is used as the book title).
- Each letter MUST have `index` (1-based), `label`, and at least one `variants[]` entry.
- `notes` is optional — used for editor annotations on a specific variant's char offset.

**Read the existing corpus first.** Before drafting anything new, read **every** `.json` story file in this folder ([stories/](.)) end-to-end. The shipped corpus is the style reference — absorb its narrative voice, page composition, variant patterns, signature style, and pacing before adding to it. Treat this as mandatory context, not optional background.

Loader path: `StoryCodec.java` → `StoryFile` → `BookFactory.buildSignedBook`.

---

## 2. Newline semantics

Three sequences do all the layout work.

| In JSON | Meaning | In rendered book |
|---|---|---|
| `\n` | Single line break | Forces a new line on the current page |
| `\n\n` | Soft paragraph break | Paginator **may pack** multiple short paragraphs onto one page until 256 chars |
| `\n\n\n` (3+) | Hard page break | **Forces a new page**, regardless of content length |

The narrative paginator (`BookFactory.paginate`) first splits the body on **three or more consecutive newlines** (hard page breaks). Each resulting section then runs through a greedy packer that splits on `\n\n` (soft paragraph break) and packs as many paragraphs onto each page as the 256-char budget allows. Oversize paragraphs spill via sentence → word fallback into additional pages.

> Practical consequence: **use `\n\n\n` between beats you want on their own page** (single-word snaps like `Cute.`, sparse openers, sign-offs). Use `\n\n` for paragraphs that can share a page. Use `\n` for line breaks within a beat.

For starting books (`StartingBookFactory.paginateExplicit`) `\n\n` is a hard page break — a different paginator with author-controlled paging. The rule above is for this folder.

---

## 3. Hard limits (engine-enforced)

| Limit | Value | Source |
|---|---|---|
| Chars per page (target) | 256 | `BookFactory.MAX_CHARS_PER_PAGE` |
| Visible UI capacity | ~14 lines × ~19 chars | comment in `BookFactory.java:41-43` |
| Pages per book | 100 (truncated with warning) | `BookFactory.MAX_PAGES` |
| Title chars | 32 (silently clamped) | `BookFactory.MAX_TITLE_CHARS` |

The Minecraft font is proportional — a line of `iiii` fits where `WWWW` doesn't. Plan for **~19 chars per line** as the safe width; lines longer than that will auto-wrap and your visual layout drifts. For predictable pages, write short lines or insert `\n` explicitly at the break point you want.

---

## 4. Page composition rules

### Default page = sparse

- **4–7 short lines, ~80–140 chars.** Negative space gives each beat weight.
- **One sentence per line** is the house style. Look at `corren_vale_three_heads.json` — every `\n\n`-separated page is one or two sentences, never a wall of prose.
- Half-empty pages are correct. Resist the urge to fill.

### Special pages (full, 200–256 chars) — **only 1 or 2 per story**

A "full" page should be earned. Use it for one of these:

- **Conclusion** — the closing emotional beat or sign-off.
- **Key info** — a name, date, place, or list the player needs to remember.
- **Highlight** — the climax line the rest of the story has been building toward.

### Letter length

- **1–5 pages by default.** Most letters are 3–4.
- Longer is allowed when the narrative demands it. Justify the length in chat before drafting.

---

## 5. Rendering proposed content in chat (CRITICAL)

Before any letter goes into JSON, render it in chat **page by page**, exactly as it will appear in the in-game book. This lets the user catch layout problems before they hit the file.

### Format

For each page:

```
Page 1
─────────────────
Mira,

Your hands were always covered in chalk.

You drew lines on everything.
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
"Mira,\n\nYour hands were always covered in chalk.\n\nYou drew lines on everything."
```

And a one-line variant-diff note when applicable:

> What variant B reveals that A doesn't: B is addressed to Bram (the shield-bearer) instead of Mira (the chalk-line drawer), and reveals Bram's nervous walk through doorways.

---

## 6. Variant rules

Each letter can carry multiple variants. The runtime picks one variant per book spawn (deterministically per world seed). Treat variants as **same events, different lens**.

### Narrative consistency (mandatory)

Same character, same world events, same broad arc. A player who reads variant A and a friend who reads variant B should not contradict each other on facts. If Mira died in the cave in variant A, she did not die at sea in variant B.

### New information per variant (mandatory)

Each variant MUST offer something the others miss:

- A different sensory detail — a smell, a sound, a chalk mark.
- A different addressee — see `corren_vale_three_heads.json` Letter 3: variant A speaks to Bram about his laugh, variant B speaks to Bram about his shield.
- A different mood that recolors the same moment.
- A different small fact — a name, a date, a place.

### Counts

- **2–3 variants per letter** is the sweet spot.
- Don't ship variants that paraphrase each other. A variant that adds no new information is a wasted slot.
- It's fine for some letters to have only one variant (especially structural / linking letters).

---

## 7. Drafting workflow — Claude MUST follow this order

When the user asks for a new story, walk these four steps in order. **Do not skip steps.** Even if the user says "just draft Letter 3", reframe to Step 1 first if no character has been established. Step 3 → Step 4 → loop back to Step 3 for the next letter, until all letters are approved and persisted.

### Step 1 — Character & intentions

Before writing any letter, propose to the user:

- **Name** and **signature style** (e.g. "- Corren" formal vs "- corren" when grief-struck).
- **Role in the world** — who they are, what happened to them.
- **Emotional core** — what they're carrying.
- **Voice rules** — sentence length, vocabulary range, recurring images, any quirks (lowercase when broken, period-only punctuation, no question marks ever, etc.).

Then **wait for user confirmation** before proceeding.

### Step 2 — Structure & overview

Propose the full letter set:

- **How many letters** — typically 3–7.
- **Who each letter is addressed to** — and why.
- **The arc beat for each letter** — one line each. Where does this letter sit in the emotional shape?
- **Which letter carries the special-page moment** — conclusion / key info / highlight.

Then **wait for user confirmation** before proceeding.

### Step 3 — Present the next letter with variants

For each letter, in order:

1. Render the letter page-by-page using the Section 5 format.
2. Render each variant separately, also page-by-page.
3. Add the one-line variant-diff note ("What variant B reveals that A doesn't…").
4. Show the JSON string for each variant.

Then **wait for user approval** on this letter.

Do not batch multiple letters into one response. One letter at a time.

### Step 4 — On approval, persist & link, then move on

The moment the user approves a letter:

1. **Write it to the JSON file** in this folder. If it's Letter 1, create the file (filename = `<id>.json`, snake_case). If it's Letter 2+, append the new letter object to the existing `letters[]` array (keep them in `index` order).
2. **Post a clickable link to the file** in chat so the user can open the assembled story so far. Use the markdown form `[filename.json](src/main/resources/data/dungeontrain/narratives/stories/filename.json)`.
3. **Briefly summarise what's in the file now** — "Letters 1–2 of 5 written, 2 more to go" — so the reviewer knows the scope of the current snapshot.
4. **Move to the next letter** (loop back to Step 3) without waiting for a fresh prompt — the approval is the green light.

The user can review the whole story end-to-end at any time by opening the linked file. The file grows letter by letter; each approval is a checkpoint.

If the user rejects or edits a letter at Step 3, do NOT write to the file. Re-draft in chat, present again, wait for approval.

---

## 8. Serialization checklist

Before writing the JSON file:

- [ ] Filename matches the `id` field (snake_case).
- [ ] `character` set (or deliberately omitted for "Anonymous").
- [ ] `story` set (or deliberately omitted for "Untitled").
- [ ] Every letter has `index`, `label`, ≥1 variant.
- [ ] No paragraph in any variant exceeds 256 chars (otherwise it will spill into a surprise page).
- [ ] Total pages across the longest variant ≤ 100.
- [ ] Title ≤ 32 chars (or accept silent truncation).
- [ ] `letters[]` is ordered by `index` ascending.

---

## 9. Auto-release reference

When a story (or a variant) is ready to ship, it goes through the auto-release cascade:

- **New whole story** → add a `new_file` queue item to `.github/auto-release/queue.json` `pending[]`.
- **New variant on an existing letter** → add an `add_variant` queue item.
- Auto-release commit format: `chore(auto): content(narrative): <Author> Letter <N> variant <A|B|…> (auto v<x.y.z>) [skip ci]`.
- Full schema: `.github/auto-release/README.md`.

The cascade fires ~22 times over 14 days after a real release, draining the queue one item per tick. Single-letter stories ship as `new_file`; longer stories often ship as a `new_file` for Letter 1, then a sequence of `add_variant`s and (rarely) more `new_file`-style appends for later letters.

---

## 10. Anti-patterns

Avoid these:

- **Padding pages to fill them.** Half-empty pages are the style — not a defect.
- **Variants that paraphrase each other.** Each variant must add new information.
- **Editorializing.** The narrator should not explain the meaning of events. Show, don't tell.
- **Wrapped-bow endings.** Endings should land sharp. No "and so I learned…" closers.
- **Skipping Step 1 or 2** of the workflow. Even when the user pushes for letter drafts, anchor character and structure first.
- **Long paragraphs.** A 200-char paragraph becomes one cramped page. Break it.
- **Mixing voices across variants.** If variant A uses lowercase signatures, variant B should too (unless the mood-shift is deliberate and obvious).

---

## 11. References

- Canonical short example: [`corren_vale_three_heads.json`](corren_vale_three_heads.json)
- Long-form example: [`the_letters_of_madame_ulster_the_answerless_prophet.json`](the_letters_of_madame_ulster_the_answerless_prophet.json)
- Flow paginator (narrative books): `src/main/java/games/brennan/dungeontrain/narrative/BookFactory.java:252-282`
- Explicit page breaks (starting books): `src/main/java/games/brennan/dungeontrain/narrative/StartingBookFactory.java:266-289`
- Story loader: `src/main/java/games/brennan/dungeontrain/narrative/StoryCodec.java`
- Auto-release queue schema: `.github/auto-release/README.md`
