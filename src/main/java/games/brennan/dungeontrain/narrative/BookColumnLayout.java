package games.brennan.dungeontrain.narrative;

/**
 * Two-column layout for a written book page — a name on the left, a right-justified number on the
 * right — for the leaderboard books.
 *
 * <h2>Why this exists at all</h2>
 * <p>A book page is {@value #PAGE_WIDTH_PX} pixels wide and Minecraft's default font is
 * variable-width, so "pad with spaces until it looks right" does not survive contact with real
 * player names: {@code IIIIIIIIIIIIIIII} and {@code MMMMMMMMMMMMMMMM} are the same sixteen
 * characters and nowhere near the same width. Right-justifying a column therefore needs actual
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
 * within 4px. This pads with the largest number of spaces that still fits, which leaves the number's
 * right edge 0–3px short of the margin — never over it, because overflowing wraps the line and
 * breaks the whole page. In practice that is under half a character of ragged edge and reads as a
 * straight column; it is not, and cannot be, pixel-exact.</p>
 *
 * <p>Names too long for the space left over are truncated with an ellipsis. At {@value #PAGE_WIDTH_PX}px
 * a rank, a gap and a five-figure score leave roughly eleven characters of name, so truncation is
 * normal rather than exceptional — a sixteen-character name does not fit on a book page next to a
 * number and no amount of layout will make it.</p>
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
     * One leaderboard line: {@code left}, then as much space as fits, then {@code right} ending flush
     * with the {@code widthPx} margin. {@code left} is truncated when it would not otherwise leave a
     * gap of at least one space.
     *
     * <p>When {@code right} alone is wider than the line there is nothing sensible to lay out, so the
     * number wins and the name is dropped — a rank with no score is useless, a score with no name is
     * merely disappointing.</p>
     */
    public static String row(String left, String right, int widthPx) {
        String value = right == null ? "" : right;
        int valuePx = width(value);
        if (valuePx >= widthPx) return value;

        int room = widthPx - valuePx - SPACE_PX; // always leave at least one space between the two
        String name = truncate(left == null ? "" : left, room);
        int gapPx = widthPx - valuePx - width(name);
        int spaces = gapPx / SPACE_PX; // floor: 0-3px short of the margin, never past it
        return name + " ".repeat(Math.max(1, spaces)) + value;
    }

    /** {@link #row(String, String, int)} at the full width of a book page. */
    public static String row(String left, String right) {
        return row(left, right, PAGE_WIDTH_PX);
    }
}
