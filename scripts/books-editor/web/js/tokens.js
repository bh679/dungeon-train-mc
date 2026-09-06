/**
 * tokens.js — the `%TOKEN%` substitution note-story starting books get at build time.
 *
 * Books in the cursed and loved context folders are TEMPLATES: CursedBookFactory substitutes the tokens below
 * (in the body AND the title) from the landed note before the book is built, so the raw JSON never
 * shows what a player actually reads — and the 32-char title clamp applies AFTER substitution. The
 * preview can therefore stand in for `/dungeontrain narrative startingbook fire cursed`, using the
 * same shape of stub that command does (your own name, your carriage, 3 days).
 *
 * Substitution is display-only. It never touches what gets written to disk.
 */

/** Contexts whose books are note-story templates (see starting_books/CLAUDE.md §1b). */
export const TEMPLATE_CONTEXTS = new Set([
  'cursed', 'cursed_fulfilled', 'cursed_defied',
  'loved', 'loved_betrayed', 'loved_turned',
]);

/** Stub values, matching the spirit of the in-game preview command's stub story. */
export const STUB = {
  '%TARGET%': 'Steve',
  '%CARRIAGE%': '42',
  '%DAYS%': '3',
  '%STORY%': 'It found you on the tracks with a netherite axe in its hands. '
    + 'You traded blows across two carriages before the lanterns went out. It did not walk away.',
  '%GEAR%': 'It still carried a netherite axe.',
  '%ENDING%': 'It did not walk away.',
};

/** Tokens present in a body, in the order they first appear — used to badge the editor. */
export function tokensIn(text) {
  if (typeof text !== 'string') return [];
  const found = [];
  for (const token of Object.keys(STUB)) {
    if (text.includes(token) && !found.includes(token)) found.push(token);
  }
  return found;
}

/**
 * Substitute every stub token. Literal and case-sensitive, exactly like the mod — `%target%` does
 * NOT substitute, which is a real authoring trap worth reproducing rather than smoothing over.
 */
export function substitute(text, values = STUB) {
  if (typeof text !== 'string' || !text) return text || '';
  let out = text;
  for (const [token, value] of Object.entries(values)) out = out.split(token).join(value);
  return out;
}
