package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.BandOption;
import games.brennan.dungeontrain.worldgen.LapBand;

import java.util.OptionalInt;

/**
 * The band cell at the end of a gated editor row: one flat row of {@link BandOption} cells,
 * {@code O N E C │ P F O │ C S}, with a small gap between the option groups. Each cell's background
 * shows how much of the option is on — green all, amber some (migrated data can split an option),
 * dim none.
 * <ul>
 *   <li><b>Click</b> an option: all on turns it off; some or none turns it all on.</li>
 *   <li><b>Shift-click</b>: toggle that option's whole group (Main / Legacy / Corrupt).</li>
 * </ul>
 * Holds the geometry every hit-test and draw path shares (so they can't drift apart) and the pure mask
 * arithmetic. Every edit produces a whole new {@link LapBand} mask, or {@link OptionalInt#empty()} when
 * it would leave no band — the gate reads an empty set as "every band", so the UI refuses instead.
 *
 * <p>{@code slotIdx} (carried in {@code Hovered.slotIdx} for {@code CellKind.PHASE}) is the
 * {@link BandOption} ordinal.</p>
 */
public final class LapBandCells {

    private LapBandCells() {}

    /** Layout units of the whole band cell (the min/max cells are 1 unit each). */
    static final double UNITS = 5.4;
    /** Width of the gap between option groups, in option-cell widths. */
    static final double GROUP_GAP = 0.35;

    private static final BandOption[] OPTIONS = BandOption.values();

    // ---------- slots ----------

    public static boolean isOptionSlot(int slot) {
        return slot >= 0 && slot < OPTIONS.length;
    }

    public static BandOption optionOf(int slot) {
        return OPTIONS[slot];
    }

    // ---------- geometry over [left, right] ----------

    /** Cell-width units of the row: one per option plus the gaps between groups. */
    private static double rowUnits() {
        return OPTIONS.length + GROUP_GAP * (BandOption.Group.values().length - 1);
    }

    /** Left edge of option {@code i}, in cell-width units from the row's left. */
    private static double startUnits(int i) {
        return i + GROUP_GAP * OPTIONS[i].group().ordinal();
    }

    /** Left/right edges of option {@code slot} within the cell. */
    public static double[] bounds(int slot, double left, double right) {
        int i = Math.max(0, Math.min(OPTIONS.length - 1, slot));
        double unit = (right - left) / rowUnits();
        double l = left + startUnits(i) * unit;
        return new double[] {l, l + unit};
    }

    /** The option under {@code x}; a point in a group gap goes to the nearer neighbour. */
    public static int slotAt(double x, double left, double right) {
        if (right <= left) return 0;
        double u = (x - left) / (right - left) * rowUnits();
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < OPTIONS.length; i++) {
            double s = startUnits(i);
            double d = u < s ? s - u : (u > s + 1 ? u - s - 1 : 0);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    // ---------- drawing ----------

    /** Minimal drawing surface, so the type-menu and parts-menu renderers share one draw path. */
    public interface Painter {
        void quad(double left, double bottom, double right, double top, int argb);

        void text(String text, double centreX, double centreY, int argb);
    }

    static final int CELL_OFF = 0x40303030;
    static final int CELL_ALL = 0x9055DD55;
    static final int CELL_SOME = 0x90D0A030;
    static final int LETTER = 0xFFFFFFFF;
    static final int LETTER_OFF = 0xFF888888;
    static final int HOVER = 0x60FFCC33;
    private static final double INSET = 0.005;

    /** Draw the option row for {@code mask} over [{@code left}, {@code right}] × [{@code bottom}, {@code top}]. */
    public static void draw(Painter p, int mask, double left, double right, double bottom, double top, int hoverSlot) {
        double cy = (bottom + top) / 2.0;
        for (BandOption option : OPTIONS) {
            double[] e = bounds(option.ordinal(), left, right);
            double l = e[0] + INSET;
            double r = e[1] - INSET;
            double b = bottom + INSET;
            double t = top - INSET;
            BandOption.State state = option.state(mask);
            p.quad(l, b, r, t, switch (state) {
                case ALL -> CELL_ALL;
                case SOME -> CELL_SOME;
                case NONE -> CELL_OFF;
            });
            if (hoverSlot == option.ordinal()) p.quad(l, b, r, t, HOVER);
            p.text(option.letter(), (l + r) / 2.0, cy, state == BandOption.State.NONE ? LETTER_OFF : LETTER);
        }
    }

    // ---------- clicks ----------

    /**
     * Route a click on option {@code slot} of a row whose bands are {@code mask}: plain toggles the
     * option, shift toggles its whole group. A changed mask goes to {@code send}; one that would leave
     * no band calls {@code refused} instead.
     */
    public static void click(int mask, int slot, boolean shift, java.util.function.IntConsumer send, Runnable refused) {
        if (!isOptionSlot(slot)) return;
        BandOption option = optionOf(slot);
        OptionalInt next = shift ? toggle(mask, option.group().mask()) : toggle(mask, option.mask());
        if (next.isEmpty()) {
            refused.run();
            return;
        }
        if (next.getAsInt() != mask) send.accept(next.getAsInt());
    }

    // ---------- mask arithmetic ----------

    /** Toggle a set of bands as one: all on turns them off, otherwise they all turn on. */
    public static OptionalInt toggle(int mask, int bands) {
        boolean allOn = (mask & bands) == bands;
        return nonEmpty(allOn ? mask & ~bands : mask | bands);
    }

    private static OptionalInt nonEmpty(int mask) {
        int m = mask & LapBand.ALL_MASK;
        return m == 0 ? OptionalInt.empty() : OptionalInt.of(m);
    }
}
