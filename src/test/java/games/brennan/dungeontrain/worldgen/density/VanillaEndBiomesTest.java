package games.brennan.dungeontrain.worldgen.density;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the erosion thresholds to vanilla 1.21.1 {@code TheEndBiomeSource#getNoiseBiome}. */
class VanillaEndBiomesTest {

    private static final int HIGHLANDS = 1, MIDLANDS = 2, SMALL_ISLANDS = 3, BARRENS = 4;

    @Test
    void highlandsStrictlyAboveQuarter() {
        assertEquals(HIGHLANDS, VanillaEndBiomes.classify(0.2500001));
        assertEquals(MIDLANDS, VanillaEndBiomes.classify(0.25));
    }

    @Test
    void midlandsFromMinusOneSixteenthInclusive() {
        assertEquals(MIDLANDS, VanillaEndBiomes.classify(-0.0625));
        assertEquals(BARRENS, VanillaEndBiomes.classify(-0.0625001));
    }

    @Test
    void smallIslandsStrictlyBelowThreshold() {
        assertEquals(BARRENS, VanillaEndBiomes.classify(-0.21875));
        assertEquals(SMALL_ISLANDS, VanillaEndBiomes.classify(-0.2187501));
        assertEquals(SMALL_ISLANDS, VanillaEndBiomes.classify(-1.0));
    }
}
