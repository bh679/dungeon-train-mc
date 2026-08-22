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
 * <h2>What this deliberately does NOT remove</h2>
 * <p>A book is a creative medium, and most "suspicious" characters are load-bearing for somebody:</p>
 * <ul>
 *   <li><b>{@code §} formatting codes in PAGE BODIES.</b> Colouring and styling your own book is a
 *       feature players use on purpose. The worst a page can do with it is make its own text hard to
 *       read, which is contained to that book. It is still stripped from titles and author names,
 *       because those reach chat lines, item names and tooltips — renderers that belong to the rest
 *       of the UI rather than to the book.</li>
 *   <li><b>Bidi overrides and isolates</b> (U+202A–202E, U+2066–2069) and the bidi marks LRM/RLM.
 *       Right-to-left text needs these; DT ships 19 locales, and stripping them mangles legitimate
 *       Arabic and Hebrew writing. A book that reverses its own display is cosmetic and can't escape
 *       the book.</li>
 *   <li><b>ZWNJ and ZWJ</b> (U+200C, U+200D). Required by Persian, Arabic and Indic scripts, and by
 *       emoji ZWJ sequences — stripping U+200D alone would break every family and profession emoji.</li>
 * </ul>
 *
 * <p>Pure — no Minecraft types — so it stays in the "pure-logic only" unit-test band.</p>
 */
public final class BookSafeText {

    /**
     * Runs of more than this many consecutive combining marks are collapsed. Legitimate text never
     * stacks anywhere near this — even Arabic with full vowel pointing and Indic conjuncts stay well
     * under — while "Zalgo" abuse stacks hundreds so a glyph overflows the page around it.
     */
    static final int MAX_COMBINING_RUN = 8;

    private BookSafeText() {}

    /**
     * Sanitize a PAGE BODY: newlines and {@code §} formatting codes are kept, since page line
     * structure is load-bearing ({@link BookFactory#paginate} splits on blank lines) and styling
     * your own page is a feature.
     */
    public static String sanitizePage(String raw) {
        return sanitize(raw, true, true);
    }

    /**
     * Sanitize a NAME — a book title, an author, a backer name. Neither newlines nor {@code §} are
     * kept: these strings reach item names, chat lines and tooltips, where a newline splits lore
     * onto extra lines and {@code §} restyles UI that does not belong to the book.
     */
    public static String sanitizeName(String raw) {
        return sanitize(raw, false, false);
    }

    /** {@link #sanitizeName} then {@link #clampCp}, trimmed at both ends. */
    public static String sanitizeAndClampName(String raw, int maxLen) {
        // Trim BEFORE clamping so leading whitespace can't eat the length budget, and again after in
        // case the clamp landed mid-space.
        return clampCp(sanitizeName(raw).trim(), maxLen).trim();
    }

    /**
     * Truncate to at most {@code max} chars WITHOUT splitting a surrogate pair.
     *
     * <p>A plain {@code substring(0, max)} whose cut lands between the halves of an astral character
     * leaves a lone high surrogate, which throws when it reaches NBT's modified-UTF-8 encoder on the
     * client. Every length cap on the book path goes through here.</p>
     */
    public static String clampCp(String s, int max) {
        if (s == null || max <= 0) return "";
        if (s.length() <= max) return s;
        // A high surrogate at the final kept index means its low partner is on the far side of the
        // cut, so step back one and drop it rather than orphan it.
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    private static String sanitize(String raw, boolean allowNewlines, boolean allowFormatting) {
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
            if (cp == '§' && !allowFormatting) continue;
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

    /** True when the code point is safe to keep regardless of field. */
    private static boolean isAllowed(int cp) {
        if (cp < 0x20 || cp == 0x7f) return false;      // C0 + DEL
        if (cp >= 0x80 && cp <= 0x9f) return false;     // C1
        if (cp >= 0xd800 && cp <= 0xdfff) return false; // lone surrogate — the actual crash vector
        if (cp == 0x200b) return false;                 // ZWSP: invisible padding, no script needs it
        return cp != 0xfeff;                            // BOM / ZWNBSP
    }

    private static boolean isCombining(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }
}
