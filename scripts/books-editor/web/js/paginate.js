/**
 * paginate.js — a faithful port of the mod's two pagination algorithms, so the editor's book frame
 * breaks a raw body into exactly the pages the in-game BookViewScreen shows.
 *
 *   - `paginate`         ← BookFactory.paginate            (random books, narrative letters)
 *   - `paginateExplicit` ← StartingBookFactory.paginateExplicit (starting / welcome books)
 *
 * Starting books break the page on `\n\n\n`. A lone blank line (`\n\n`) is a blank LINE inside the
 * page — the marker moved up one newline in 2026-09 precisely so that authors could write one.
 *
 * Ported from dp-relay's `web/js/book-paginate.js`, which is itself the validated port of the Java.
 * Keep all three in lockstep — do not "improve" the algorithm here without changing the mod.
 *
 * On top of the string-returning functions the relay has, this module adds {@link paginateSpans}:
 * the same pagination, but every page also carries the [start, end) offsets of the SOURCE text it
 * came from. That is what makes the preview editable — typing on page N splices only
 * `body.slice(page.start, page.end)`, leaving the rest of the body byte-identical.
 */

export const MAX_CHARS_PER_PAGE = 256; // BookFactory.MAX_CHARS_PER_PAGE
export const MAX_PAGES = 100;          // BookFactory.MAX_PAGES
export const MAX_TITLE_CHARS = 32;     // BookFactory.MAX_TITLE_CHARS

/** Pagination mode for a corpus. Starting books are author-paginated; everything else flows. */
export const FLOW = 'flow';
export const EXPLICIT = 'explicit';

/** The starting-book page break. Exactly this run of newlines, nothing shorter and nothing longer. */
export const PAGE_BREAK = '\n\n\n';

// --- string API (parity with the mod / the relay) ---------------------------

/** Split a raw book body into page strings — BookFactory.paginate. */
export function paginate(body) {
  return paginateSpans(body, FLOW).map((p) => p.text);
}

/** Split a starting-book body into page strings — StartingBookFactory.paginateExplicit. */
export function paginateExplicit(body) {
  return paginateSpans(body, EXPLICIT).map((p) => p.text);
}

// --- span API (what the editor renders) -------------------------------------

/**
 * Paginate `body` and return `[{ text, start, end, exclusive }]`.
 *
 *   text       the page as the game renders it (trimmed / packed like the mod)
 *   start,end  offsets into `body` of the source chunk this page was built from
 *   exclusive  true when this page OWNS its source span — i.e. editing the page can splice its new
 *              text straight back in. False when one oversize source chunk spilled across several
 *              pages, so the span is shared and an edit has to rewrite the whole chunk.
 *
 * Always returns at least one page (a blank one), mirroring the mod's empty-body fallback.
 */
export function paginateSpans(body, mode = FLOW) {
  if (typeof body !== 'string' || !body) return [blankPage()];
  const pages = mode === EXPLICIT ? explicitPages(body) : flowPages(body);
  return pages.length ? pages : [blankPage()];
}

const blankPage = () => ({ text: '', start: 0, end: 0, exclusive: true });

/**
 * StartingBookFactory.paginateExplicit — EXACTLY `\n\n\n` is a hard page break (not `\n{3,}`), no
 * paragraph packing. A chunk inside the budget becomes a page verbatim — blank lines and all,
 * including an EMPTY chunk, which is an intentional blank-page slot. Only an oversize chunk falls
 * back to the flow packer. Leading / trailing blank pages are trimmed; internal blanks survive.
 */
function explicitPages(body) {
  const pages = [];
  let cursor = 0;
  for (const chunk of body.split(PAGE_BREAK)) {
    const start = cursor;
    const end = cursor + chunk.length;
    cursor = end + PAGE_BREAK.length;
    const page = chunk.trim();
    if (page.length <= MAX_CHARS_PER_PAGE) {
      pages.push({ text: page, start, end, exclusive: true });
    } else {
      // Same oversize fallback the mod uses (BookFactory.paginate over the trimmed chunk).
      for (const p of paginate(page)) pages.push({ text: p, start, end, exclusive: false });
    }
  }
  while (pages.length && pages[0].text === '') pages.shift();
  while (pages.length && pages[pages.length - 1].text === '') pages.pop();
  return pages;
}

/**
 * BookFactory.paginate — `\n{3,}` is a hard section break; within a section, blank-line paragraphs
 * are greedy-packed onto a page until the 256-char budget is hit; an oversize paragraph splits on
 * sentence, then word, then raw chars.
 */
function flowPages(body) {
  const pages = [];
  for (const section of splitSections(body)) {
    if (section.end === section.start) continue; // Java: `if (section.length() == 0) continue`
    for (const page of packSection(body, section)) pages.push(page);
  }
  return pages;
}

/** Body → section spans, split on three-or-more consecutive newlines. */
function splitSections(body) {
  return splitSpans(body, /\n{3,}/g);
}

/**
 * Section → paragraph tokens, split on a blank line. The mod's regex is `\n\s*\n`; `\s` there can
 * swallow further newlines, which only ever merges separators — an extra empty token on our side
 * is dropped by the same "skip empty paragraph" rule, so the pages come out identical.
 */
function paragraphTokens(body, section) {
  return splitSpans(body.slice(section.start, section.end), /\n[^\S\n]*\n/g)
    .map((s) => ({ start: section.start + s.start, end: section.start + s.end }));
}

/** Generic separator split that yields the spans BETWEEN matches, relative to `text`. */
function splitSpans(text, re) {
  const spans = [];
  let last = 0;
  let m;
  re.lastIndex = 0;
  while ((m = re.exec(text)) !== null) {
    spans.push({ start: last, end: m.index });
    last = m.index + m[0].length;
    if (m[0].length === 0) re.lastIndex++; // defensive: never loop on a zero-width match
  }
  spans.push({ start: last, end: text.length });
  return spans;
}

/** Greedy paragraph packer for one hard-break section (BookFactory.paginateGreedy). */
function packSection(body, section) {
  const pages = [];
  let current = '';
  let curStart = -1;
  let curEnd = -1;
  const flush = () => {
    if (current.length > 0) pages.push({ text: current, start: curStart, end: curEnd, exclusive: true });
    current = '';
    curStart = curEnd = -1;
  };
  for (const token of paragraphTokens(body, section)) {
    const para = body.slice(token.start, token.end).trim();
    if (para.length === 0) continue;
    if (para.length > MAX_CHARS_PER_PAGE) {
      flush();
      // One source paragraph, several pages: the span is shared, so no page owns it.
      for (const chunk of splitOversize(para)) {
        pages.push({ text: chunk, start: token.start, end: token.end, exclusive: false });
      }
      continue;
    }
    const needed = current.length === 0 ? para.length : current.length + 2 + para.length;
    if (needed > MAX_CHARS_PER_PAGE) {
      flush();
      current = para;
      curStart = token.start;
      curEnd = token.end;
    } else {
      current = current.length > 0 ? current + '\n\n' + para : para;
      if (curStart < 0) curStart = token.start;
      curEnd = token.end;
    }
  }
  flush();
  return pages;
}

/** Paragraph too big for one page — sentence boundaries first, then words. */
function splitOversize(paragraph) {
  const out = [];
  let current = '';
  for (const sentence of paragraph.split(/(?<=[.!?])\s+/)) {
    if (sentence.length > MAX_CHARS_PER_PAGE) {
      if (current.length > 0) { out.push(current); current = ''; }
      for (const chunk of splitByWord(sentence)) out.push(chunk);
      continue;
    }
    const needed = current.length === 0 ? sentence.length : current.length + 1 + sentence.length;
    if (needed > MAX_CHARS_PER_PAGE) {
      out.push(current);
      current = sentence;
    } else {
      current = current.length > 0 ? current + ' ' + sentence : sentence;
    }
  }
  if (current.length > 0) out.push(current);
  return out;
}

/** Last resort — a sentence longer than a page. Pack words; hard-break a single oversize word. */
function splitByWord(sentence) {
  const out = [];
  let current = '';
  for (const word of sentence.split(/\s+/)) {
    if (word.length > MAX_CHARS_PER_PAGE) {
      if (current.length > 0) { out.push(current); current = ''; }
      for (let idx = 0; idx < word.length; idx += MAX_CHARS_PER_PAGE) {
        out.push(word.slice(idx, Math.min(word.length, idx + MAX_CHARS_PER_PAGE)));
      }
      continue;
    }
    const needed = current.length === 0 ? word.length : current.length + 1 + word.length;
    if (needed > MAX_CHARS_PER_PAGE) {
      out.push(current);
      current = word;
    } else {
      current = current.length > 0 ? current + ' ' + word : word;
    }
  }
  if (current.length > 0) out.push(current);
  return out;
}

// --- editing -----------------------------------------------------------------

/**
 * Replace the source text behind `page` with `text`, returning the new body. Everything outside
 * `[page.start, page.end)` is preserved byte-for-byte — which is the whole point: an edit to one
 * page can never reformat the rest of a book.
 */
export function splicePage(body, page, text) {
  return body.slice(0, page.start) + text + body.slice(page.end);
}

/** The raw source text behind a page (untrimmed) — what the page editor puts in front of the author. */
export function pageSource(body, page) {
  return body.slice(page.start, page.end);
}

// --- keybind tokens ----------------------------------------------------------
// The mod resolves {key.<binding>} to each reader's OWN bound key at render (BookText.toPage). The
// editor has no keybinds, so show vanilla 1.21 defaults; an unknown binding prettifies its name.

const DEFAULT_KEYS = {
  'key.advancements': 'L', 'key.inventory': 'E', 'key.drop': 'Q', 'key.chat': 'T', 'key.command': '/',
  'key.sneak': 'Shift', 'key.sprint': 'Ctrl', 'key.jump': 'Space', 'key.attack': 'LMB',
  'key.use': 'RMB', 'key.pickItem': 'MMB', 'key.swapOffhand': 'F', 'key.playerlist': 'Tab',
  'key.forward': 'W', 'key.back': 'S', 'key.left': 'A', 'key.right': 'D',
  'key.screenshot': 'F2', 'key.togglePerspective': 'F5',
};

const KEYBIND_TOKEN = /\{(key\.[a-zA-Z0-9_.]+)\}/g;

/** Pretty label for an unmapped binding: last dotted segment, underscores→spaces, Title Case. */
function prettifyBinding(binding) {
  const tail = String(binding).split('.').pop() || binding;
  return tail.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

/** Replace {key.*} tokens with a display key, mirroring the in-game render. */
export function resolveKeybinds(text) {
  if (typeof text !== 'string' || text.indexOf('{key.') < 0) return text || '';
  return text.replace(KEYBIND_TOKEN, (_, binding) => DEFAULT_KEYS[binding] || `[${prettifyBinding(binding)}]`);
}
