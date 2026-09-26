package games.brennan.dungeontrain.worldgen;

/**
 * The spheres band's End-sky window, as an intensity {@code 0..1}: overworld sky until
 * {@link SpheresSegments#endSkyStart}, crossfading in over {@code fadeIn} blocks, then End sky to the
 * band's end, fading back to the overworld sky over its last {@code fadeOut} blocks. Pure; the client
 * reads it through {@code ClientVoidBand}.
 */
public final class SpheresSky {

    private SpheresSky() {}

    /** End-sky intensity at {@code worldX}. */
    public static double endSky(WorldGenCycle cycle, SpheresSegments seg, int worldX, int fadeIn, int fadeOut) {
        return cycle.spheresSkyWindowRamp(worldX, seg.endSkyStart(), Long.MAX_VALUE, fadeIn, fadeOut);
    }
}
