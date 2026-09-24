package games.brennan.dungeontrain.editor;

/**
 * Per-{@link VariantState} footprint config for a <b>single-space</b> block that
 * shares a variant cell with a multi-space block (door, bed, tall plant — see
 * {@link MultiBlockFootprint}). A multi-space pick fills two spaces; this
 * setting decides how a single-space pick fills those same two spaces.
 *
 * <ul>
 *   <li>{@link Mode#ONE_FIRST} — block in the cell, the partner space is air.</li>
 *   <li>{@link Mode#ONE_SECOND} — cell is air, block in the partner space.</li>
 *   <li>{@link Mode#ONE_RANDOM} — seeded 50/50 between the two above.</li>
 *   <li>{@link Mode#TWO_SAME} — the same block in both spaces.</li>
 *   <li>{@link Mode#TWO_RANDOM} — the partner space re-rolls among the cell's
 *       single-space entries (may land on the same block).</li>
 *   <li>{@link Mode#AUTO} — the default, omitted from JSON: {@link #resolve}
 *       picks {@code TWO_SAME} when the cell's first entry is multi-space
 *       (the author started from a door and added fillers) and
 *       {@code ONE_FIRST} otherwise (a multi-space block was added to an
 *       existing single-block cell).</li>
 * </ul>
 *
 * <p>Ignored entirely on cells with no multi-space entry, and on multi-space
 * entries themselves.</p>
 */
public record VariantSpan(Mode mode) {

    public enum Mode {
        AUTO, ONE_FIRST, ONE_SECOND, ONE_RANDOM, TWO_SAME, TWO_RANDOM;

        /** True for the "How many = 1" modes. */
        public boolean isOne() {
            return this == ONE_FIRST || this == ONE_SECOND || this == ONE_RANDOM;
        }
    }

    /** Default: follow the cell's first entry — see {@link Mode#AUTO}. */
    public static final VariantSpan NONE = new VariantSpan(Mode.AUTO);

    public VariantSpan {
        if (mode == null) mode = Mode.AUTO;
    }

    /** True when this is the no-op default — used to skip JSON emission. */
    public boolean isDefault() {
        return mode == Mode.AUTO;
    }

    /** The concrete mode, resolving {@link Mode#AUTO} against the cell's first entry. */
    public Mode resolve(boolean firstEntryIsMulti) {
        if (mode != Mode.AUTO) return mode;
        return firstEntryIsMulti ? Mode.TWO_SAME : Mode.ONE_FIRST;
    }

    /** Menu "How many" click: 1 ↔ 2, landing on Position 1 / Same. {@code resolved} must not be AUTO. */
    public static Mode nextCount(Mode resolved) {
        return resolved.isOne() ? Mode.TWO_SAME : Mode.ONE_FIRST;
    }

    /** Menu sub-option click: cycles 1 → 2 → R under "1", Same ↔ Random under "2". */
    public static Mode nextSub(Mode resolved) {
        return switch (resolved) {
            case ONE_FIRST -> Mode.ONE_SECOND;
            case ONE_SECOND -> Mode.ONE_RANDOM;
            case ONE_RANDOM -> Mode.ONE_FIRST;
            case TWO_SAME -> Mode.TWO_RANDOM;
            case TWO_RANDOM, AUTO -> Mode.TWO_SAME;
        };
    }

    /** Compact JSON token; {@code null} for {@link Mode#AUTO} (field omitted). */
    public static String toToken(Mode mode) {
        return switch (mode) {
            case AUTO -> null;
            case ONE_FIRST -> "1/1";
            case ONE_SECOND -> "1/2";
            case ONE_RANDOM -> "1/r";
            case TWO_SAME -> "2/same";
            case TWO_RANDOM -> "2/r";
        };
    }

    /** Inverse of {@link #toToken}; unknown / null tokens read as {@link #NONE}. */
    public static VariantSpan fromToken(String token) {
        if (token == null) return NONE;
        return switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1/1" -> new VariantSpan(Mode.ONE_FIRST);
            case "1/2" -> new VariantSpan(Mode.ONE_SECOND);
            case "1/r" -> new VariantSpan(Mode.ONE_RANDOM);
            case "2/same" -> new VariantSpan(Mode.TWO_SAME);
            case "2/r" -> new VariantSpan(Mode.TWO_RANDOM);
            default -> NONE;
        };
    }

    /** Wire / NBT decode of an ordinal byte; out-of-range reads as {@link #NONE}. */
    public static VariantSpan fromOrdinal(int ordinal) {
        Mode[] all = Mode.values();
        return ordinal >= 0 && ordinal < all.length ? new VariantSpan(all[ordinal]) : NONE;
    }
}
