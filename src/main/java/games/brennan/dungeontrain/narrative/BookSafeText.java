package games.brennan.dungeontrain.narrative;

/**
 * Sanitization and surrogate-safe truncation for untrusted book text.
 *
 * <p>Book text arrives from two places DT does not control: the relay's community pool, and the
 * sign-book packet a client sends when a player signs their own book. The relay sanitizes what it
 * serves, but this mod must not depend on that — a compromised relay, or the plain-HTTP override on
 * {@code DUNGEONTRAIN_RELAY_BASE_URL}, puts arbitrary text on the wire. This class is the mod's own
 * layer, applied at {@link BookFactory#buildPlainBook}, the single choke point both paths pass
 * through.</p>
 *
 * <p>Pure — no Minecraft types — so it stays in the "pure-logic only" unit-test band.</p>
 */
public final class BookSafeText {

    /**
     * Runs of more than this many consecutive combining marks are collapsed. Legitimate text never
     * stacks anywhere near this (Devanagari, Thai and Arabic peak around three); "Zalgo" abuse
     * stacks hundreds so a glyph overflows the page around it.
     */
    static final int MAX_COMBINING_RUN = 8;

    private BookSafeText() {}

    /**
     * Strip untrusted text down to something safe to render, removing by CODE POINT so valid astral
     * characters (emoji, rare CJK) survive intact.
     *
     * <p>Removed:</p>
     * <ul>
     *   <li>{@code §} (U+00A7), Minecraft's legacy formatting escape. {@link BookText} builds pages
     *       with {@code Component.literal}, which does not interpret it — but chat and several
     *       tooltip renderers downstream do, so a book could recolour or obfuscate text it does not
     *       own.</li>
     *   <li>C0 controls, DEL and C1. A newline in a title splits item lore onto extra lines; a NUL
     *       in an item name is a renderer hazard. Pages keep {@code \n} — their line structure is
     *       load-bearing, {@link BookFactory#paginate} splits on blank lines.</li>
     *   <li>Lone surrogates. {@link String#codePoints()} surfaces an unpaired surrogate as its own
     *       code point in D800–DFFF; left in, it reaches NBT's modified-UTF-8 encoder and throws.</li>
     *   <li>Bidi overrides and isolates, and zero-width/BOM formatting characters — invisible text
     *       that reverses or hides the characters around it.</li>
     * </ul>
     *
     * <p>Strictly reducing and idempotent: {@code sanitize(sanitize(x)) == sanitize(x)}.</p>
     */
    public static String sanitize(String raw, boolean allowNewlines) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        int combiningRun = 0;
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                if (allowNewlines) { out.append('\n'); combiningRun = 0; }
                continue;
            }
            if (!isAllowed(cp)) continue;
            // Collapse an over-long combining-mark run, but keep counting so the whole run is eaten
            // rather than only its first excess mark.
            if (isCombining(cp)) {
                combiningRun++;
                if (combiningRun > MAX_COMBINING_RUN) continue;
            } else {
                combiningRun = 0;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** {@link #sanitize(String, boolean)} then {@link #clampCp(String, int)}, trimmed at both ends. */
    public static String sanitizeAndClamp(String raw, int maxLen, boolean allowNewlines) {
        // Trim BEFORE clamping so leading whitespace can't eat the length budget, and again after in
        // case the clamp landed mid-space.
        return clampCp(sanitize(raw, allowNewlines).trim(), maxLen).trim();
    }

    /**
     * Truncate to at most {@code max} chars WITHOUT splitting a surrogate pair.
     *
     * <p>A plain {@code substring(0, max)} whose cut lands between the halves of an astral character
     * leaves a lone high surrogate — exactly what {@link #sanitize} exists to remove, manufactured
     * after the fact. Every length cap on the book path goes through here.</p>
     */
    public static String clampCp(String s, int max) {
        if (s == null || max <= 0) return "";
        if (s.length() <= max) return s;
        // A high surrogate at the final kept index means its low partner is on the far side of the
        // cut, so step back one and drop it rather than orphan it.
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    /** True when the code point is safe to keep (newlines are decided by the caller). */
    private static boolean isAllowed(int cp) {
        if (cp < 0x20 || cp == 0x7f) return false;              // C0 + DEL
        if (cp >= 0x80 && cp <= 0x9f) return false;             // C1
        if (cp == '§') return false;                            // legacy formatting escape
        if (cp >= 0xd800 && cp <= 0xdfff) return false;         // lone surrogate
        if (cp >= 0x200b && cp <= 0x200f) return false;         // zero-width + LRM/RLM
        if (cp >= 0x202a && cp <= 0x202e) return false;         // bidi embedding + override
        if (cp >= 0x2066 && cp <= 0x2069) return false;         // bidi isolates
        return cp != 0xfeff;                                    // BOM / ZWNBSP
    }

    private static boolean isCombining(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }
}
