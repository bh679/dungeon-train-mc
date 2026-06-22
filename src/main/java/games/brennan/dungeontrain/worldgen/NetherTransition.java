package games.brennan.dungeontrain.worldgen;

/**
 * Pure math for the <b>Nether transition band</b> — a second, independent looping
 * world-gen phase (parallel to the {@link Disintegration} End band) the train crosses
 * where the overworld swells into a world-height mountain, the train tunnels through
 * it, and the far side is the real Nether:
 *
 * <pre>
 *   Overworld → taller mountains → mega-mountain (tunnel) → netherrack → Nether → … → Overworld
 * </pre>
 *
 * <p>The transition is driven by <b>two trapezoidal ramps</b>, both evaluated over the
 * offset {@code d = X − startX} on a cycle that repeats forever along +X, exactly like
 * the End band's {@code middleRamp}/{@code endRamp} pair. With spans {@code F} (fade),
 * {@code Mh} (mountainHold), {@code Cf} (coreFade), {@code Ch} (coreHold) and
 * {@code Oh} (overworldHold), one active band lays out as:</p>
 * <pre>
 *   | F  | Mh |  Cf  |   Ch    |  Cf  | Mh | F  |
 *   |rise|plat|cross |  NETHER |cross |plat|fall|
 *   heightRamp H:  /‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾\        (mountain top height)
 *   netherRamp N:         /‾‾‾‾‾‾‾‾‾‾\               (netherrack → real Nether intensity)
 * </pre>
 *
 * <ul>
 *   <li>{@link #heightRamp} ramps 0→1 over the first fade, holds 1 across the whole
 *       {@code Mh+Cf+Ch+Cf+Mh} interior, ramps 1→0 over the last fade. It scales how
 *       tall the stamped mountain rises: {@code targetTop = bedY + round(H · maxHeight)}.
 *       {@code H = 1} is the world-height mega-mountain.</li>
 *   <li>{@link #netherRamp} is 0 through the rising/falling mountain, ramps 0→1 across
 *       the {@code Cf} crossfade (stone → netherrack dither), holds 1 across the
 *       {@code Ch} real-Nether core, mirrors back. It drives the material crossfade,
 *       the red sky/fog blend, and nether-mob spawning.</li>
 * </ul>
 *
 * <p>No Minecraft types here, so it is unit tested without a NeoForge bootstrap —
 * same convention as {@link Disintegration}.</p>
 */
public final class NetherTransition {

    private NetherTransition() {}

    /** Width of one active band: {@code 2·fade + 2·mountainHold + 2·coreFade + coreHold}. */
    public static long bandLength(int fade, int mountainHold, int coreFade, int coreHold) {
        return 2L * Math.max(0, fade) + 2L * Math.max(0, mountainHold)
                + 2L * Math.max(0, coreFade) + Math.max(0, coreHold);
    }

    /**
     * Length of one full repeating cycle: an active band followed by an overworld
     * stretch — {@code bandLength + owHold}. The pattern tiles this period forever
     * along +X from {@code startX}.
     */
    public static long cyclePeriod(int fade, int mountainHold, int coreFade, int coreHold, int owHold) {
        return bandLength(fade, mountainHold, coreFade, coreHold) + Math.max(0, owHold);
    }

    /**
     * Offset into the current cycle for a world-X (measured in blocks from {@code startX}):
     * {@code (worldX − startX) mod period}, or {@code -1} before {@code startX}. Each cycle
     * is {@code [0, owHold)} = the overworld phase, then {@code [owHold, period)} = the
     * active band, so the player always spawns in normal terrain.
     */
    private static long cycleOffset(int worldX, long startX, int fade, int mountainHold, int coreFade, int coreHold, int owHold) {
        if (worldX < startX) return -1L;
        long period = cyclePeriod(fade, mountainHold, coreFade, coreHold, owHold);
        if (period <= 0L) return -1L;
        return Math.floorMod((long) worldX - startX, period);
    }

    /**
     * Height ramp {@code H ∈ [0,1]}, evaluated on the repeating cycle: 0 across the
     * overworld phase, linear 0→1 as terrain swells into the mega-mountain, flat 1
     * across the whole {@code Mh+Cf+Ch+Cf+Mh} interior, linear 1→0 back to overworld.
     * Scales the stamped mountain top height. Repeats forever from {@code startX}.
     */
    public static double heightRamp(int worldX, long startX, int fade, int mountainHold, int coreFade, int coreHold, int owHold) {
        long d = cycleOffset(worldX, startX, fade, mountainHold, coreFade, coreHold, owHold);
        if (d < 0L) return 0.0;
        int oh = Math.max(0, owHold);
        if (d < oh) return 0.0;                                    // overworld phase (start of each cycle)
        long dd = d - oh;                                          // offset within the active band
        long band = bandLength(fade, mountainHold, coreFade, coreHold);
        int f = Math.max(0, fade);
        if (dd < f) return (double) dd / f;                        // overworld → mountain
        long holdEnd = band - f;
        if (dd < holdEnd) return 1.0;                              // mega-mountain + nether interior
        return 1.0 - (double) (dd - holdEnd) / f;                  // mountain → overworld
    }

    /**
     * Nether ramp {@code N ∈ [0,1]}, evaluated on the repeating cycle: 0 through the
     * overworld + mountain holds, linear 0→1 across the {@code Cf} crossfade (the
     * netherrack transition), flat 1 across the {@code Ch} real-Nether core, linear
     * 1→0 back to mountain. Drives the material crossfade, sky/fog blend and mob spawns.
     */
    public static double netherRamp(int worldX, long startX, int fade, int mountainHold, int coreFade, int coreHold, int owHold) {
        long d = cycleOffset(worldX, startX, fade, mountainHold, coreFade, coreHold, owHold);
        if (d < 0L) return 0.0;
        int oh = Math.max(0, owHold);
        if (d < oh) return 0.0;                                    // overworld phase
        long dd = d - oh;                                          // offset within the active band
        int f = Math.max(0, fade);
        int mh = Math.max(0, mountainHold);
        int cf = Math.max(0, coreFade);
        int ch = Math.max(0, coreHold);
        long n0 = (long) f + mh;          // crossfade in begins
        long n1 = (long) f + mh + cf;     // real-Nether core begins
        long n2 = (long) f + mh + cf + ch; // core ends
        long n3 = (long) f + mh + 2L * cf + ch; // crossfade out ends
        if (dd < n0 || dd >= n3) return 0.0;
        if (dd < n1) return (double) (dd - n0) / cf;               // mountain → netherrack (cf>0 here)
        if (dd < n2) return 1.0;                                    // real-Nether core
        return 1.0 - (double) (dd - n2) / cf;                      // netherrack → mountain (cf>0 here)
    }

    /**
     * World-Y the stamped mountain reaches for a column's {@link #heightRamp} value:
     * {@code bedY + round(H · maxHeight)}, clamped to {@code worldTop}. Pure so the
     * height curve is unit-testable alongside the ramps.
     */
    public static int mountainTopY(double heightRamp, int bedY, int maxHeight, int worldTop) {
        double h = heightRamp < 0.0 ? 0.0 : (heightRamp > 1.0 ? 1.0 : heightRamp);
        int top = bedY + (int) Math.round(h * Math.max(0, maxHeight));
        return Math.min(worldTop, top);
    }
}
