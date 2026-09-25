package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.LapBand;

import java.util.List;
import java.util.OptionalInt;

/**
 * The band cell at the end of a gated editor row, as a two-level world-space selector.
 * <ul>
 *   <li><b>Lap view</b> — four cells {@code V M L C}. Each cell's background is split into one segment
 *       per band of that lap, lit where the band is on, so its position shows <em>which</em> are on
 *       (Mod lit only at its right-hand end = only its last bands). Click opens the lap; shift-click
 *       toggles the whole lap.</li>
 *   <li><b>Band view</b> — a {@code ‹} back cell carrying the lap letter, then that lap's band letters.
 *       Click flips a band; shift-click flips every other band (the long-standing "others" idiom).</li>
 * </ul>
 * Holds the geometry every hit-test and draw path shares (so they can't drift apart) and the pure mask
 * arithmetic. Every edit produces a whole new mask, or {@link OptionalInt#empty()} when it would
 * leave no band — the gate reads an empty set as "every band", so the UI refuses instead.
 *
 * <p>{@code slotIdx} encoding (carried in {@code Hovered.slotIdx} for {@code CellKind.PHASE}):
 * {@code 0..3} a lap cell, {@link #BACK_SLOT} the back cell, {@code BAND_BASE + ordinal} a band.</p>
 */
public final class LapBandCells {

    private LapBandCells() {}

    /** Layout units of the whole band cell (the min/max cells are 1 unit each). */
    static final double UNITS = 5.4;
    /** Fraction of the cell the band view's back cell takes. */
    static final double BACK_FRACTION = 0.14;
    public static final int BACK_SLOT = 50;
    public static final int BAND_BASE = 100;

    private static final LapBand.Lap[] LAPS = LapBand.Lap.values();

    // ---------- slots ----------

    public static boolean isLapSlot(int slot) {
        return slot >= 0 && slot < LAPS.length;
    }

    public static boolean isBandSlot(int slot) {
        return slot >= BAND_BASE && slot < BAND_BASE + LapBand.values().length;
    }

    public static LapBand.Lap lapOf(int slot) {
        return LAPS[slot];
    }

    public static LapBand bandOf(int slot) {
        return LapBand.values()[slot - BAND_BASE];
    }

    public static int bandSlot(LapBand band) {
        return BAND_BASE + band.ordinal();
    }

    // ---------- geometry over [left, right] ----------

    /** Left/right edges of {@code slot} within the cell; {@code open} is the lap shown (null = lap view). */
    public static double[] bounds(int slot, double left, double right, LapBand.Lap open) {
        double w = right - left;
        if (open == null) {
            int i = Math.max(0, Math.min(LAPS.length - 1, slot));
            return new double[] {left + w * i / LAPS.length, left + w * (i + 1) / LAPS.length};
        }
        double backR = left + w * BACK_FRACTION;
        if (slot == BACK_SLOT || !isBandSlot(slot)) return new double[] {left, backR};
        List<LapBand> members = open.members();
        int i = Math.max(0, members.indexOf(bandOf(slot)));
        double step = (right - backR) / members.size();
        return new double[] {backR + step * i, backR + step * (i + 1)};
    }

    /** The slot under {@code x} (clamped into the cell). */
    public static int slotAt(double x, double left, double right, LapBand.Lap open) {
        double w = right - left;
        if (w <= 0) return open == null ? 0 : BACK_SLOT;
        double t = Math.max(0.0, Math.min(0.999999, (x - left) / w));
        if (open == null) return (int) (t * LAPS.length);
        if (t < BACK_FRACTION) return BACK_SLOT;
        List<LapBand> members = open.members();
        int i = (int) ((t - BACK_FRACTION) / (1.0 - BACK_FRACTION) * members.size());
        return bandSlot(members.get(Math.min(i, members.size() - 1)));
    }

    // ---------- drawing ----------

    /** Minimal drawing surface, so the type-menu and parts-menu renderers share one draw path. */
    public interface Painter {
        void quad(double left, double bottom, double right, double top, int argb);

        void text(String text, double centreX, double centreY, int argb);
    }

    static final int CELL_BG = 0x40303030;
    static final int SEGMENT_ON = 0x9055DD55;
    static final int LETTER_ON = 0xFF66FF66;
    static final int LETTER_OFF = 0xFF777777;
    static final int LAP_LETTER = 0xFFFFFFFF;
    static final int BACK_TEXT = 0xFF66E0FF;
    static final int HOVER = 0x60FFCC33;
    private static final double INSET = 0.005;

    /** Draw the band cell for {@code mask} over [{@code left}, {@code right}] × [{@code bottom}, {@code top}]. */
    public static void draw(Painter p, int mask, double left, double right, double bottom, double top,
                            LapBand.Lap open, int hoverSlot) {
        double cy = (bottom + top) / 2.0;
        double b = bottom + INSET;
        double t = top - INSET;
        if (open == null) {
            for (int i = 0; i < LAPS.length; i++) {
                LapBand.Lap lap = LAPS[i];
                double[] e = bounds(i, left, right, null);
                double l = e[0] + INSET;
                double r = e[1] - INSET;
                p.quad(l, b, r, t, CELL_BG);
                List<LapBand> members = lap.members();
                double step = (r - l) / members.size();
                for (int k = 0; k < members.size(); k++) {
                    if ((mask & members.get(k).bit()) != 0) p.quad(l + step * k, b, l + step * (k + 1), t, SEGMENT_ON);
                }
                if (hoverSlot == i) p.quad(l, b, r, t, HOVER);
                p.text(lap.letter(), (l + r) / 2.0, cy, LAP_LETTER);
            }
            return;
        }
        double[] back = bounds(BACK_SLOT, left, right, open);
        p.quad(back[0] + INSET, b, back[1] - INSET, t, CELL_BG);
        if (hoverSlot == BACK_SLOT) p.quad(back[0] + INSET, b, back[1] - INSET, t, HOVER);
        p.text("‹" + open.letter(), (back[0] + back[1]) / 2.0, cy, BACK_TEXT);
        for (LapBand band : open.members()) {
            int slot = bandSlot(band);
            double[] e = bounds(slot, left, right, open);
            if (hoverSlot == slot) p.quad(e[0] + INSET, b, e[1] - INSET, t, HOVER);
            p.text(band.letter(), (e[0] + e[1]) / 2.0, cy, (mask & band.bit()) != 0 ? LETTER_ON : LETTER_OFF);
        }
    }

    // ---------- clicks ----------

    /**
     * Route a click on {@code slot} of the row {@code rowKey} whose bands are {@code mask}: a lap
     * opens its letters (shift: toggles the whole lap), the back cell returns to the laps, a band flips
     * (shift: flips every other band). A changed mask goes to {@code send}; one that would leave no
     * band calls {@code refused} instead.
     */
    public static void click(String rowKey, int mask, int slot, boolean shift,
                             java.util.function.IntConsumer send, Runnable refused) {
        OptionalInt next;
        if (isLapSlot(slot)) {
            if (!shift) {
                LapBandView.open(rowKey, lapOf(slot));
                return;
            }
            next = toggleLap(mask, lapOf(slot));
        } else if (isBandSlot(slot)) {
            next = clickBand(mask, bandOf(slot), shift);
        } else {
            if (slot == BACK_SLOT) LapBandView.close();
            return;
        }
        if (next.isEmpty()) {
            refused.run();
            return;
        }
        if (next.getAsInt() != mask) send.accept(next.getAsInt());
    }

    // ---------- mask arithmetic ----------

    /** Shift-click on a lap: a fully-on lap turns off, otherwise the whole lap turns on. */
    public static OptionalInt toggleLap(int mask, LapBand.Lap lap) {
        boolean allOn = (mask & lap.mask()) == lap.mask();
        return nonEmpty(allOn ? mask & ~lap.mask() : mask | lap.mask());
    }

    /** Click on a band: flip it. Shift-click: flip every other band. */
    public static OptionalInt clickBand(int mask, LapBand band, boolean shift) {
        return nonEmpty(shift ? mask ^ (LapBand.ALL_MASK & ~band.bit()) : mask ^ band.bit());
    }

    private static OptionalInt nonEmpty(int mask) {
        int m = mask & LapBand.ALL_MASK;
        return m == 0 ? OptionalInt.empty() : OptionalInt.of(m);
    }
}
