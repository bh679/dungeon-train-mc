package games.brennan.dungeontrain.narrative;

/**
 * Text measurement for a written book page — how wide a string renders, how to cut one to the
 * margin, and how to push one flush against the right edge — for the leaderboard books.
 *
 * <h2>Why this exists at all</h2>
 * <p>A book page is {@value #PAGE_WIDTH_PX} pixels wide and Minecraft's default font is
 * variable-width, so "pad with spaces until it looks right" does not survive contact with real
 * player names: {@code IIIIIIIIIIIIIIII} and {@code MMMMMMMMMMMMMMMM} are the same sixteen
 * characters and nowhere near the same width. Right-justifying anything therefore needs actual
 * glyph advances.</p>
 *
 * <p>The obvious source for those is {@code net.minecraft.client.gui.Font#width}, and it is not
 * available here: book pages are built SERVER-side (see {@link BookFactory}), and a dedicated server
 * has no font. So this class carries its own table of the vanilla default font's advances. It is
 * small, it is fixed data that has not changed across Minecraft versions, and it is the only way to
 * lay out a column in a book at all.</p>
 *
 * <h2>What "right-justified" means here, exactly</h2>
 * <p>Padding is made of spaces, and a space advances 4px, so a column edge can only ever be hit to
 * within 4px. {@link #rightAlign} pads with the largest number of spaces that still fits, which
 * leaves the value's right edge 0–3px short of the margin — never over it, because overflowing wraps
 * the line and breaks the whole page. In practice that is under half a character of ragged edge and
 * reads as a straight column; it is not, and cannot be, pixel-exact.</p>
 *
 * <p>A leaderboard entry spends a whole line on the name and the next on the score, so a name has
 * the full {@value #PAGE_WIDTH_PX}px to itself and only the longest are cut. That is the difference
 * between showing {@code ThePenultimateCarriage} and showing {@code ThePenultim…}: sharing a line
 * with a five-figure score left about eleven characters for a name, which most real names do not
 * fit in.</p>
 */
public final class BookColumnLayout {

    /** Usable text width of one written-book page, in pixels — vanilla's {@code BookViewScreen}. */
    public static final int PAGE_WIDTH_PX = 114;

    /** Advance of a space in the default font. The padding granularity, and the reason for the 0–3px slack. */
    private static final int SPACE_PX = 4;

    /** Default advance for anything not in {@link #NARROW} and not wide-script. */
    private static final int DEFAULT_PX = 6;

    /** Advance for CJK / fullwidth glyphs, which the default font renders from the unifont sheet. */
    private static final int WIDE_PX = 9;

    /** Shown when a name is cut. U+2026, one glyph — cheaper than "..." at 3x the width. */
    private static final char ELLIPSIS = '…';

    /**
     * Every printable ASCII glyph whose advance is not {@value #DEFAULT_PX}, as pairs of
     * (character, advance). Indexed by {@code c - ' '} in {@link #NARROW_BY_CODE} below.
     */
    private static final int[] NARROW_BY_CODE = new int[95]; // ' ' (32) .. '~' (126)

    static {
        for (int i = 0; i < NARROW_BY_CODE.length; i++) NARROW_BY_CODE[i] = DEFAULT_PX;
        put(' ', 4);  put('!', 2);  put('"', 5);  put('\'', 3);
        put('(', 5);  put(')', 5);  put('*', 5);  put(',', 2);
        put('.', 2);  put(':', 2);  put(';', 2);  put('<', 5);
        put('>', 5);  put('@', 7);  put('I', 4);  put('[', 4);
        put(']', 4);  put('`', 3);  put('f', 5);  put('i', 2);
        put('k', 5);  put('l', 3);  put('t', 4);  put('{', 5);
        put('|', 2);  put('}', 5);  put('~', 7);
    }

    private static void put(char c, int px) { NARROW_BY_CODE[c - ' '] = px; }

    private BookColumnLayout() {}

    /** Rendered advance of one character in the default font, including its 1px spacing. */
    public static int charWidth(char c) {
        if (c >= ' ' && c <= '~') return NARROW_BY_CODE[c - ' '];
        if (c == ELLIPSIS) return DEFAULT_PX;
        // CJK, Hangul, kana, fullwidth forms — the unifont sheet's wide cell.
        if ((c >= 0x1100 && c <= 0x11FF) || (c >= 0x2E80 && c <= 0xA4CF)
            || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
            || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
            || (c >= 0xFFE0 && c <= 0xFFE6)) return WIDE_PX;
        return DEFAULT_PX;
    }

    /** Rendered advance of a string. Null or empty is 0. */
    public static int width(String s) {
        if (s == null || s.isEmpty()) return 0;
        int px = 0;
        for (int i = 0; i < s.length(); i++) px += charWidth(s.charAt(i));
        return px;
    }

    /**
     * {@code s} shortened until it fits {@code maxPx}, with an ellipsis marking the cut. Returns
     * {@code ""} when not even the ellipsis fits, so a caller never lands a glyph past the margin.
     */
    public static String truncate(String s, int maxPx) {
        if (s == null || s.isEmpty()) return "";
        if (width(s) <= maxPx) return s;
        int ell = charWidth(ELLIPSIS);
        if (maxPx < ell) return "";
        int px = ell;
        int end = 0;
        while (end < s.length()) {
            int next = px + charWidth(s.charAt(end));
            if (next > maxPx) break;
            px = next;
            end++;
        }
        return s.substring(0, end) + ELLIPSIS;
    }

    /**
     * {@code value} pushed against the right-hand margin of a {@code widthPx} line, padded with
     * leading spaces. A value already at or over the width is returned as-is rather than padded
     * onto a second line — overflowing wraps, and a wrapped score would break the page's rhythm.
     */
    public static String rightAlign(String value, int widthPx) {
        if (value == null || value.isEmpty()) return "";
        int px = width(value);
        if (px >= widthPx) return value;
        int spaces = (widthPx - px) / SPACE_PX; // floor: 0-3px short of the margin, never past it
        return " ".repeat(spaces) + value;
    }

    /** {@link #rightAlign(String, int)} at the full width of a book page. */
    public static String rightAlign(String value) {
        return rightAlign(value, PAGE_WIDTH_PX);
    }
}
