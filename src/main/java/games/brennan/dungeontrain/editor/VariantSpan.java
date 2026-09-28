package games.brennan.dungeontrain.editor;

import java.util.Locale;

/**
 * Per-<b>cell</b> footprint setting for a variant cell that holds a multi-space
 * block (door, bed, tall plant — see {@link MultiBlockFootprint}). A multi-space
 * pick fills two spaces; this one setting decides how whichever single-space
 * block the cell rolls fills those same two spaces. Stored beside the cell's
 * lock-id in every variant sidecar ({@code "span"}), set from the Z menu's
 * Span popup, which shows it as three sections:
 *
 * <ul>
 *   <li>{@link Count} — <b>How many</b>: the block fills 1 space, both, or a
 *       seeded coin picks per spawn.</li>
 *   <li>{@link Position} — which space a lone block takes (the cell, the
 *       partner space, or random). Used when How many is 1 or R.</li>
 *   <li>{@link Fill} — <b>Repeat</b>: with both spaces filled, the second holds
 *       the same block or re-rolls among the cell's single blocks. Used when
 *       How many is 2 or R.</li>
 * </ul>
 *
 * <p>{@link #NONE} ({@code auto}) is the unset default, omitted from JSON:
 * {@link #resolve} makes it 2 / Same when the cell's first entry is multi-space
 * (the author started from a door and added fillers) and 1 / Pos 1 otherwise.
 * Ignored entirely on cells with no multi-space entry.</p>
 */
public record VariantSpan(boolean auto, Count count, Position position, Fill fill) {

    public enum Count { ONE, TWO, RANDOM }
    public enum Position { FIRST, SECOND, RANDOM }
    public enum Fill { SAME, RANDOM }

    /** Default: follow the cell's first entry. */
    public static final VariantSpan NONE = new VariantSpan(true, Count.ONE, Position.FIRST, Fill.SAME);

    private static final int AUTO_BIT = 0x80;

    public VariantSpan {
        if (count == null) count = Count.ONE;
        if (position == null) position = Position.FIRST;
        if (fill == null) fill = Fill.SAME;
    }

    /** An explicit (author-chosen) span. */
    public static VariantSpan of(Count count, Position position, Fill fill) {
        return new VariantSpan(false, count, position, fill);
    }

    /** True when this is the unset default — used to skip JSON emission. */
    public boolean isDefault() {
        return auto;
    }

    /** The explicit span in effect, resolving {@link #NONE} against the cell's first entry. */
    public VariantSpan resolve(boolean firstEntryIsMulti) {
        if (!auto) return this;
        return firstEntryIsMulti
            ? of(Count.TWO, Position.FIRST, Fill.SAME)
            : of(Count.ONE, Position.FIRST, Fill.SAME);
    }

    public VariantSpan withCount(Count c) { return of(c, position, fill); }
    public VariantSpan withPosition(Position p) { return of(count, p, fill); }
    public VariantSpan withFill(Fill f) { return of(count, position, f); }

    /** True when the Position section applies (How many is 1 or R). */
    public boolean usesPosition() { return count != Count.TWO; }

    /** True when the Repeat section applies (How many is 2 or R). */
    public boolean usesFill() { return count != Count.ONE; }

    // ─── JSON / clipboard token ─────────────────────────────────────────

    /** Compact token {@code "<count>/<pos>/<fill>"}, e.g. {@code "r/2/same"}; {@code null} when auto. */
    public String toToken() {
        if (auto) return null;
        return countToken(count) + "/" + positionToken(position) + "/" + (fill == Fill.SAME ? "same" : "r");
    }

    /**
     * Inverse of {@link #toToken}. Also reads the two-part tokens this setting first used on the
     * branch ({@code 1/1}, {@code 1/2}, {@code 1/r}, {@code 2/same}, {@code 2/r}). Unknown / null
     * tokens read as {@link #NONE}.
     */
    public static VariantSpan fromToken(String token) {
        if (token == null) return NONE;
        String[] parts = token.trim().toLowerCase(Locale.ROOT).split("/");
        if (parts.length == 3) {
            Count c = parseCount(parts[0]);
            Position p = parsePosition(parts[1]);
            Fill f = parseFill(parts[2]);
            return c == null || p == null || f == null ? NONE : of(c, p, f);
        }
        if (parts.length == 2) {
            if (parts[0].equals("1")) {
                Position p = parsePosition(parts[1]);
                return p == null ? NONE : of(Count.ONE, p, Fill.SAME);
            }
            if (parts[0].equals("2")) {
                Fill f = parseFill(parts[1]);
                return f == null ? NONE : of(Count.TWO, Position.FIRST, f);
            }
        }
        return NONE;
    }

    private static String countToken(Count c) {
        return switch (c) { case ONE -> "1"; case TWO -> "2"; case RANDOM -> "r"; };
    }

    private static String positionToken(Position p) {
        return switch (p) { case FIRST -> "1"; case SECOND -> "2"; case RANDOM -> "r"; };
    }

    private static Count parseCount(String s) {
        return switch (s) { case "1" -> Count.ONE; case "2" -> Count.TWO; case "r" -> Count.RANDOM; default -> null; };
    }

    private static Position parsePosition(String s) {
        return switch (s) {
            case "1" -> Position.FIRST; case "2" -> Position.SECOND; case "r" -> Position.RANDOM; default -> null;
        };
    }

    private static Fill parseFill(String s) {
        return switch (s) { case "same" -> Fill.SAME; case "r" -> Fill.RANDOM; default -> null; };
    }

    // ─── Wire byte ──────────────────────────────────────────────────────

    /** One packed byte for the sync / edit packets: bit 7 auto, bits 3–4 count, 1–2 position, 0 fill. */
    public int toByte() {
        if (auto) return AUTO_BIT;
        return (count.ordinal() << 3) | (position.ordinal() << 1) | fill.ordinal();
    }

    /** Inverse of {@link #toByte}; anything malformed reads as {@link #NONE}. */
    public static VariantSpan fromByte(int b) {
        b &= 0xFF;
        if ((b & AUTO_BIT) != 0) return NONE;
        int c = (b >> 3) & 0x3;
        int p = (b >> 1) & 0x3;
        int f = b & 0x1;
        if (c >= Count.values().length || p >= Position.values().length || (b & ~0x1F) != 0) return NONE;
        return of(Count.values()[c], Position.values()[p], Fill.values()[f]);
    }
}
