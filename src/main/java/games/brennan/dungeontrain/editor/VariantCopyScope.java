package games.brennan.dungeontrain.editor;

import java.util.Locale;

/**
 * Which tiles of a repeating dimensional carriage room a variant cell applies in.
 *
 * <p>A room under one of the endless modes is stamped many times: the arrival room the player walks
 * into ({@code PortalRoomTiling.Tile#BASE}) and a sliding window of copies around it. Every
 * authored cell used to be rolled into all of them, which is the right default and is what
 * {@link #BOTH} still means. This is the author's way of saying otherwise — a torch that should
 * only light the hall, a doorway that belongs to the arrival room and would be a wall of doors
 * repeated down it.</p>
 *
 * <h2>Excluded means empty</h2>
 * <p>Where a cell does not apply, the variant pass writes it as an empty pick: air, with a
 * two-space cell's partner space cleared too. It used to skip the cell and leave whatever the
 * room's template stamped there, on the theory that this was a block the author had built. It
 * never was: {@code VariantEditorPreviewTicker} keeps every variant cell cycling through its
 * entries, and the room save captures the plot raw, so the template held whichever entry the
 * preview happened to show at the moment of saving. A "Not copies" cell saved while its stage
 * block was showing put that block in every copy. Empty is the one answer that does not depend
 * on when the author pressed save.</p>
 *
 * <h2>Independent of the reroll flag</h2>
 * <p>This answers <i>whether the cell is there</i>; {@link VariantCopyRoll} answers <i>how it
 * rolls</i> once it is. The two compose freely — a cell can apply only in the copies and reroll in
 * each of them — which is why they are two settings and two buttons rather than one cycle through
 * their product.</p>
 *
 * <p>Stored per cell as {@code "scope"} inside the sidecar's cell object, and omitted entirely for
 * {@link #BOTH} so a cell that never touched the setting round-trips byte-identical.</p>
 */
public enum VariantCopyScope {

    /** Every tile — the arrival room and all its copies. What every cell authored before this did. */
    BOTH("both", "Both"),

    /** The appended copies only. The arrival room leaves this cell empty. */
    COPIES("copies", "Copies"),

    /** The arrival room only. Every copy leaves this cell empty. */
    NOT_COPIES("not_copies", "Not copies");

    private final String id;
    private final String displayName;

    VariantCopyScope(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk key. */
    public String id() {
        return id;
    }

    /** The word the menu's toolbar cell shows. */
    public String displayName() {
        return displayName;
    }

    /** True for the default — the one value that is never written to disk. */
    public boolean isDefault() {
        return this == BOTH;
    }

    /**
     * True when a cell with this scope should be applied to {@code baseTile}'s stamp.
     *
     * <p>Takes the boolean rather than the tile so the pure decision stays testable without a
     * portal package import, and so the one place that knows which tile is the arrival room stays
     * {@code PortalCarriageBuilder}.</p>
     */
    public boolean appliesTo(boolean baseTile) {
        return switch (this) {
            case BOTH -> true;
            case COPIES -> !baseTile;
            case NOT_COPIES -> baseTile;
        };
    }

    /**
     * What a cell with this scope places in {@code baseTile}'s stamp: {@code roll}'s pick where the
     * cell applies, {@link VariantConditions#EMPTY_PICK} where it does not.
     *
     * <p>{@code roll} is only called where the cell applies, so an excluded cell draws nothing
     * from the seed. May return null when {@code roll} does (nothing to place).</p>
     */
    public VariantState pickFor(boolean baseTile, java.util.function.Supplier<VariantState> roll) {
        return appliesTo(baseTile) ? roll.get() : VariantConditions.EMPTY_PICK;
    }

    /**
     * The scope named by {@code id}, or {@link #BOTH} when it is null, blank or unrecognised.
     *
     * <p>Total, like every other sidecar value parser here: a hand-edited typo should stamp the
     * room the way it always did rather than fail the file.</p>
     */
    public static VariantCopyScope parse(String id) {
        if (id == null) return BOTH;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (VariantCopyScope s : values()) {
            if (s.id.equals(key)) return s;
        }
        return BOTH;
    }

    /** The scope at {@code ordinal}, or {@link #BOTH} when it is out of range — the wire's read side. */
    public static VariantCopyScope fromOrdinal(int ordinal) {
        VariantCopyScope[] all = values();
        return ordinal < 0 || ordinal >= all.length ? BOTH : all[ordinal];
    }

    /** The next scope, wrapping — what the toolbar cell's click does. */
    public VariantCopyScope next() {
        VariantCopyScope[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
