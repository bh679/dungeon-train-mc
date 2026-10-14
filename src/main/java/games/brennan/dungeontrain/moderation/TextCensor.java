package games.brennan.dungeontrain.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hot path: find the listed words in one line of player text and replace each with
 * {@value #MASK}. Pure, synchronous, a few passes over a short string — no allocation beyond the
 * folded copies, so it runs per keystroke without anyone noticing.
 *
 * <p>Four passes, each marking character spans of the ORIGINAL string to mask:</p>
 * <ol>
 *   <li><b>whole word</b> — every run of letters, folded, looked up in the list;</li>
 *   <li><b>phrase</b> — multi-word patterns on the folded text;</li>
 *   <li><b>collapse</b> — the distinctive terms as substrings of the letters-only, repeats-squeezed
 *       text, so "n i g g e r", "n.i.g.g.e.r", "niiigger" and "n1gg3r" all catch. A hit inside ONE
 *       word counts only when that whole word squeezes to the term (so "Nigeria" does not read as a
 *       slur); a hit across SEVERAL words counts only when each of them is three letters or shorter
 *       (so "in Niger" does not either, but "n i g g e r" does);</li>
 *   <li><b>CJK</b> — the Chinese terms as substrings of the separator-free text.</li>
 * </ol>
 *
 * <p>A masked span always grows to the enclosing word so no fragment of the term survives. The output
 * is idempotent: {@value #MASK} has no letters, so censoring it again changes nothing.</p>
 */
public final class TextCensor {

    public static final String MASK = "****";

    /** Longest line checked in full; anything past it is left alone (chat caps at 256, a page at ~1k). */
    static final int MAX_LEN = 4096;

    /** Longest word that may take part in a multi-word collapse hit ("n i g g e r", "nig ger"). */
    static final int MAX_SPLIT_PIECE = 3;

    /** What one check found: the text to use, which terms matched, and whether anything changed. */
    public record Result(String text, List<String> hits, boolean changed) {
        public static Result unchanged(String text) {
            return new Result(text, List.of(), false);
        }
    }

    private TextCensor() {}

    /** Censor a finished line (a sent chat message, a signed page). */
    public static Result censor(String raw, CompiledWords words) {
        return run(raw, words, false);
    }

    /**
     * Censor text still being typed: identical, except a whole-word hit needs a boundary after it
     * (so "paki" is not masked while "pakistan" is on its way). The collapse, phrase and CJK passes
     * run in full — their terms are distinctive enough to act on at once.
     */
    public static Result censorTail(String raw, CompiledWords words) {
        return run(raw, words, true);
    }

    private static Result run(String raw, CompiledWords words, boolean tail) {
        if (raw == null || raw.isEmpty() || words == null || words.isEmpty() || raw.length() > MAX_LEN) {
            return Result.unchanged(raw == null ? "" : raw);
        }
        char[] folded = TextNormalizer.fold(raw);
        boolean[] mask = new boolean[raw.length()];
        List<String> hits = new ArrayList<>(2);

        wordPass(folded, words, tail, mask, hits);
        phrasePass(folded, words, mask, hits);
        collapsePass(folded, words, mask, hits);
        cjkPass(folded, words, mask, hits);

        if (hits.isEmpty()) return Result.unchanged(raw);
        return new Result(apply(raw, mask), List.copyOf(hits), true);
    }

    // --- pass 1: whole words ----------------------------------------------------------------------

    private static void wordPass(char[] folded, CompiledWords words, boolean tail, boolean[] mask, List<String> hits) {
        int i = 0;
        int n = folded.length;
        while (i < n) {
            if (!TextNormalizer.isWordChar(folded[i])) { i++; continue; }
            int start = i;
            StringBuilder sb = new StringBuilder();
            while (i < n && !TextNormalizer.isBoundary(folded[i])) {
                if (folded[i] != TextNormalizer.TRANSPARENT) sb.append(folded[i]);
                i++;
            }
            int end = trimTransparent(folded, start, i);
            boolean completed = i < n; // a boundary follows (not the end of the text)
            if (tail && !completed) continue;
            String token = sb.toString();
            if (words.isAllowed(token)) continue;
            if (words.isWord(token)) {
                fill(mask, start, end);
                hits.add(token);
            }
        }
    }

    // --- pass 2: phrases --------------------------------------------------------------------------

    private static void phrasePass(char[] folded, CompiledWords words, boolean[] mask, List<String> hits) {
        if (words.phrasePatterns().isEmpty()) return;
        TextNormalizer.Mapped flat = TextNormalizer.flatten(folded);
        List<Pattern> patterns = words.phrasePatterns();
        for (int p = 0; p < patterns.size(); p++) {
            Matcher m = patterns.get(p).matcher(flat.text());
            while (m.find()) {
                int from = flat.origin()[m.start()];
                int to = flat.origin()[m.end() - 1] + 1;
                fill(mask, from, to);
                hits.add(words.phraseTerms().get(p));
            }
        }
    }

    // --- pass 3: collapsed substrings -------------------------------------------------------------

    private static void collapsePass(char[] folded, CompiledWords words, boolean[] mask, List<String> hits) {
        if (words.collapseTerms().isEmpty()) return;
        TextNormalizer.Mapped col = TextNormalizer.collapse(folded);
        String text = col.text();
        if (text.isEmpty()) return;
        for (String term : words.collapseTerms()) {
            int at = text.indexOf(term);
            while (at >= 0) {
                int from = col.origin()[at];
                int to = col.origin()[at + term.length() - 1] + 1;
                if (acceptCollapseHit(folded, from, to, term, words)) {
                    int[] span = wordSpan(folded, from, to);
                    fill(mask, span[0], span[1]);
                    hits.add(term);
                }
                at = text.indexOf(term, at + 1);
            }
        }
    }

    /**
     * Whether a collapse hit on original chars {@code [from, to)} is a real evasion rather than an
     * innocent word that happens to contain the squeezed letters. See the class comment.
     */
    static boolean acceptCollapseHit(char[] folded, int from, int to, String term, CompiledWords words) {
        int[] span = wordSpan(folded, from, to);
        List<String> pieces = piecesIn(folded, span[0], span[1]);
        if (pieces.size() == 1) {
            String piece = pieces.get(0);
            if (words.isAllowed(piece)) return false;
            return squeeze(piece).equals(term);
        }
        for (String piece : pieces) {
            if (piece.length() > MAX_SPLIT_PIECE) return false;
        }
        return true;
    }

    /** The folded words (letter runs) that overlap {@code [from, to)}. */
    private static List<String> piecesIn(char[] folded, int from, int to) {
        List<String> out = new ArrayList<>(4);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            char c = folded[i];
            if (TextNormalizer.isBoundary(c)) {
                if (sb.length() > 0) { out.add(sb.toString()); sb.setLength(0); }
            } else if (c != TextNormalizer.TRANSPARENT) {
                sb.append(c);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    /** Repeated letters squeezed ("niiigger" → "niger"). */
    static String squeeze(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        char last = TextNormalizer.TRANSPARENT;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == last) continue;
            sb.append(c);
            last = c;
        }
        return sb.toString();
    }

    // --- pass 4: CJK ------------------------------------------------------------------------------

    private static void cjkPass(char[] folded, CompiledWords words, boolean[] mask, List<String> hits) {
        if (words.cjkTerms().isEmpty()) return;
        TextNormalizer.Mapped col = TextNormalizer.cjkCollapse(folded);
        String text = col.text();
        if (text.isEmpty()) return;
        for (String term : words.cjkTerms()) {
            int at = text.indexOf(term);
            while (at >= 0) {
                int from = col.origin()[at];
                int to = col.origin()[at + term.length() - 1] + 1;
                fill(mask, from, to);
                hits.add(term);
                at = text.indexOf(term, at + 1);
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Grow {@code [from, to)} outwards to the nearest word boundaries. */
    static int[] wordSpan(char[] folded, int from, int to) {
        while (from > 0 && !TextNormalizer.isBoundary(folded[from - 1])) from--;
        while (to < folded.length && !TextNormalizer.isBoundary(folded[to])) to++;
        return new int[] { from, trimTransparent(folded, from, to) };
    }

    /** Drop trailing TRANSPARENT chars from a span so a following zero-width char isn't swallowed. */
    private static int trimTransparent(char[] folded, int from, int to) {
        while (to > from && folded[to - 1] == TextNormalizer.TRANSPARENT) to--;
        return to;
    }

    private static void fill(boolean[] mask, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(mask.length, to); i++) mask[i] = true;
    }

    /** Each maximal masked run becomes one {@value #MASK}. */
    static String apply(String raw, boolean[] mask) {
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            if (!mask[i]) { sb.append(raw.charAt(i)); i++; continue; }
            while (i < raw.length() && mask[i]) i++;
            sb.append(MASK);
        }
        return sb.toString();
    }
}
