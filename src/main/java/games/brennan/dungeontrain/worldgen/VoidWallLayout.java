package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;

/**
 * Where the see-through walls across the track stand for a camera at world X — the layout half of
 * "you can see into a void, never past it". Pure arithmetic over the band layout: no Minecraft or
 * Distant Horizons types, so it unit-tests without a bootstrap, and every renderer that applies the
 * walls (vanilla / Sodium / Sable culling, the sky pass, DH) reads the same answer.
 *
 * <h2>The rule</h2>
 * <p>The layout is a list of <em>screens</em>: a stretch of X {@code [from, wallX]}.</p>
 * <ul>
 *   <li><b>Voids</b> — the End band's two empty holds and the legacy run's {@code VOID} era.</li>
 *   <li><b>Legacy eras</b> — each era's core, so from one era you see the next, never the one after.</li>
 * </ul>
 * <p>Before a screen, its <em>forward</em> wall stands at its far edge: you see into it, never past it.
 * Once through it, its <em>backward</em> wall stands at its near edge: what lies behind it is out of
 * view. The two hand over: while the camera crosses the fade, the forward wall fades out exactly as the
 * backward one fades in ({@code back = 1 - forward}).</p>
 *
 * <h2>When the hand-over happens</h2>
 * <p>Not at the edge. A void only reads as void once its sky has changed, so the fade waits for it: on
 * the way into the End band until its sky has fully risen, and on the way out until the overworld sky
 * is back — which, on the way out, is at or past the hold's far edge, so that wall stands one fade
 * further out. Where the upside-down band follows the End its lead already shows mirrored terrain and
 * its sky changes later still, so that hold hands over {@link #UPSIDE_DOWN_FADE_AT} of the way through
 * instead. A fade lasts {@code fadeFraction} of the screen's length.</p>
 *
 * <p>Only a wall at full strength is culled ({@link Result#cullX}, {@link Result#backCullX}); a fading
 * one is painted over by the sky pass at its strength.</p>
 *
 * <p>Only a {@link CycleLayout} cycle is handled (the shipped shape); the classic single-period order
 * has no wall. Runs double in length, so the camera's run and both neighbours are consulted — a void
 * just across a run boundary still stands.</p>
 */
public final class VoidWallLayout {

    /** How far through the hold before the upside-down band its walls hand over. */
    static final double UPSIDE_DOWN_FADE_AT = 0.65;

    private VoidWallLayout() {}

    /**
     * The walls in force for one camera position.
     *
     * @param cullX         nearest forward wall at full strength: anything past it is not drawn.
     *                      {@link Double#POSITIVE_INFINITY} when there is none
     * @param veilX         the forward wall currently fading, nearer than {@code cullX}, or
     *                      {@link Double#POSITIVE_INFINITY}
     * @param veilStrength  that wall's opacity in {@code (0, 1)}; 0 when there is none
     * @param backCullX     nearest backward wall at full strength: anything before it is not drawn.
     *                      {@link Double#NEGATIVE_INFINITY} when there is none
     * @param backVeilX     the backward wall currently fading in, nearer than {@code backCullX}, or
     *                      {@link Double#NEGATIVE_INFINITY}
     * @param backStrength  that wall's opacity in {@code (0, 1)}; 0 when there is none
     */
    public record Result(double cullX, double veilX, double veilStrength,
                         double backCullX, double backVeilX, double backStrength) {
        public static final Result NONE = new Result(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0);

        public boolean hasCull() {
            return !Double.isInfinite(cullX);
        }

        public boolean hasVeil() {
            return veilStrength > 0.0 && !Double.isInfinite(veilX);
        }

        public boolean hasBackCull() {
            return !Double.isInfinite(backCullX);
        }

        public boolean hasBackVeil() {
            return backStrength > 0.0 && !Double.isInfinite(backVeilX);
        }

        /** Whether either wall is fading — the frames the sky pass must paint a partial wall. */
        public boolean fading() {
            return hasVeil() || hasBackVeil();
        }

        public boolean isNone() {
            return !hasCull() && !hasVeil() && !hasBackCull() && !hasBackVeil();
        }
    }

    /**
     * The walls for a camera at {@code camX}.
     *
     * @param fadeFraction share of a screen's length the hand-over takes, in {@code [0, 1]}; 0 flips it
     *                     at once
     * @param skyOffset    {@code disintegrationSkyFadeOffsetBlocks}: how far the End band's sky lags its
     *                     terrain, which sets when each End void has finished fading in
     * @param voids        whether voids are screens
     * @param legacy       whether legacy eras are screens
     */
    public static Result wallAt(WorldGenCycle cycle, double camX, double fadeFraction, int skyOffset,
                                boolean voids, boolean legacy) {
        CycleLayout layout = cycle.layout();
        if (layout == null || layout.count() == 0 || layout.period() <= 0L || !(voids || legacy)) {
            return Result.NONE;
        }
        double fade = clamp01(fadeFraction);
        Collector c = new Collector(camX);
        int k = runOf(cycle, layout, camX);
        for (int run = Math.max(0, k - 1); run <= k + 1; run++) {
            long runStart = cycle.startX() + CycleLayout.runStart(run, layout.period());
            for (int i = 0; i < layout.count(); i++) {
                CycleLayout.Type type = layout.slot(i).type();
                if (voids && type == CycleLayout.Type.END) {
                    endVoids(cycle, layout, i, runStart, run, skyOffset, fade, c);
                }
                if (type == CycleLayout.Type.LEGACY_RUN) {
                    legacyRun(layout, i, runStart, run, fade, c, voids, legacy);
                }
            }
        }
        return c.result();
    }

    /**
     * The forward wall's strength for a camera {@code into} blocks past the point its fade starts, over
     * a fade {@code fadeLen} blocks long: 1 before it, easing (smoothstep) to 0 at the end. The backward
     * wall's is one minus this.
     */
    static double strength(double into, double fadeLen) {
        if (into <= 0.0) return 1.0;
        if (fadeLen <= 0.0 || into >= fadeLen) return 0.0;
        double t = into / fadeLen;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    /** Doubling run the camera is in (0 before the anchor). */
    private static int runOf(WorldGenCycle cycle, CycleLayout layout, double camX) {
        long off = (long) Math.floor(camX) - cycle.startX();
        return off < 0L ? 0 : CycleLayout.runIndex(off, layout.period());
    }

    /**
     * The End slot's two empty holds — offsets as {@link Disintegration#endRamp} lays them out, sky
     * timing as {@link Disintegration#skyRamp} does. The trailing hold stops short of the upside-down
     * entry lead where that band follows (lap 1), because the lead already shows mirrored terrain.
     */
    private static void endVoids(WorldGenCycle cycle, CycleLayout layout, int i, long runStart, int run,
                                 int skyOffset, double fade, Collector c) {
        long f = Math.max(0, cycle.eFade());
        long vh = Math.max(0, cycle.eVoid());
        long eh = Math.max(0, layout.slot(i).core());
        long slotStart = layout.start(i);
        long slotEnd = slotStart + layout.length(i);
        long band = Disintegration.bandLength((int) f, (int) vh, (int) eh);
        long o = Math.min(Math.max(0, skyOffset), Math.max(0L, (band - 2L * f) / 2L));

        // In: the End sky has fully risen offset + fade into the slot.
        long first = slotStart + f;
        long firstEnd = first + vh;
        long firstFade = slotStart + Math.max(f, f + o);
        c.screen(world(runStart, run, first), world(runStart, run, firstFade), world(runStart, run, firstEnd),
                fade * (world(runStart, run, firstEnd) - world(runStart, run, first)));

        // Out: the overworld sky is back offset before the slot ends — or, before the upside-down band,
        // a set share of the way through the (shortened) hold.
        boolean udFollows = i + 1 < layout.count()
                && layout.slot(i + 1).type() == CycleLayout.Type.UPSIDE_DOWN;
        long wallLimit = udFollows ? slotEnd - cycle.udEntryLeadLen() : slotEnd;
        long second = slotStart + 3L * f + vh + eh;
        long secondEnd = Math.min(slotStart + 3L * f + 2L * vh + eh, wallLimit);
        long holdLen = Math.max(0L, secondEnd - second);
        long fadeLen = (long) Math.ceil(fade * holdLen);
        long secondFade;
        long secondWall;
        if (udFollows) {
            secondFade = second + Math.round(UPSIDE_DOWN_FADE_AT * holdLen);
            secondWall = secondEnd;
        } else {
            long skyBack = slotStart + band - o;
            secondWall = Math.min(wallLimit, Math.max(secondEnd, skyBack + fadeLen));
            secondFade = Math.min(Math.max(second, skyBack), secondWall);
        }
        c.screen(world(runStart, run, second), world(runStart, run, secondFade), world(runStart, run, secondWall),
                (double) (fadeLen << run));
    }

    /**
     * The legacy run: each era's core is a screen when {@code legacy} (see the next era, never the one
     * after), and a {@code VOID} era's core is one when {@code voids}. Legacy eras have no sky change
     * to wait for, so they hand over from their own start.
     */
    private static void legacyRun(CycleLayout layout, int i, long runStart, int run, double fade, Collector c,
                                  boolean voids, boolean legacy) {
        long slotStart = layout.start(i);
        for (int e = 0; e < layout.eras().length; e++) {
            boolean isVoid = layout.eras()[e].kind() == LegacyBandKind.VOID;
            if (!(legacy || (voids && isVoid))) continue;
            long cs = slotStart + layout.eraCoreStart(e);
            double from = world(runStart, run, cs);
            double to = world(runStart, run, cs + layout.eraCoreLen(e));
            c.screen(from, from, to, fade * (to - from));
        }
    }

    /** World X of slot-relative base offset {@code u} on {@code run}. */
    private static double world(long runStart, int run, long u) {
        return (double) runStart + (double) (u << run);
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** Folds screens into the nearest walls either side, full-strength and fading. */
    private static final class Collector {
        private final double x;
        private double cullX = Double.POSITIVE_INFINITY;
        private double veilX = Double.POSITIVE_INFINITY;
        private double veilStrength = 0.0;
        private double backCullX = Double.NEGATIVE_INFINITY;
        private double backVeilX = Double.NEGATIVE_INFINITY;
        private double backStrength = 0.0;

        Collector(double x) {
            this.x = x;
        }

        /**
         * A screen {@code [from, wallX]}: its forward wall at {@code wallX} stands until {@code fadeFrom}
         * and fades out over {@code fadeBlocks}, while its backward wall at {@code from} fades in.
         */
        void screen(double from, double fadeFrom, double wallX, double fadeBlocks) {
            if (wallX <= from) return;
            double start = Math.min(Math.max(from, fadeFrom), wallX);
            double fadeLen = Math.min(fadeBlocks, wallX - start);
            double s = strength(x - start, fadeLen);
            if (s >= 1.0) {
                cullX = Math.min(cullX, wallX);
                return;
            }
            if (s > 0.0 && wallX < veilX) {
                veilX = wallX;
                veilStrength = s;
            }
            double back = 1.0 - s;
            if (back >= 1.0) {
                backCullX = Math.max(backCullX, from);
            } else if (from > backVeilX) {
                backVeilX = from;
                backStrength = back;
            }
        }

        Result result() {
            boolean veil = veilX < cullX;
            boolean backVeil = backVeilX > backCullX;
            return new Result(cullX, veil ? veilX : Double.POSITIVE_INFINITY, veil ? veilStrength : 0.0,
                    backCullX, backVeil ? backVeilX : Double.NEGATIVE_INFINITY, backVeil ? backStrength : 0.0);
        }
    }
}
