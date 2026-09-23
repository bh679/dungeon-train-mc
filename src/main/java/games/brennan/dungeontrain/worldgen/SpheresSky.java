package games.brennan.dungeontrain.worldgen;

/**
 * The spheres band's two sky windows, as intensities {@code 0..1} — End sky from
 * {@link SpheresSegments#endSkyStart}, handing over to the Nether sky at
 * {@link SpheresSegments#netherSkyStart}, which holds to the band's end. The handover is a true
 * crossfade centred on {@code netherSkyStart}: the End window runs half a fade past it and the Nether
 * window starts half a fade before it, so neither sky dips back to overworld in between. Pure; the
 * client reads it through {@code ClientVoidBand} / {@code ClientNetherBand}.
 */
public final class SpheresSky {

    private SpheresSky() {}

    /** End-sky intensity at {@code worldX}. */
    public static double endSky(WorldGenCycle cycle, SpheresSegments seg, int worldX, int fade) {
        long half = Math.max(0, fade) / 2;
        return cycle.spheresSkyWindowRamp(worldX, seg.endSkyStart(), seg.netherSkyStart() + half, fade);
    }

    /** Nether-sky intensity at {@code worldX}. */
    public static double netherSky(WorldGenCycle cycle, SpheresSegments seg, int worldX, int fade) {
        long half = Math.max(0, fade) / 2;
        return cycle.spheresSkyWindowRamp(worldX, seg.netherSkyStart() - half, Long.MAX_VALUE, fade);
    }
}
