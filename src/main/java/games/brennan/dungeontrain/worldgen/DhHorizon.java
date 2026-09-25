package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;

import java.util.OptionalLong;

/**
 * How far a long-distance renderer (Distant Horizons) may draw from a camera at world X without
 * showing what the band layout means to keep hidden. Pure layout arithmetic — no DH types, no level —
 * so it is unit-tested and shared by whatever applies the result.
 *
 * <p>The answer is a <em>visible window</em> {@code [lo, hi]} of world X around the camera, narrowed by
 * two independent rules; the render radius is the camera's distance to the nearer window edge.</p>
 *
 * <ul>
 *   <li><b>Voids.</b> The End band's two empty void holds and the legacy run's {@code VOID} era. From
 *       either side you may see into a void, never past its far side; from inside one, both sides are
 *       past it. The trailing hold stops short of the upside-down entry lead where that band follows
 *       (lap 1), because the lead already shows mirrored terrain.</li>
 *   <li><b>Legacy eras.</b> The legacy run as bands {@code [before, era0 … eraN-1, after]}: from a
 *       band's core you may see the neighbouring bands' cores, but not the crossfades into the band
 *       beyond them; from a crossfade you may see the two bands it joins.</li>
 * </ul>
 *
 * <p>Only a {@link CycleLayout} cycle is handled (the shipped shape); the classic single-period order
 * reports no cap. Runs double in length, so the camera's run and both neighbours are consulted —
 * a void or legacy run just across a run boundary still narrows the window.</p>
 */
public final class DhHorizon {

    private DhHorizon() {}

    /**
     * The stretch of world X that may be seen from {@code camX}: nothing past a void (when {@code voids})
     * and nothing beyond the next legacy band (when {@code legacy}). Either edge may be infinite; both
     * are infinite when nothing narrows the view. Only X is limited — the view across the track is not.
     */
    public record XWindow(double lo, double hi) {
        public static final XWindow OPEN = new XWindow(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        public boolean isOpen() {
            return Double.isInfinite(lo) && Double.isInfinite(hi);
        }

        /** Whether the whole X span {@code [minX, maxX]} lies inside the window. */
        public boolean contains(double minX, double maxX) {
            return minX >= lo && maxX <= hi;
        }
    }

    /**
     * Largest render radius in blocks that shows nothing past a void (when {@code voids}) and nothing
     * beyond the next legacy band (when {@code legacy}), or empty when nothing narrows the view.
     */
    public static OptionalLong capBlocks(WorldGenCycle cycle, double camX, boolean voids, boolean legacy) {
        XWindow w = window(cycle, camX, voids, legacy);
        double r = Math.min(camX - w.lo(), w.hi() - camX);
        if (Double.isInfinite(r) || Double.isNaN(r)) return OptionalLong.empty();
        return OptionalLong.of(Math.max(0L, (long) Math.floor(r)));
    }

    /** See {@link XWindow}. */
    public static XWindow window(WorldGenCycle cycle, double camX, boolean voids, boolean legacy) {
        CycleLayout layout = cycle.layout();
        if (layout == null || layout.count() == 0 || layout.period() <= 0L || !(voids || legacy)) {
            return XWindow.OPEN;
        }
        Window w = new Window(camX);
        int k = runOf(cycle, layout, camX);
        for (int run = Math.max(0, k - 1); run <= k + 1; run++) {
            long runStart = cycle.startX() + CycleLayout.runStart(run, layout.period());
            for (int i = 0; i < layout.count(); i++) {
                CycleLayout.Type type = layout.slot(i).type();
                if (voids && type == CycleLayout.Type.END) {
                    endVoids(cycle, layout, i, runStart, run, w);
                }
                if (type == CycleLayout.Type.LEGACY_RUN) {
                    legacyRun(layout, i, runStart, run, w, voids, legacy);
                }
            }
        }
        return new XWindow(w.lo, w.hi);
    }

    /**
     * {@link #window} with a buffer of {@code bufferBlocks} before any edge relaxes. An edge that
     * tightens does so at once — nothing past a boundary is ever shown — but an edge only relaxes once
     * the windows {@code bufferBlocks} behind and ahead of the camera agree it may, so riding back and
     * forth across a boundary does not flip the view.
     *
     * @param previous the window applied last tick ({@link XWindow#OPEN} to start fresh)
     */
    public static XWindow buffered(XWindow previous, WorldGenCycle cycle, double camX, double bufferBlocks,
                                   boolean voids, boolean legacy) {
        XWindow here = window(cycle, camX, voids, legacy);
        if (bufferBlocks <= 0.0) return here;
        return buffer(previous, here,
                window(cycle, camX - bufferBlocks, voids, legacy),
                window(cycle, camX + bufferBlocks, voids, legacy));
    }

    /**
     * Pure core of {@link #buffered}: tighten each edge to {@code here} at once; relax it only as far as
     * the windows {@code behind} and {@code ahead} of the camera also allow.
     */
    static XWindow buffer(XWindow previous, XWindow here, XWindow behind, XWindow ahead) {
        double lo = here.lo() > previous.lo()
                ? here.lo()
                : Math.min(previous.lo(), Math.max(here.lo(), Math.max(behind.lo(), ahead.lo())));
        double hi = here.hi() < previous.hi()
                ? here.hi()
                : Math.max(previous.hi(), Math.min(here.hi(), Math.min(behind.hi(), ahead.hi())));
        return new XWindow(lo, hi);
    }

    /** Doubling run the camera is in (0 before the anchor). */
    private static int runOf(WorldGenCycle cycle, CycleLayout layout, double camX) {
        long off = (long) Math.floor(camX) - cycle.startX();
        return off < 0L ? 0 : CycleLayout.runIndex(off, layout.period());
    }

    /** The End slot's two empty holds — see {@link Disintegration#endRamp} for the offsets. */
    private static void endVoids(WorldGenCycle cycle, CycleLayout layout, int i, long runStart, int run, Window w) {
        long f = Math.max(0, cycle.eFade());
        long vh = Math.max(0, cycle.eVoid());
        long eh = Math.max(0, layout.slot(i).core());
        long slotStart = layout.start(i);
        long slotEnd = slotStart + layout.length(i);

        long secondEnd = slotStart + 3L * f + 2L * vh + eh;
        boolean udFollows = i + 1 < layout.count()
                && layout.slot(i + 1).type() == CycleLayout.Type.UPSIDE_DOWN;
        if (udFollows) secondEnd = Math.min(secondEnd, slotEnd - cycle.udEntryLeadLen());

        w.void_(world(runStart, run, slotStart + f), world(runStart, run, slotStart + f + vh));
        w.void_(world(runStart, run, slotStart + 3L * f + vh + eh), world(runStart, run, secondEnd));
    }

    /** The legacy run's bands (plus its {@code VOID} era as a void). */
    private static void legacyRun(CycleLayout layout, int i, long runStart, int run, Window w,
                                  boolean voids, boolean legacy) {
        int n = layout.eras().length;
        if (n == 0) return;
        long slotStart = layout.start(i);
        long slotEnd = slotStart + layout.length(i);
        // Band b in [0, n+1]: 0 = before the run, 1..n = era b-1, n+1 = after. Core edges in world X.
        double[] coreLo = new double[n + 2];
        double[] coreHi = new double[n + 2];
        coreLo[0] = Double.NEGATIVE_INFINITY;
        coreHi[0] = world(runStart, run, slotStart);
        for (int e = 0; e < n; e++) {
            long cs = slotStart + layout.eraCoreStart(e);
            coreLo[e + 1] = world(runStart, run, cs);
            coreHi[e + 1] = world(runStart, run, cs + layout.eraCoreLen(e));
            if (voids && layout.eras()[e].kind() == LegacyBandKind.VOID) {
                w.void_(coreLo[e + 1], coreHi[e + 1]);
            }
        }
        coreLo[n + 1] = world(runStart, run, slotEnd);
        coreHi[n + 1] = Double.POSITIVE_INFINITY;
        if (legacy) w.legacy(coreLo, coreHi);
    }

    /** World X of slot-relative base offset {@code u} on {@code run}. */
    private static double world(long runStart, int run, long u) {
        return (double) runStart + (double) (u << run);
    }

    /** The visible window {@code [lo, hi]} around the camera, narrowed rule by rule. */
    private static final class Window {
        private final double x;
        private double lo = Double.NEGATIVE_INFINITY;
        private double hi = Double.POSITIVE_INFINITY;

        Window(double x) {
            this.x = x;
        }

        /** A void {@code [from, to]}: the far side of it is out of view, from wherever the camera is. */
        void void_(double from, double to) {
            if (to <= from) return;
            if (x < from) {
                hi = Math.min(hi, to);
            } else if (x > to) {
                lo = Math.max(lo, from);
            } else {
                lo = Math.max(lo, from);
                hi = Math.min(hi, to);
            }
        }

        /** Legacy bands by core edges: see the neighbouring cores, never the band beyond them. */
        void legacy(double[] coreLo, double[] coreHi) {
            int last = coreLo.length - 1;
            for (int b = 0; b <= last; b++) {
                if (x >= coreLo[b] && x <= coreHi[b]) {                       // in band b's core
                    if (b - 1 >= 0) lo = Math.max(lo, coreLo[b - 1]);
                    if (b + 1 <= last) hi = Math.min(hi, coreHi[b + 1]);
                    return;
                }
                if (b < last && x > coreHi[b] && x < coreLo[b + 1]) {         // crossfade b → b+1
                    lo = Math.max(lo, coreLo[b]);
                    hi = Math.min(hi, coreHi[b + 1]);
                    return;
                }
            }
        }
    }
}
