/**
 * library.js — the sidebar: every book in the three corpora, filterable, plus the new-book dialog.
 *
 * Starting books are grouped by their lifecycle context folder because that folder IS the routing
 * (starting_books/CLAUDE.md §1) — a book in the wrong group fires at the wrong moment, so the
 * grouping is the first thing an author should see.
 */

import { createBook } from './api.js';

const CORPUS_LABEL = { starting: 'Starting books', random: 'Random books', stories: 'Narrative stories' };
const CONTEXT_LABEL = { '': 'DEFAULT (first play)' };

export const contextLabel = (ctx) => CONTEXT_LABEL[ctx] ?? ctx.replace(/_/g, ' ');

/** Group key for a book row — corpus, subdivided by context for starting books. */
function groupOf(book) {
  if (book.corpus !== 'starting') return CORPUS_LABEL[book.corpus] || book.corpus;
  return `${CORPUS_LABEL.starting} · ${contextLabel(book.context || '')}`;
}

export function createLibrary({ onOpen, onCreated }) {
  const node = document.createElement('div');
  node.className = 'library';
  node.innerHTML = `
    <div class="library-head">
      <input class="filter" type="search" placeholder="Filter books…" aria-label="Filter books">
      <button class="new-book" type="button" title="New book">+</button>
    </div>
    <div class="library-list"></div>`;

  const filter = node.querySelector('.filter');
  const list = node.querySelector('.library-list');
  let books = [];
  let currentPath = null;

  filter.addEventListener('input', paint);
  node.querySelector('.new-book').addEventListener('click', () => newBookDialog(books, onCreated));

  function matches(book, needle) {
    if (!needle) return true;
    return [book.id, book.title, book.author, book.path, book.context]
      .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
  }

  function paint() {
    const needle = filter.value.trim().toLowerCase();
    const groups = new Map();
    for (const book of books) {
      if (!matches(book, needle)) continue;
      const key = groupOf(book);
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(book);
    }
    list.replaceChildren(...[...groups].map(([label, rows]) => {
      const section = document.createElement('section');
      const heading = document.createElement('h2');
      heading.textContent = `${label} (${rows.length})`;
      section.append(heading, ...rows.map(row));
      return section;
    }));
    if (!groups.size) {
      const empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'No book matches that.';
      list.replaceChildren(empty);
    }
  }

  function row(book) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'book-row';
    item.classList.toggle('active', book.path === currentPath);
    const count = book.letters ? `${book.letters} letters · ${book.count} variants`
      : `${book.count} variant${book.count === 1 ? '' : 's'}`;
    item.innerHTML = `
      <span class="row-title"></span>
      <span class="row-meta"></span>`;
    item.querySelector('.row-title').textContent = book.title || book.id;
    item.querySelector('.row-meta').textContent =
      `${book.id} · ${count}${book.locales ? ` · ${book.locales} locales` : ''}`;
    if (!book.valid) item.classList.add('invalid');
    if (book.warnings && book.warnings.length) item.title = book.warnings.join('\n');
    item.addEventListener('click', () => onOpen(book.path));
    return item;
  }

  return {
    node,
    setBooks(next) { books = next; paint(); },
    setCurrent(path) { currentPath = path; paint(); },
  };
}

/**
 * New book. The filename is the identity everywhere in the mod (RandomBookFile.basename()), so it is
 * validated to the ResourceLocation charset here rather than after a failed save; the context folder
 * picker exists because for starting books that folder decides when the book can ever fire.
 */
async function newBookDialog(books, onCreated) {
  const id = (prompt('Filename (a-z, 0-9, . _ - — no extension):') || '').trim();
  if (!id) return;
  if (!/^[a-z0-9._-]+$/.test(id)) {
    alert(`'${id}' is not a valid ResourceLocation path — the registry would skip the book.`);
    return;
  }
  const corpusKey = (prompt('Corpus: starting / random / stories', 'starting') || '').trim();
  const dir = { starting: 'starting_books', random: 'random_books', stories: 'stories' }[corpusKey];
  if (!dir) return;
  let folder = '';
  if (corpusKey === 'starting') {
    folder = (prompt('Context folder (blank = DEFAULT):\n'
      + 'new_world, joined_world, respawn, nether, end,\n'
      + 'cursed, cursed_fulfilled, cursed_defied, loved, loved_betrayed, loved_turned', '') || '').trim();
  }
  const path = [dir, folder, `${id}.json`].filter(Boolean).join('/');
  if (books.some((b) => b.path === path)) { alert(`${path} already exists.`); return; }

  const data = corpusKey === 'stories'
    ? { id, character: 'Anonymous', story: 'Untitled', letters: [{ index: 1, label: 'Letter One', variants: [''] }] }
    : { id, title: 'Untitled', author: 'Brennan Hatton', generation: 0, weight: 1, variants: [''] };
  // A brand-new book needs one non-empty variant to satisfy the loader; seed a placeholder line.
  if (corpusKey === 'stories') data.letters[0].variants = ['Write the first page here.'];
  else data.variants = ['Write the first page here.'];

  try {
    await createBook(path, data);
    onCreated(path);
  } catch (err) {
    alert(`Could not create ${path}: ${err.message}`);
  }
}
