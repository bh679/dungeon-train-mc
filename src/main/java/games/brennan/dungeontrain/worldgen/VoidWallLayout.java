package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;

/**
 * Where the see-through wall across the track stands for a camera at world X — the layout half of
 * "you can see into a void, never past it". Pure arithmetic over the band layout: no Minecraft or
 * Distant Horizons types, so it unit-tests without a bootstrap, and every renderer that applies the
 * wall (vanilla / Sodium / Sable culling, the veil, DH) reads the same answer.
 *
 * <h2>The rule</h2>
 * <p>The layout is a list of <em>screens</em>: a stretch of X {@code [from, wallX]} whose far edge hides
 * whatever lies beyond it from a camera that has not yet entered it.</p>
 * <ul>
 *   <li><b>Voids</b> — the End band's two empty holds and the legacy run's {@code VOID} era. A camera
 *       before the void sees into it but not past its far side.</li>
 *   <li><b>Legacy eras</b> — from one era you see the next era but not the one after it, so each
 *       era's core is a screen whose wall is its own far edge.</li>
 * </ul>
 * <p>Only walls <em>ahead</em> (+X) exist: the train only ever runs +X, and once the camera is past a
 * screen, looking back across it is allowed.</p>
 *
 * <h2>The fade</h2>
 * <p>A screen does not switch off the instant the camera enters it. Once the void itself has faded
 * in — the End band's sky lags its terrain by {@code skyOffset} blocks, so the first hold only reads
 * as void once that sky has finished rising — its wall becomes a translucent veil whose strength eases
 * from 1 to 0 ({@link Result#veilStrength}) over {@code fadeFraction} of the screen's length. Only a
 * wall at full strength is culled ({@link Result#cullX}); the veil is drawn over whatever lies behind a
 * fading one.</p>
 *
 * <p>Only a {@link CycleLayout} cycle is handled (the shipped shape); the classic single-period order
 * has no wall. Runs double in length, so the camera's run and both neighbours are consulted — a void
 * just across a run boundary still stands.</p>
 */
public final class VoidWallLayout {

    /** How far through the hold before the upside-down band its wall starts to fade. */
    static final double UPSIDE_DOWN_FADE_AT = 0.65;

    /**
     * How far short of the upside-down lead that hold's wall stands — a chunk, so the stray blocks the
     * lead's edge chunk carries are behind the wall rather than just past it.
     */
    static final long UPSIDE_DOWN_WALL_INSET = 16L;

    private VoidWallLayout() {}

    /**
     * The walls in force for one camera position.
     *
     * @param cullX        nearest wall ahead at full strength: anything wholly past it is not drawn.
     *                     {@link Double#POSITIVE_INFINITY} when there is none
     * @param veilX        the wall currently fading, nearer than {@code cullX}, or
     *                     {@link Double#POSITIVE_INFINITY} when none is
     * @param veilStrength the fading wall's opacity in {@code (0, 1)}; 0 when there is no veil
     */
    public record Result(double cullX, double veilX, double veilStrength) {
        public static final Result NONE =
                new Result(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0);

        public boolean hasCull() {
            return !Double.isInfinite(cullX);
        }

        public boolean hasVeil() {
            return veilStrength > 0.0 && !Double.isInfinite(veilX);
        }

        public boolean isNone() {
            return !hasCull() && !hasVeil();
        }
    }

    /**
     * The walls for a camera at {@code camX}.
     *
     * @param fadeFraction share of a screen's length over which its wall fades once the void has faded
     *                     in, in {@code [0, 1]}; 0 switches it off at that point
     * @param skyOffset    {@code disintegrationSkyFadeOffsetBlocks}: how far the End band's sky lags its
     *                     terrain, which is how long the first void takes to fade in
     * @param voids        whether voids are screens
     * @param legacy       whether legacy eras are screens
     */
    public static Result wallAt(WorldGenCycle cycle, double camX, double fadeFraction, int skyOffset,
                                boolean voids, boolean legacy) {
        CycleLayout layout = cycle.layout();
        if (layout == null || layout.count() == 0 || layout.period() <= 0L || !(voids || legacy)) {
            return Result.NONE;
        }
        Collector c = new Collector(camX, clamp01(fadeFraction));
        int k = runOf(cycle, layout, camX);
        for (int run = Math.max(0, k - 1); run <= k + 1; run++) {
            long runStart = cycle.startX() + CycleLayout.runStart(run, layout.period());
            for (int i = 0; i < layout.count(); i++) {
                CycleLayout.Type type = layout.slot(i).type();
                if (voids && type == CycleLayout.Type.END) {
                    endVoids(cycle, layout, i, runStart, run, skyOffset, clamp01(fadeFraction), c);
                }
                if (type == CycleLayout.Type.LEGACY_RUN) {
                    legacyRun(layout, i, runStart, run, c, voids, legacy);
                }
            }
        }
        return c.result();
    }

    /**
     * The fading wall's strength for a camera {@code into} blocks past a screen's entry edge, over a
     * fade {@code fadeLen} blocks long: 1 before the edge, easing (smoothstep) to 0 at the end.
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
     * The End slot's two empty holds — offsets as {@link Disintegration#endRamp} lays them out. The
     * trailing hold stops short of the upside-down entry lead where that band follows (lap 1), because
     * the lead already shows mirrored terrain.
     *
     * <p>Each wall starts to fade only once the sky has finished changing — the End sky coming in on the
     * way into the band ({@link Disintegration#skyRamp} reaching 1, {@code offset + fade} into the slot),
     * the overworld sky coming back on the way out ({@code skyRamp} back at 0, {@code offset} before the
     * slot's end). On the way out that point is at, or past, the far edge of the second hold, so that
     * wall stands further out — one fade past the sky's return — over the ground reforming beyond the
     * hold, never past the slot. Where the upside-down band follows, its sky changes past the lead,
     * so that hold instead starts fading {@link #UPSIDE_DOWN_FADE_AT} of the way through, ahead of the
     * sky, with its wall {@link #UPSIDE_DOWN_WALL_INSET} short of the lead's edge.</p>
     */
    private static void endVoids(WorldGenCycle cycle, CycleLayout layout, int i, long runStart, int run,
                                 int skyOffset, double fadeFraction, Collector c) {
        long f = Math.max(0, cycle.eFade());
        long vh = Math.max(0, cycle.eVoid());
        long eh = Math.max(0, layout.slot(i).core());
        long slotStart = layout.start(i);
        long slotEnd = slotStart + layout.length(i);

        long secondEnd = slotStart + 3L * f + 2L * vh + eh;
        boolean udFollows = i + 1 < layout.count()
                && layout.slot(i + 1).type() == CycleLayout.Type.UPSIDE_DOWN;
        long wallLimit = udFollows ? slotEnd - cycle.udEntryLeadLen() - UPSIDE_DOWN_WALL_INSET : slotEnd;
        secondEnd = Math.max(slotStart + 3L * f + vh + eh + 1L, Math.min(secondEnd, wallLimit));

        long band = Disintegration.bandLength((int) f, (int) vh, (int) eh);
        long o = Math.min(Math.max(0, skyOffset), Math.max(0L, (band - 2L * f) / 2L));
        long skyFull = f + o;
        c.screen(world(runStart, run, slotStart + f), world(runStart, run, slotStart + Math.max(f, skyFull)),
                world(runStart, run, slotStart + f + vh));

        long second = slotStart + 3L * f + vh + eh;
        long skyBack = slotStart + band - o;
        long holdLen = Math.max(0L, secondEnd - second);
        long fadeLen = (long) Math.ceil(fadeFraction * holdLen);
        long secondWall;
        long secondFade;
        if (udFollows) {
            // The upside-down band's sky takes over later, past the lead — waiting for it would leave no
            // room before the wall, so this hold hands over part-way through, ahead of that change.
            secondWall = secondEnd;
            secondFade = second + Math.round(UPSIDE_DOWN_FADE_AT * holdLen);
        } else {
            secondWall = Math.min(wallLimit, Math.max(secondEnd, skyBack + fadeLen));
            secondFade = Math.min(Math.max(second, skyBack), secondWall);
        }
        c.screen(world(runStart, run, second), world(runStart, run, secondFade), world(runStart, run, secondWall),
                fadeLen << run);
    }

    /**
     * The legacy run: each era's core is a screen when {@code legacy} (see the next era, never the one
     * after), and a {@code VOID} era's core is one when {@code voids}. Registering a core once for both
     * rules is harmless — the same screen twice gives the same answer.
     */
    private static void legacyRun(CycleLayout layout, int i, long runStart, int run, Collector c,
                                  boolean voids, boolean legacy) {
        long slotStart = layout.start(i);
        for (int e = 0; e < layout.eras().length; e++) {
            boolean isVoid = layout.eras()[e].kind() == LegacyBandKind.VOID;
            if (!(legacy || (voids && isVoid))) continue;
            long cs = slotStart + layout.eraCoreStart(e);
            double from = world(runStart, run, cs);
            c.screen(from, from, world(runStart, run, cs + layout.eraCoreLen(e)));
        }
    }

    /** World X of slot-relative base offset {@code u} on {@code run}. */
    private static double world(long runStart, int run, long u) {
        return (double) runStart + (double) (u << run);
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** Folds screens into the nearest full wall ahead and the nearest fading one. */
    private static final class Collector {
        private final double x;
        private final double fade;
        private double cullX = Double.POSITIVE_INFINITY;
        private double veilX = Double.POSITIVE_INFINITY;
        private double veilStrength = 0.0;

        Collector(double x, double fade) {
            this.x = x;
            this.fade = fade;
        }

        /**
         * A screen {@code [from, wallX]}: its wall hides what is past {@code wallX}, holding full strength
         * until {@code fadeFrom} (where the void has faded in) and fading over {@code fade} of its length.
         */
        void screen(double from, double fadeFrom, double wallX) {
            screen(from, fadeFrom, wallX, fade * (wallX - from));
        }

        /** As {@link #screen(double, double, double)}, fading over {@code fadeBlocks} rather than a share. */
        void screen(double from, double fadeFrom, double wallX, double fadeBlocks) {
            if (wallX <= from || x >= wallX) return;
            double start = Math.min(Math.max(from, fadeFrom), wallX);
            double fadeLen = Math.min(fadeBlocks, wallX - start);
            double s = strength(x - start, fadeLen);
            if (s >= 1.0) {
                cullX = Math.min(cullX, wallX);
            } else if (s > 0.0 && wallX < veilX) {
                veilX = wallX;
                veilStrength = s;
            }
        }

        Result result() {
            if (veilX >= cullX) return new Result(cullX, Double.POSITIVE_INFINITY, 0.0);
            return new Result(cullX, veilX, veilStrength);
        }
    }
}
