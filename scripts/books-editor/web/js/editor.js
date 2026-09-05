/**
 * editor.js — application state and wiring for the book editor.
 *
 * The model is deliberately small: one open book (the parsed JSON exactly as it sits on disk), a
 * selected letter / variant / page, and a dirty flag. Every edit produces a NEW data object rather
 * than mutating the loaded one, so "what would be written" is always one value you can inspect, and
 * an edit to page N only ever rewrites the source span behind page N (see paginate.js#splicePage).
 *
 * Two things are deliberately read-only: the locale view (translations belong to the import
 * pipeline, not to this tool) and the %TOKEN% preview (substituted text must never be written back
 * over the template).
 */

import { listCorpus, readBook, readLocale, saveBook, moveBook } from './api.js';
import { createLibrary, contextLabel } from './library.js';
import { createFrame, measurePage, SAFE_LINE_CHARS, SAFE_LINES } from './book-frame.js';
import {
  paginateSpans, splicePage, pageSource, resolveKeybinds,
  MAX_CHARS_PER_PAGE, MAX_PAGES, MAX_TITLE_CHARS, EXPLICIT, PAGE_BREAK_TEXT,
} from './paginate.js';
import { TEMPLATE_CONTEXTS, substitute, tokensIn } from './tokens.js';

const state = {
  books: [],
  contexts: [],
  assets: [],
  current: null,      // { path, corpus, shape, mode, context, data, style, mtime, locales, warnings }
  letterIdx: 0,
  variantIdx: 0,
  pageIdx: 0,
  caret: null,        // where to put the caret on the next paint (null = leave it alone)
  dirty: false,
  tokensOn: false,
  locale: '',         // '' = the English source; otherwise a read-only translation
  localeData: null,
  status: '',
};

let library;
let frame;
const dom = {};

// --- data helpers (immutable) ------------------------------------------------

const isStory = (book) => book && book.shape === 'story';

/** The object holding `variants[]` — the book itself, or the selected letter for a story. */
function holder(book = state.current, data = null) {
  const source = data || (state.locale ? state.localeData : book.data);
  if (!source) return null;
  if (!isStory(book)) return source;
  const letters = source.letters || [];
  return letters[Math.min(state.letterIdx, letters.length - 1)] || null;
}

function variants(book = state.current) {
  const held = holder(book);
  return (held && held.variants) || [];
}

function body() {
  const list = variants();
  return list[Math.min(state.variantIdx, list.length - 1)] || '';
}

function withVariants(data, shape, letterIdx, list) {
  if (shape !== 'story') return { ...data, variants: list };
  return { ...data, letters: data.letters.map((l, i) => (i === letterIdx ? { ...l, variants: list } : l)) };
}

function setBody(next) {
  const list = variants().map((v, i) => (i === state.variantIdx ? next : v));
  state.current = { ...state.current, data: withVariants(state.current.data, state.current.shape, state.letterIdx, list) };
  state.dirty = true;
}

function setField(key, value) {
  const data = state.current.data;
  if (isStory(state.current) && (key === 'label')) {
    const letters = data.letters.map((l, i) => (i === state.letterIdx ? { ...l, label: value } : l));
    state.current = { ...state.current, data: { ...data, letters } };
  } else {
    state.current = { ...state.current, data: { ...data, [key]: value } };
  }
  state.dirty = true;
  repaint();
}

// --- pagination view ---------------------------------------------------------

function pages() {
  return paginateSpans(body(), state.current ? state.current.mode : 'flow');
}

function currentPage(list = pages()) {
  return list[Math.min(state.pageIdx, list.length - 1)] || list[0];
}

/** True when the page's text must be shown substituted / translated rather than edited. */
const readOnlyView = () => Boolean(state.locale) || state.tokensOn;

// --- loading -----------------------------------------------------------------

async function refreshLibrary(selectPath) {
  const payload = await listCorpus();
  state.books = payload.books;
  state.contexts = payload.contexts;
  state.assets = payload.assets;
  // The vanilla sheet needs cropping in CSS; dp-relay ships the book already cropped.
  document.body.classList.toggle('no-textures', !payload.assets['book.png']);
  document.body.classList.toggle('no-arrows', !payload.assets['page_forward.png']);
  document.body.classList.toggle('sheet-texture', payload.assets['book.png'] === 'jar');
  library.setBooks(state.books);
  if (selectPath) await openBook(selectPath);
}

async function openBook(path, { keepPosition = false } = {}) {
  if (state.dirty && !confirm('This book has unsaved changes. Discard them?')) return;
  const loaded = await readBook(path);
  const entry = state.books.find((b) => b.path === path) || {};
  state.current = { ...loaded, shape: entry.shape || (loaded.corpus === 'stories' ? 'story' : 'book') };
  if (!keepPosition) { state.letterIdx = 0; state.variantIdx = 0; state.pageIdx = 0; }
  state.dirty = false;
  state.locale = '';
  state.localeData = null;
  state.status = '';
  location.hash = encodeURIComponent(path);
  library.setCurrent(path);
  repaint();
}

async function save() {
  const pending = frame.flush();
  if (pending !== null) applyEdit(pending.text, pending.caret, { repaint: false });
  if (!state.current) return;
  try {
    const res = await saveBook(state.current.path, state.current.data, state.current.style, state.current.mtime);
    state.current = { ...state.current, mtime: res.mtime };
    state.dirty = false;
    state.status = `Saved ${state.current.path}`;
    await refreshLibrary();
    library.setCurrent(state.current.path);
  } catch (err) {
    if (err.status === 409 && confirm(`${err.message}\n\nReload the file from disk and lose this edit?`)) {
      state.dirty = false;
      await openBook(state.current.path, { keepPosition: true });
      return;
    }
    state.status = `Save failed: ${err.message}`;
  }
  repaint();
}

// --- editing -----------------------------------------------------------------

/**
 * Splice the edited text back over the page's source span, then follow the caret. An edit can push
 * text onto another page — a starting-book page break typed mid-page, or a flow page reflowing past
 * the char budget — and the view has to go where the author's text went, or their words look lost.
 */
function applyEdit(text, caret = null, { repaint: doRepaint = true } = {}) {
  if (!state.current || readOnlyView()) return;
  const page = currentPage();
  const next = splicePage(body(), page, text);
  if (next === body()) return;
  const absolute = Number.isInteger(caret) ? page.start + caret : null;
  setBody(next);
  if (absolute !== null) {
    const repaginated = paginateSpans(next, state.current.mode);
    const idx = repaginated.findIndex((p) => absolute >= p.start && absolute <= p.end);
    if (idx >= 0) {
      state.pageIdx = idx;
      state.caret = absolute - repaginated[idx].start;
    }
  }
  if (doRepaint) repaint({ keepFocus: true });
}

function navigate(delta) {
  const count = pages().length;
  state.pageIdx = Math.max(0, Math.min(count - 1, state.pageIdx + delta));
  state.caret = 0;
  repaint();
}

/** Page tools operate on the source spans, so they never disturb text outside the pages involved. */
function insertPageAfter(blank = false) {
  const page = currentPage();
  const text = blank ? '' : 'New page';
  const source = body();
  // Starting books break on a %PAGE% line; flow books on three newlines.
  const sep = state.current.mode === EXPLICIT ? PAGE_BREAK_TEXT : '\n\n\n';
  setBody(source.slice(0, page.end) + sep + text + source.slice(page.end));
  state.pageIdx += blank ? 1 : 1;
  repaint();
}

function deletePage() {
  const list = pages();
  if (list.length <= 1) { state.status = 'A book needs at least one page.'; repaint(); return; }
  const page = currentPage(list);
  // A page that shares its source chunk (an oversize paragraph the game spills over several pages)
  // cannot be deleted alone — the chunk is the unit. Say how many pages go before doing it.
  if (!page.exclusive) {
    const spilled = list.filter((p) => p.start === page.start && p.end === page.end).length;
    if (!confirm(`This page is part of one oversize chunk that the game spills across ${spilled} `
      + 'pages. Deleting it removes all of them. Continue?')) return;
  }
  const source = body();
  let start = page.start;
  let end = page.end;
  // Take the separator that joined this page to a neighbour: one %PAGE% marker in explicit mode,
  // the whole newline run between two paragraphs in flow mode.
  const explicit = state.current.mode === EXPLICIT;
  const afterRe = explicit ? /^(?:\r?\n)?[ \t]*%PAGE%[ \t]*(?:\r?\n)?/ : /^\n{2,}/;
  const beforeRe = explicit ? /(?:\r?\n)?[ \t]*%PAGE%[ \t]*(?:\r?\n)?$/ : /\n{2,}$/;
  const runAfter = source.slice(end).match(afterRe);
  const runBefore = source.slice(0, start).match(beforeRe);
  if (runAfter) end += runAfter[0].length;
  else if (runBefore) start -= runBefore[0].length;
  setBody(source.slice(0, start) + source.slice(end));
  state.pageIdx = Math.max(0, state.pageIdx - (state.pageIdx >= list.length - 1 ? 1 : 0));
  repaint();
}

function movePage(delta) {
  const list = pages();
  const from = Math.min(state.pageIdx, list.length - 1);
  const to = from + delta;
  if (to < 0 || to >= list.length) return;
  const a = list[Math.min(from, to)];
  const b = list[Math.max(from, to)];
  const source = body();
  const next = source.slice(0, a.start) + source.slice(b.start, b.end)
    + source.slice(a.end, b.start) + source.slice(a.start, a.end) + source.slice(b.end);
  setBody(next);
  state.pageIdx = to;
  repaint();
}

/** Merge this page with the next by softening the break between them into a blank line. */
function mergeWithNext() {
  const list = pages();
  const page = list[state.pageIdx];
  const next = list[state.pageIdx + 1];
  if (!next) return;
  const source = body();
  // Softening the break leaves a blank line where the page turn was.
  setBody(source.slice(0, page.end) + '\n\n' + source.slice(next.start));
  repaint();
}

function addVariant(duplicate) {
  const list = variants();
  const next = [...list, duplicate ? list[state.variantIdx] : 'A new variant.'];
  state.current = { ...state.current, data: withVariants(state.current.data, state.current.shape, state.letterIdx, next) };
  state.variantIdx = next.length - 1;
  state.pageIdx = 0;
  state.dirty = true;
  repaint();
}

function deleteVariant() {
  const list = variants();
  if (list.length <= 1) { state.status = 'A book needs at least one variant.'; repaint(); return; }
  if (!confirm(`Delete variant ${state.variantIdx + 1} of ${list.length}?`)) return;
  const next = list.filter((_, i) => i !== state.variantIdx);
  state.current = { ...state.current, data: withVariants(state.current.data, state.current.shape, state.letterIdx, next) };
  state.variantIdx = Math.max(0, state.variantIdx - 1);
  state.pageIdx = 0;
  state.dirty = true;
  repaint();
}

async function moveContext(context) {
  const book = state.current;
  const to = ['starting_books', context, `${book.path.split('/').pop()}`].filter(Boolean).join('/');
  if (to === book.path) return;
  if (state.dirty) { alert('Save this book before moving it.'); return; }
  try {
    await moveBook(book.path, to);
    await refreshLibrary(to);
  } catch (err) {
    alert(`Could not move: ${err.message}`);
  }
}

async function showLocale(locale) {
  state.locale = locale;
  state.localeData = null;
  if (locale) {
    try {
      const res = await readLocale(state.current.path, locale);
      state.localeData = res.data;
    } catch (err) {
      state.status = `No ${locale} translation: ${err.message}`;
      state.locale = '';
    }
  }
  state.pageIdx = 0;
  repaint();
}

// --- rendering ---------------------------------------------------------------

/** Terse element builder: `class` / `on*` handlers / properties / attributes, then children. */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key in node) node[key] = value;
    else node.setAttribute(key, value);
  }
  node.append(...children.filter((c) => c !== null && c !== undefined));
  return node;
}

function repaint({ keepFocus = false } = {}) {
  if (!state.current) return;
  renderMeta();
  renderControls();
  renderFrame(keepFocus);
  renderSide();
  dom.save.disabled = !state.dirty;
  dom.dirty.hidden = !state.dirty;
  dom.status.textContent = state.status;
}

function titleFor(book, data) {
  if (isStory(book)) {
    const story = data.story;
    const held = holder(book, data);
    return story && story !== 'Untitled' ? story : (held && held.label) || book.id;
  }
  return data.title && data.title !== 'Untitled' ? data.title : book.path.split('/').pop().replace(/\.json$/, '');
}

function renderMeta() {
  const book = state.current;
  const data = state.locale ? state.localeData : book.data;
  const fields = [];
  const text = (label, key, value, opts = {}) => el('label', { class: 'field' },
    el('span', { textContent: label }),
    el('input', {
      type: opts.type || 'text', value: value ?? '', disabled: Boolean(state.locale),
      onchange: (e) => setField(key, opts.type === 'number' ? Number(e.target.value) : e.target.value),
    }));

  if (isStory(book)) {
    fields.push(text('Story title', 'story', data.story), text('Character (author)', 'character', data.character));
    const held = holder(book, data);
    fields.push(text('Letter label', 'label', held ? held.label : ''));
  } else {
    fields.push(text('Title', 'title', data.title), text('Author', 'author', data.author));
    fields.push(text('Weight', 'weight', data.weight ?? 1, { type: 'number' }));
    fields.push(text('Generation', 'generation', data.generation ?? 0, { type: 'number' }));
  }

  const badges = [];
  const shown = titleFor(book, data);
  const substituted = state.tokensOn ? substitute(shown) : shown;
  if (substituted.length > MAX_TITLE_CHARS) {
    badges.push(el('span', { class: 'badge warn', textContent: `title ${substituted.length}/${MAX_TITLE_CHARS} — clamped in game` }));
  }
  if (book.locales.length) {
    badges.push(el('span', { class: 'badge', title: book.locales.join(', '),
      textContent: `${book.locales.length} translations — editing English leaves them stale` }));
  }
  if (book.style && book.style.exact === false) {
    badges.push(el('span', { class: 'badge warn', textContent: 'hand-formatted file — saving reformats it' }));
  }
  for (const warning of book.warnings || []) badges.push(el('span', { class: 'badge warn', textContent: warning }));

  dom.meta.replaceChildren(
    el('div', { class: 'path' }, el('code', { textContent: book.path }),
      state.locale ? el('span', { class: 'badge locale', textContent: `${state.locale} · read-only` }) : null),
    el('div', { class: 'fields' }, ...fields),
    el('div', { class: 'badges' }, ...badges),
  );
}

function renderControls() {
  const book = state.current;
  const data = state.locale ? state.localeData : book.data;
  const controls = [];

  if (isStory(book)) {
    const letters = data.letters || [];
    controls.push(el('label', { class: 'picker' }, el('span', { textContent: 'Letter' }),
      el('select', {
        onchange: (e) => { state.letterIdx = Number(e.target.value); state.variantIdx = 0; state.pageIdx = 0; repaint(); },
      }, ...letters.map((l, i) => el('option', {
        value: String(i), selected: i === state.letterIdx,
        textContent: `${l.index}. ${l.label || 'Untitled'} (${(l.variants || []).length})`,
      })))));
  }

  const list = variants();
  controls.push(el('div', { class: 'variant-picker' },
    el('span', { textContent: 'Variant' }),
    ...list.map((_, i) => el('button', {
      type: 'button', class: `dot${i === state.variantIdx ? ' on' : ''}`, textContent: String(i + 1),
      onclick: () => { state.variantIdx = i; state.pageIdx = 0; repaint(); },
    })),
    state.locale ? null : el('button', { type: 'button', class: 'mini', textContent: '+', title: 'Add variant', onclick: () => addVariant(false) }),
    state.locale ? null : el('button', { type: 'button', class: 'mini', textContent: '⧉', title: 'Duplicate variant', onclick: () => addVariant(true) }),
    state.locale ? null : el('button', { type: 'button', class: 'mini', textContent: '␡', title: 'Delete variant', onclick: deleteVariant }),
  ));

  if (book.corpus === 'starting') {
    controls.push(el('label', { class: 'picker' }, el('span', { textContent: 'Context' }),
      el('select', { onchange: (e) => moveContext(e.target.value) },
        ...state.contexts.map((ctx) => el('option', {
          value: ctx, selected: ctx === (book.context || ''), textContent: contextLabel(ctx),
        })))));
  }

  const templated = book.corpus === 'starting' && TEMPLATE_CONTEXTS.has(book.context || '');
  if (templated || tokensIn(body()).length) {
    controls.push(el('label', { class: 'toggle' },
      el('input', { type: 'checkbox', checked: state.tokensOn, onchange: (e) => { state.tokensOn = e.target.checked; repaint(); } }),
      el('span', { textContent: 'Preview %TOKENS% (read-only)' })));
  }

  if (book.locales.length) {
    controls.push(el('label', { class: 'picker' }, el('span', { textContent: 'Locale' }),
      el('select', { onchange: (e) => showLocale(e.target.value) },
        el('option', { value: '', selected: !state.locale, textContent: 'English (source)' }),
        ...book.locales.map((loc) => el('option', { value: loc, selected: loc === state.locale, textContent: loc })))));
  }

  dom.controls.replaceChildren(...controls);
}

function renderFrame(keepFocus) {
  const list = pages();
  state.pageIdx = Math.min(state.pageIdx, list.length - 1);
  const page = currentPage(list);
  const source = pageSource(body(), page);
  const rendered = resolveKeybinds(state.tokensOn ? substitute(page.text) : page.text);
  frame.update({
    source: readOnlyView() ? rendered : source,
    rendered,
    pageIdx: state.pageIdx,
    pageCount: list.length,
    readOnly: readOnlyView(),
    shared: !page.exclusive,
    caret: state.caret,
  });
  state.caret = null;
  if (keepFocus) frame.focus();

  const tools = [];
  const tool = (label, title, fn, disabled = false) =>
    el('button', { type: 'button', title, textContent: label, disabled, onclick: fn });
  if (!readOnlyView()) {
    tools.push(
      tool('◀ move', page.exclusive ? 'Move this page earlier'
        : 'This page shares an oversize chunk with its neighbours — nothing to move on its own',
        () => movePage(-1), state.pageIdx === 0 || !page.exclusive),
      tool('move ▶', page.exclusive ? 'Move this page later'
        : 'This page shares an oversize chunk with its neighbours — nothing to move on its own',
        () => movePage(1), state.pageIdx >= list.length - 1 || !page.exclusive),
      tool('+ page', 'Insert a page after this one', () => insertPageAfter(false)),
      tool('+ blank', 'Insert an intentional blank page after this one', () => insertPageAfter(true)),
      tool('␡ page', 'Delete this page', deletePage),
    );
    tools.push(tool('merge ▶', 'Soften the break into a blank line so this page joins the next',
      mergeWithNext, state.pageIdx >= list.length - 1));
  }
  dom.tools.replaceChildren(...tools);
}

function renderSide() {
  const list = pages();
  const page = currentPage(list);
  const rendered = state.tokensOn ? substitute(page.text) : page.text;
  const stats = measurePage(rendered);

  const notes = [];
  if (!page.exclusive) {
    notes.push('This page comes from one oversize chunk that spills across pages — editing it '
      + 'rewrites the whole chunk, and the mod re-splits it the same way.');
  }
  if (stats.chars > MAX_CHARS_PER_PAGE) {
    notes.push(`${stats.chars} chars — over the ${MAX_CHARS_PER_PAGE} budget, so the game spills this into extra pages.`);
  }
  if (stats.longLines) {
    notes.push(`${stats.longLines} line(s) longer than ~${SAFE_LINE_CHARS} chars — Minecraft will wrap them.`);
  }
  if (stats.wrapped > SAFE_LINES) {
    notes.push(`wraps to about ${stats.wrapped} lines — more than the ~${SAFE_LINES} the page shows, `
      + 'so the tail runs off the bottom in game even though it fits the char budget.');
  }
  if (list.length > MAX_PAGES) {
    notes.push(`${list.length} pages — the game truncates at ${MAX_PAGES}.`);
  }
  if (state.current.mode === EXPLICIT && page.text.includes('\n\n')) {
    notes.push('Blank lines stay on the page — starting books break only on a %PAGE% line, '
      + 'which the page buttons below write for you.');
  }
  if (!readOnlyView() && pageSource(body(), page).trim() !== page.text) {
    notes.push('The game trims / packs this page, so the render differs slightly from the source.');
  }

  const strip = el('div', { class: 'page-strip' }, ...list.map((p, i) => el('button', {
    type: 'button', class: `thumb${i === state.pageIdx ? ' on' : ''}`,
    onclick: () => { state.pageIdx = i; repaint(); },
  }, el('span', { class: 'thumb-no', textContent: String(i + 1) }),
     el('span', { class: 'thumb-text',
       textContent: (state.tokensOn ? substitute(p.text) : p.text).split('\n')[0].slice(0, 24) || '—' }))));

  dom.side.replaceChildren(
    el('h3', { textContent: `Pages (${list.length})` }),
    strip,
    el('div', { class: 'stats', textContent: `page ${state.pageIdx + 1}: ${stats.chars}/${MAX_CHARS_PER_PAGE} chars · `
      + `${stats.lines} written lines · ~${stats.wrapped}/${SAFE_LINES} shown` }),
    ...notes.map((n) => el('p', { class: 'note', textContent: n })),
    el('h3', { textContent: 'Variant source' }),
    el('pre', { class: 'raw', textContent: JSON.stringify(body()) }),
  );
}

// --- boot --------------------------------------------------------------------

export async function boot() {
  dom.meta = document.getElementById('meta');
  dom.controls = document.getElementById('controls');
  dom.tools = document.getElementById('tools');
  dom.side = document.getElementById('side');
  dom.save = document.getElementById('save');
  dom.dirty = document.getElementById('dirty');
  dom.status = document.getElementById('status');

  library = createLibrary({
    onOpen: (path) => openBook(path).catch((e) => alert(e.message)),
    onCreated: (path) => refreshLibrary(path),
  });
  document.getElementById('library-host').append(library.node);

  frame = createFrame({ onEdit: applyEdit, onNav: navigate });
  document.getElementById('stage').prepend(frame.node);

  dom.save.addEventListener('click', save);
  window.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 's') { e.preventDefault(); save(); }
  });
  window.addEventListener('beforeunload', (e) => { if (state.dirty) e.preventDefault(); });

  await refreshLibrary();
  const wanted = decodeURIComponent(location.hash.slice(1));
  const first = state.books.find((b) => b.path === wanted) || state.books[0];
  if (first) await openBook(first.path);
}
