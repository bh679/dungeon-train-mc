/**
 * paginate.test.mjs — the pagination port must match the mod's BookFactory / StartingBookFactory, or
 * the editor lies about where the page breaks fall. Cases mirror BookFactory's four-tier splitter and
 * the explicit paginator's `\n\n` rule (carried over from dp-relay's book-paginate.test.js), plus the
 * span/splice invariants this port adds for in-page editing.
 *
 *   node --test scripts/books-editor/web/js/paginate.test.mjs
 */

import test from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  paginate, paginateExplicit, paginateSpans, splicePage, resolveKeybinds,
  MAX_CHARS_PER_PAGE, FLOW, EXPLICIT,
} from './paginate.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const NARRATIVES = path.resolve(HERE, '../../../../src/main/resources/data/dungeontrain/narratives');

const under = (pages) => pages.every((p) => p.length <= MAX_CHARS_PER_PAGE);

// ---- flow paginator (random books + narrative letters) ----------------------

test('blank / empty body yields a single blank page (book stays openable)', () => {
  assert.deepStrictEqual(paginate(''), ['']);
  assert.deepStrictEqual(paginate(null), ['']);
  assert.deepStrictEqual(paginate('   '), ['']);
});

test('three-plus newlines force a hard page break; a double newline is a soft one', () => {
  assert.deepStrictEqual(paginate('Alpha\n\n\nBravo'), ['Alpha', 'Bravo']);
  assert.deepStrictEqual(paginate('A\n\n\n\n\nB'), ['A', 'B']);
  assert.deepStrictEqual(paginate('A\n\nB'), ['A\n\nB']);
});

test('greedy packer fills to the 256-char budget then breaks', () => {
  const p = 'x'.repeat(120);
  assert.strictEqual(paginate(`${p}\n\n${p}`).length, 1);        // 120 + 2 + 120 = 242
  assert.strictEqual(paginate(`${p}\n\n${p}\n\n${p}`).length, 2); // 364 spills
});

test('oversize paragraph splits on sentences, every page within budget', () => {
  const pages = paginate('This train never stops. '.repeat(30).trim());
  assert.ok(pages.length >= 3, `expected multiple pages, got ${pages.length}`);
  assert.ok(under(pages));
});

test('a single word longer than a page is hard char-split, losslessly', () => {
  const pages = paginate('z'.repeat(600));
  assert.ok(pages.length >= 3);
  assert.ok(under(pages));
  assert.strictEqual(pages.join(''), 'z'.repeat(600));
});

// ---- explicit paginator (starting / welcome books) --------------------------

test('paginateExplicit: blank / empty body yields a single blank page', () => {
  assert.deepStrictEqual(paginateExplicit(''), ['']);
  assert.deepStrictEqual(paginateExplicit(null), ['']);
});

test('paginateExplicit: a %PAGE% line is the page break', () => {
  assert.deepStrictEqual(paginateExplicit('Alpha\nBravo'), ['Alpha\nBravo']);
  assert.deepStrictEqual(paginateExplicit('A\n%PAGE%\nB\n%PAGE%\nC'), ['A', 'B', 'C']);
  assert.deepStrictEqual(paginateExplicit('A.\n   %PAGE%  \nB.'), ['A.', 'B.']);
});

test('paginateExplicit: newlines are content — any number of blank lines stay on the page', () => {
  assert.deepStrictEqual(paginateExplicit('Line one.\n\nLine two.'), ['Line one.\n\nLine two.']);
  assert.deepStrictEqual(paginateExplicit('A.\n\n\n\nB.'), ['A.\n\n\n\nB.']);
});

test('paginateExplicit: leading blank lines survive, pushing the page text down', () => {
  // One newline belongs to the marker; the rest are the author's layout.
  assert.deepStrictEqual(paginateExplicit('A.\n%PAGE%\n\n\nLower.'), ['A.', '\n\nLower.']);
});

test('paginateExplicit: two markers in a row are a blank-page slot; edge blanks are trimmed', () => {
  assert.deepStrictEqual(paginateExplicit('A\n%PAGE%\n%PAGE%\nB'), ['A', '', 'B']);
  assert.deepStrictEqual(paginateExplicit('A\n%PAGE%\n \n%PAGE%\nB'), ['A', ' ', 'B']);
  assert.deepStrictEqual(paginateExplicit('%PAGE%\nA\n%PAGE%\nB\n%PAGE%'), ['A', 'B']);
});

test('paginateExplicit: an oversize chunk falls back to the flow paginator', () => {
  const pages = paginateExplicit('This train never stops. '.repeat(30).trim());
  assert.ok(pages.length >= 3, `expected multiple pages, got ${pages.length}`);
  assert.ok(under(pages));
});

// ---- spans + splicing (what makes the preview editable) ---------------------

test('spans: each page reports the source text it was built from', () => {
  const body = 'Alpha\n%PAGE%\nBravo\n%PAGE%\nCharlie';
  const pages = paginateSpans(body, EXPLICIT);
  assert.deepStrictEqual(pages.map((p) => body.slice(p.start, p.end)), ['Alpha', 'Bravo', 'Charlie']);
  assert.ok(pages.every((p) => p.exclusive));
});

test('spans: splicing one page leaves every other byte of the body untouched', () => {
  const body = 'Alpha\n%PAGE%\nBravo\n%PAGE%\nCharlie';
  const pages = paginateSpans(body, EXPLICIT);
  assert.strictEqual(splicePage(body, pages[1], 'Bravo\n\n\nrewritten'),
    'Alpha\n%PAGE%\nBravo\n\n\nrewritten\n%PAGE%\nCharlie');
});

test('spans: a flow page packing several paragraphs spans all of them', () => {
  const body = 'One.\n\nTwo.\n\nThree.';
  const pages = paginateSpans(body, FLOW);
  assert.strictEqual(pages.length, 1); // all three pack onto one page
  assert.strictEqual(body.slice(pages[0].start, pages[0].end), body);
});

test('spans: an oversize chunk spilling across pages marks them NOT exclusive', () => {
  const pages = paginateSpans('This train never stops. '.repeat(30).trim(), EXPLICIT);
  assert.ok(pages.length > 1);
  assert.ok(pages.every((p) => !p.exclusive), 'a shared source span cannot be spliced per page');
  assert.strictEqual(pages[0].start, pages[pages.length - 1].start, 'all spill pages share one span');
});

test('spans: re-splicing every page with its own source text is the identity on the body', () => {
  for (const [body, mode] of [
    ['Alpha\n\nBravo\n\n\nCharlie', FLOW],
    ['A\n%PAGE%\n%PAGE%\nB\n%PAGE%\nC', EXPLICIT],
    ['   Padded.   \n%PAGE%\nMore.\n\n\n', EXPLICIT],
  ]) {
    for (const page of paginateSpans(body, mode)) {
      assert.strictEqual(splicePage(body, page, body.slice(page.start, page.end)), body);
    }
  }
});

// ---- keybind display --------------------------------------------------------

test('resolveKeybinds shows vanilla defaults; unknown bindings prettify', () => {
  assert.strictEqual(resolveKeybinds('Press {key.advancements}'), 'Press L');
  assert.strictEqual(resolveKeybinds('{key.inventory} then {key.drop}'), 'E then Q');
  assert.strictEqual(resolveKeybinds('Hit {key.super_move}'), 'Hit [Super Move]');
  assert.strictEqual(resolveKeybinds('no tokens here'), 'no tokens here');
});

// ---- integration over the real corpus ---------------------------------------

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

test('integration: a real random book paginates like in-game', () => {
  const quiet = readJson(path.join(NARRATIVES, 'random_books/quiet_rules.json'));
  const pages = paginate(quiet.variants[0]);
  assert.strictEqual(pages.length, 2);
  assert.ok(under(pages));
});

test('integration: blank_pages.json keeps its literal "." pages and trims the edges', () => {
  const blank = readJson(path.join(NARRATIVES, 'starting_books/blank_pages.json'));
  const pages = paginateExplicit(blank.variants[0]);
  assert.ok(pages.length >= 20, `expected many short pages, got ${pages.length}`);
  assert.ok(under(pages));
  assert.ok(pages.includes('.'));
  assert.notStrictEqual(pages[0], '');
  assert.notStrictEqual(pages[pages.length - 1], '');
});

test('integration: every variant in the shipped corpus round-trips through spans unchanged', () => {
  let variants = 0;
  const check = (body, mode) => {
    variants += 1;
    const pages = paginateSpans(body, mode);
    // the span API must agree with the string API the mod is ported from
    assert.deepStrictEqual(pages.map((p) => p.text),
      mode === EXPLICIT ? paginateExplicit(body) : paginate(body));
    for (const page of pages) {
      assert.ok(page.start >= 0 && page.end <= body.length && page.start <= page.end, 'span in range');
      assert.strictEqual(splicePage(body, page, body.slice(page.start, page.end)), body);
    }
  };
  const walk = (dir, mode, isStory) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) { walk(full, mode, isStory); continue; }
      if (!entry.name.endsWith('.json')) continue;
      const json = readJson(full);
      const bodies = isStory
        ? (json.letters || []).flatMap((l) => l.variants || [])
        : (json.variants || []);
      for (const body of bodies) check(body, mode);
    }
  };
  walk(path.join(NARRATIVES, 'starting_books'), EXPLICIT, false);
  walk(path.join(NARRATIVES, 'random_books'), FLOW, false);
  walk(path.join(NARRATIVES, 'stories'), FLOW, true);
  assert.ok(variants > 300, `expected the whole corpus, only saw ${variants} variants`);
});
