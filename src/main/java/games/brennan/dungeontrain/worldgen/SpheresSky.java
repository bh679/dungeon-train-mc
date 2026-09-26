package games.brennan.dungeontrain.worldgen;

/**
 * The spheres band's End-sky window, as an intensity {@code 0..1}: overworld sky until
 * {@link SpheresSegments#endSkyStart}, crossfading in over {@code fadeIn} blocks, then End sky until the
 * spheres run out — {@code exitVoidBlocks} before the band's end — fading back to the overworld sky over
 * the {@code fadeOut} blocks before that, so the closing void is under an overworld sky. Pure; the client
 * reads it through {@code ClientVoidBand}.
 */
public final class SpheresSky {

    private SpheresSky() {}

    /** End-sky intensity at {@code worldX}. */
    public static double endSky(WorldGenCycle cycle, SpheresSegments seg, int worldX, int fadeIn, int fadeOut,
                                int exitVoidBlocks) {
        long end = cycle.spheresLen() - Math.max(0, exitVoidBlocks);
        return cycle.spheresSkyWindowRamp(worldX, seg.endSkyStart(), end, fadeIn, fadeOut);
    }
}
