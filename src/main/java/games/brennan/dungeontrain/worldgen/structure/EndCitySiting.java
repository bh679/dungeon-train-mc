package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.EndIslandGeometry;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The pure "may a city stand here, and at what Y" decision behind {@link BandEndCityStructure}. Extracted
 * from the structure body so it is unit-testable without a NeoForge bootstrap (the same convention as
 * {@link WorldGenCycle} / {@code BandBiomeDecision}); the structure is the thin shell that supplies
 * rotation, the live island geometry and the End-biome check.
 *
 * <p>The rule is vanilla's {@code EndCityStructure} rule, with the band's terrain substituted: probe the
 * four corners of the 5×5 box anchored 7 blocks into the chunk, take the <b>lowest</b> surface, and refuse
 * anything too low. Taking the lowest is what keeps cities off island edges and ragged spires — a corner
 * hanging over the void fails outright.</p>
 */
public final class EndCitySiting {

    /** Vanilla's probe box: anchored 7 blocks into the chunk, 5 blocks across (sign follows rotation). */
    public static final int PROBE_ANCHOR = 7;
    public static final int PROBE_SPAN = 5;

    private EndCitySiting() {}

    /**
     * World Y an End city's base sits at for the probe box anchored at {@code (anchorX, anchorZ)} and
     * extending {@code (spanX, spanZ)}, or {@link Integer#MIN_VALUE} if no city may generate there.
     *
     * <p>Refused when any probed column is outside the End-band core, when any probed column is open void
     * (no island beneath it), or when the resulting surface is below {@code seaLevel} — the band's
     * analogue of vanilla's {@code y < 60} floor, and also the height at which DT's forced End biomes
     * begin, so siting and biome never disagree.</p>
     */
    public static int siteY(WorldGenCycle cycle, EndIslandGeometry geometry, int seaLevel,
                            int anchorX, int anchorZ, int spanX, int spanZ) {
        if (cycle == null || geometry == null) return Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) {
                int x = anchorX + (cx == 0 ? 0 : spanX);
                int z = anchorZ + (cz == 0 ? 0 : spanZ);
                if (!cycle.isEndCore(x)) return Integer.MIN_VALUE;
                int top = geometry.topSolidY(x, z, cycle.endIslandRamp(x));
                if (top == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                lowest = Math.min(lowest, top);
            }
        }
        if (lowest == Integer.MAX_VALUE || lowest < seaLevel) return Integer.MIN_VALUE;
        return lowest;
    }
}
