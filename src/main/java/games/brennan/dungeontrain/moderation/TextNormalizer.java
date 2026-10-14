package games.brennan.dungeontrain.moderation;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Map;

/**
 * Folds player text into a shape a word list can be matched against, keeping track of where every
 * folded character came from so a hit can be masked in the ORIGINAL string.
 *
 * <p>Pure and allocation-light: one pass over a few hundred characters. The folding is deliberately
 * the same as the relay's {@code moderation-wordlist.js normalise()/collapse()} so the two ends agree
 * on what a term looks like:</p>
 * <ul>
 *   <li>lowercase, {@link Normalizer.Form#NFKD} per code point (fullwidth → ASCII, accents split off);</li>
 *   <li>combining marks, zero-width and other format characters become {@link #TRANSPARENT} — they
 *       neither split a word nor count as a letter, so "nïgger" and "nig​ger" still read
 *       as one word;</li>
 *   <li>a few Cyrillic / Greek look-alikes map to the Latin letter they imitate;</li>
 *   <li>leetspeak digits and symbols map to letters, but only when flanked by letters on both sides
 *       ("n1gg3r" → "nigger"; "hello!" keeps its "!" as a boundary; "2024" stays digits).</li>
 * </ul>
 */
public final class TextNormalizer {

    /** Placeholder for a character that was folded away: it joins the words around it without separating them. */
    public static final char TRANSPARENT = '\0';

    private static final Map<Character, Character> LEET = Map.ofEntries(
        Map.entry('@', 'a'), Map.entry('4', 'a'), Map.entry('3', 'e'), Map.entry('1', 'i'),
        Map.entry('!', 'i'), Map.entry('|', 'i'), Map.entry('0', 'o'), Map.entry('$', 's'),
        Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('+', 't'), Map.entry('9', 'g'),
        Map.entry('8', 'b'), Map.entry('2', 'z'));

    /** Look-alikes that survive NFKD unchanged: Cyrillic and Greek letters shaped like Latin ones. */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
        Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
        Map.entry('с', 'c'), Map.entry('х', 'x'), Map.entry('у', 'y'), Map.entry('к', 'k'),
        Map.entry('і', 'i'), Map.entry('ѕ', 's'), Map.entry('ј', 'j'), Map.entry('ԁ', 'd'),
        Map.entry('ɡ', 'g'), Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ν', 'v'),
        Map.entry('ι', 'i'), Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('κ', 'k'));

    private TextNormalizer() {}

    /**
     * One folded character per ORIGINAL char index ({@code result.length == raw.length()}). The low
     * half of a surrogate pair and every folded-away character read {@link #TRANSPARENT}.
     */
    public static char[] fold(String raw) {
        char[] out = new char[raw.length()];
        Arrays.fill(out, TRANSPARENT);
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int width = Character.charCount(cp);
            out[i] = foldCodePoint(cp);
            i += width;
        }
        applyLeet(out);
        return out;
    }

    /** The folded form of one code point: a lowercase letter, a digit, a boundary, or TRANSPARENT. */
    static char foldCodePoint(int cp) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK || type == Character.FORMAT) {
            return TRANSPARENT;
        }
        String s = new String(Character.toChars(cp));
        String nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD);
        if (nfkd.isEmpty()) return TRANSPARENT;
        int base = nfkd.codePointAt(0);
        if (Character.charCount(base) > 1) {
            // A supplementary-plane character with no single-char fold: keep it as a letter if it is one.
            return Character.isLetter(base) ? '�' : ' ';
        }
        char c = Character.toLowerCase((char) base);
        Character conf = CONFUSABLES.get(c);
        if (conf != null) return conf;
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return ' ';
        return c;
    }

    /** Leet digits / symbols become letters only when both neighbours (skipping TRANSPARENT) are letters. */
    private static void applyLeet(char[] out) {
        for (int i = 0; i < out.length; i++) {
            Character mapped = LEET.get(out[i]);
            if (mapped == null) continue;
            if (isLetterAt(out, i, -1) && isLetterAt(out, i, +1)) out[i] = mapped;
        }
    }

    private static boolean isLetterAt(char[] out, int from, int step) {
        for (int j = from + step; j >= 0 && j < out.length; j += step) {
            char c = out[j];
            if (c == TRANSPARENT) continue;
            return Character.isLetter(c);
        }
        return false;
    }

    /** True for a folded char that is part of a word (a letter; digits are not). */
    public static boolean isWordChar(char folded) {
        return folded != TRANSPARENT && Character.isLetter(folded);
    }

    /** Whether a folded char separates words: not a letter and not transparent. */
    public static boolean isBoundary(char folded) {
        return folded != TRANSPARENT && !Character.isLetter(folded);
    }

    /** A folded string with the original index of every kept character. */
    public record Mapped(String text, int[] origin) {}

    /** The folded text with TRANSPARENT dropped — what the phrase patterns run on. */
    public static Mapped flatten(char[] folded) {
        StringBuilder sb = new StringBuilder(folded.length);
        int[] origin = new int[folded.length];
        int n = 0;
        for (int i = 0; i < folded.length; i++) {
            if (folded[i] == TRANSPARENT) continue;
            sb.append(folded[i]);
            origin[n++] = i;
        }
        return new Mapped(sb.toString(), Arrays.copyOf(origin, n));
    }

    /**
     * Letters only, runs of the same letter squeezed to one ("n i g g e r" → "niger"), each kept
     * letter remembering where it came from. The evasion-resistant pass for the distinctive terms.
     */
    public static Mapped collapse(char[] folded) {
        StringBuilder sb = new StringBuilder(folded.length);
        int[] origin = new int[folded.length];
        int n = 0;
        char last = TRANSPARENT;
        for (int i = 0; i < folded.length; i++) {
            char c = folded[i];
            if (!isWordChar(c)) continue;
            if (c == last) continue;
            sb.append(c);
            origin[n++] = i;
            last = c;
        }
        return new Mapped(sb.toString(), Arrays.copyOf(origin, n));
    }

    /** Letters and digits (ideographs included), separators dropped — the CJK substring pass. */
    public static Mapped cjkCollapse(char[] folded) {
        StringBuilder sb = new StringBuilder(folded.length);
        int[] origin = new int[folded.length];
        int n = 0;
        for (int i = 0; i < folded.length; i++) {
            char c = folded[i];
            if (c == TRANSPARENT || !Character.isLetterOrDigit(c)) continue;
            sb.append(c);
            origin[n++] = i;
        }
        return new Mapped(sb.toString(), Arrays.copyOf(origin, n));
    }

    /** The folded form of a whole term, TRANSPARENT dropped — how list entries are stored. */
    public static String normalizeTerm(String term) {
        return flatten(fold(term == null ? "" : term.strip())).text();
    }

    /** The collapsed form of a term (letters only, repeats squeezed). */
    public static String collapseTerm(String term) {
        return collapse(fold(term == null ? "" : term)).text();
    }
}
