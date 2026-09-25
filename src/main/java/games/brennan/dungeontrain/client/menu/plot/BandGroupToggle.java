package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.BandGroup;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.OptionalInt;

/**
 * The band cell on a gated type-menu row: four tri-state {@link BandGroup} toggles followed by a
 * "▸" cell that opens the band picker. Holds the cell geometry (shared by every hit-test and draw
 * path so they cannot drift apart) and the pure mask arithmetic behind each click.
 *
 * <p>Every mask operation returns {@link OptionalInt#empty()} when it would select no bands — the
 * gate normalises an empty set to "all bands", so the UI refuses the click instead.</p>
 */
public final class BandGroupToggle {

    private BandGroupToggle() {}

    /** Number of group slots; the picker slot sits right after them. */
    public static final int GROUP_SLOTS = BandGroup.values().length;
    /** {@code slotIdx} of the "▸" picker cell. */
    public static final int PICKER_SLOT = GROUP_SLOTS;
    /** Layout units of one group cell (the min/max cells are 1 unit each). */
    static final double GROUP_UNITS = 1.2;
    /** Layout units of the picker cell. */
    static final double PICKER_UNITS = 0.5;
    /** Layout units of the whole band cell. */
    static final double UNITS = GROUP_SLOTS * GROUP_UNITS + PICKER_UNITS;

    // ---------- geometry over [left, right] ----------

    /** Left edge of {@code slot} (0..{@link #PICKER_SLOT}) within the band cell [{@code left}, {@code right}]. */
    static double slotLeft(int slot, double left, double right) {
        double unit = (right - left) / UNITS;
        return left + Math.min(slot, GROUP_SLOTS) * GROUP_UNITS * unit;
    }

    /** Right edge of {@code slot} within the band cell. */
    static double slotRight(int slot, double left, double right) {
        return slot >= PICKER_SLOT ? right : slotLeft(slot + 1, left, right);
    }

    /** The slot under {@code x}, clamped to 0..{@link #PICKER_SLOT}. */
    static int slotAt(double x, double left, double right) {
        if (right <= left) return 0;
        for (int slot = 0; slot < PICKER_SLOT; slot++) {
            if (x < slotRight(slot, left, right)) return slot;
        }
        return PICKER_SLOT;
    }

    // ---------- mask arithmetic ----------

    /**
     * A group-cell click. Plain: a fully-on group turns off, otherwise the whole group turns on.
     * Shift: solo the group (only its bands stay on).
     */
    public static OptionalInt clickGroup(int mask, BandGroup group, boolean shift) {
        if (shift) return OptionalInt.of(group.mask());
        int next = group.state(mask) == BandGroup.State.ALL ? mask & ~group.mask() : mask | group.mask();
        return nonEmpty(next);
    }

    /** Flip one band. */
    public static OptionalInt toggleBand(int mask, TrainPhase phase) {
        return nonEmpty(mask ^ phase.bit());
    }

    /** Flip every band. */
    public static OptionalInt invert(int mask) {
        return nonEmpty(~mask);
    }

    private static OptionalInt nonEmpty(int mask) {
        int m = mask & TrainPhase.ALL_MASK;
        return m == 0 ? OptionalInt.empty() : OptionalInt.of(m);
    }
}
