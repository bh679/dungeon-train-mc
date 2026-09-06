/**
 * book-frame.js — the vanilla BookViewScreen, but you can type in it.
 *
 * Renders one page on the real `book.png` parchment at vanilla's page-area offsets, with the game's
 * own page-turn arrow sprites and the "Page X of Y" indicator, and puts a transparent textarea over
 * the page area so the page IS the editor. Modelled on dp-relay's read-only `book-viewer.js`; the
 * editing, page tools and overflow warnings are what this adds.
 *
 * The textarea holds the page's SOURCE text (what is in the JSON), not the packed render — that is
 * what an author needs to edit. Where the two differ (the flow paginator trims and packs), the
 * caller surfaces it as a note under the frame rather than silently showing something else.
 *
 * State lives with the caller (editor.js). This module owns DOM and events only:
 *   update(view)   paint a view — see the `view` fields in {@link createFrame}
 *   onEdit(text)   fires (debounced) when the author types
 *   onNav(delta)   fires on arrow click / keyboard page turn
 */

const DEBOUNCE_MS = 120;

export function createFrame({ onEdit, onNav }) {
  const node = document.createElement('div');
  node.className = 'frame-wrap';
  node.innerHTML = `
    <div class="book" role="group" aria-label="Book page">
      <div class="page-indicator"></div>
      <div class="page-area">
        <textarea class="page-edit" spellcheck="true" aria-label="Page text"></textarea>
      </div>
      <button class="arrow back" type="button" aria-label="Previous page"></button>
      <button class="arrow fwd" type="button" aria-label="Next page"></button>
    </div>`;

  const book = node.querySelector('.book');
  const area = node.querySelector('.page-area');
  const edit = node.querySelector('.page-edit');
  const indicator = node.querySelector('.page-indicator');
  const back = node.querySelector('.arrow.back');
  const fwd = node.querySelector('.arrow.fwd');

  let timer = null;
  // The caret goes out with the text: an edit can re-flow the page (or split it in two), and the
  // caller uses the caret to keep the view on whichever page the author's text ended up on.
  edit.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => onEdit(edit.value, edit.selectionStart), DEBOUNCE_MS);
  });
  // Flush a pending edit before the caller acts on anything else (save, page turn, variant switch).
  edit.addEventListener('blur', () => {
    if (timer) { clearTimeout(timer); timer = null; onEdit(edit.value, edit.selectionStart); }
  });

  back.addEventListener('click', () => onNav(-1));
  fwd.addEventListener('click', () => onNav(1));
  node.addEventListener('keydown', (e) => {
    // Plain arrows belong to the text cursor; page turns are alt+arrow, as in "flip the page".
    if (!e.altKey || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
    e.preventDefault();
    onNav(e.key === 'ArrowRight' ? 1 : -1);
  });

  /**
   * Paint one page.
   *   view.source      the page's source text (goes in the textarea)
   *   view.rendered    the page as the game renders it (for the overflow / line measurements)
   *   view.pageIdx     0-based page being shown
   *   view.pageCount   total pages this variant produces
   *   view.readOnly    true in the locale view — translations are not editable here
   *   view.shared      true when this page is part of an oversize chunk (edit rewrites the chunk)
   *   view.caret       where to put the caret (defaults to holding its current position)
   */
  function update(view) {
    const { source = '', pageIdx = 0, pageCount = 1, readOnly = false, shared = false } = view;
    if (edit.value !== source) {
      // Assigning `value` collapses the selection to the end, which would fling the caret to the
      // bottom of the page on every keystroke that reflows the text. Put it back.
      const focused = document.activeElement === edit;
      const wanted = Number.isInteger(view.caret) ? view.caret : edit.selectionStart;
      edit.value = source;
      if (focused) {
        const at = Math.max(0, Math.min(source.length, wanted));
        edit.setSelectionRange(at, at);
      }
    } else if (Number.isInteger(view.caret) && document.activeElement === edit) {
      const at = Math.max(0, Math.min(source.length, view.caret));
      if (edit.selectionStart !== at) edit.setSelectionRange(at, at);
    }
    edit.readOnly = readOnly;
    area.classList.toggle('shared', shared);
    book.classList.toggle('read-only', readOnly);
    indicator.textContent = pageCount > 1 ? `Page ${pageIdx + 1} of ${pageCount}` : '';
    back.hidden = pageIdx <= 0;
    fwd.hidden = pageIdx >= pageCount - 1;
  }

  /** Move the caret into the page — used when the author picks a page from the strip. */
  function focus() {
    edit.focus({ preventScroll: true });
  }

  /** Hand the in-flight text back without waiting for the debounce (used before save). */
  function flush() {
    if (!timer) return null;
    clearTimeout(timer);
    timer = null;
    return { text: edit.value, caret: edit.selectionStart };
  }

  return { node, update, focus, flush };
}

/**
 * Per-page measurements the author needs while writing, mirroring the engine's limits: the 256-char
 * page budget (BookFactory.MAX_CHARS_PER_PAGE) and the ~19-char safe line width of the proportional
 * Minecraft font (BookFactory's ~14 lines × ~19 chars note).
 */
export const SAFE_LINE_CHARS = 19;
export const SAFE_LINES = 14;

export function measurePage(rendered) {
  const lines = (rendered || '').split('\n');
  return {
    chars: (rendered || '').length,
    lines: lines.length,
    longLines: lines.filter((l) => l.length > SAFE_LINE_CHARS).length,
    wrapped: wrappedLineCount(lines),
  };
}

/**
 * How many lines the page actually occupies once Minecraft's proportional font wraps it at ~19
 * chars. A page can sit inside the 256-char budget and STILL run off the bottom of the visible page
 * — the budget is a storage limit, the 14 lines are what the player can see.
 */
function wrappedLineCount(lines) {
  let count = 0;
  for (const line of lines) {
    let used = 0;
    let taken = 1;
    for (const word of line.split(' ')) {
      const width = word.length || 1;
      if (used === 0) used = width;
      else if (used + 1 + width <= SAFE_LINE_CHARS) used += 1 + width;
      else { taken += 1; used = width; }
      while (used > SAFE_LINE_CHARS) { taken += 1; used -= SAFE_LINE_CHARS; }
    }
    count += taken;
  }
  return count;
}
