package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LostCityStructuresTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();

    /** The shipped defaults — the same cycle {@code WorldGenCycleLayoutTest} builds. */
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static final int MARGIN = LostCityStructures.EDGE_MARGIN_BLOCKS;

    /** World X of base coordinate {@code u} on run {@code k}. */
    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    /** Base coordinate where the Lost City core starts, and its length. */
    private static long coreStart() {
        int legacy = LAYOUT.firstIndexOf(CycleLayout.Type.LEGACY_RUN);
        return LAYOUT.start(legacy) + LAYOUT.eraCoreStart(LAYOUT.eraIndex(LegacyBandKind.LOST_CITY));
    }

    @Test
    @DisplayName("the shipped Lost City era runs 4000 blocks straight after Amplified")
    void shippedPlacement() {
        int e = LAYOUT.eraIndex(LegacyBandKind.LOST_CITY);
        assertEquals(LAYOUT.eraIndex(LegacyBandKind.AMPLIFIED) + 1, e);
        assertEquals(LAYOUT.eraIndex(LegacyBandKind.BETA) - 1, e);
        assertEquals(4000L, C.legacyLen(LegacyBandKind.LOST_CITY));
    }

    @Test
    @DisplayName("cities start only in the core, with the margin kept clear of both edges")
    void coreOnly() {
        long cs = coreStart();
        long ce = cs + 4000L;
        int mid = x(cs + 2000L, 0) >> 4;
        assertTrue(LostCityStructures.allowedAt(C, mid));
        // First chunk whose margin clears the core start is allowed; the one before it is not.
        int firstOk = Math.floorDiv(x(cs, 0) + MARGIN + 15, 16);
        assertTrue(LostCityStructures.allowedAt(C, firstOk));
        assertFalse(LostCityStructures.allowedAt(C, firstOk - 1));
        // Right at either edge, in the crossfades, and deep in the neighbouring eras: never.
        assertFalse(LostCityStructures.allowedAt(C, x(cs, 0) >> 4));
        assertFalse(LostCityStructures.allowedAt(C, x(ce - 1, 0) >> 4));
        assertFalse(LostCityStructures.allowedAt(C, x(cs - 240, 0) >> 4));
        assertFalse(LostCityStructures.allowedAt(C, x(ce + 240, 0) >> 4));
        assertFalse(LostCityStructures.allowedAt(C, x(cs - 2500, 0) >> 4));              // Amplified core
        assertFalse(LostCityStructures.allowedAt(C, x(ce + 2000, 0) >> 4));              // Beta core
        assertFalse(LostCityStructures.allowedAt(C, x(1000, 0) >> 4));                   // the first overworld gap
    }

    @Test
    @DisplayName("run 1 stretches the core, and cities follow it")
    void doubling() {
        long cs = coreStart();
        assertTrue(LostCityStructures.allowedAt(C, x(cs + 2000L, 1) >> 4));
        assertFalse(LostCityStructures.allowedAt(C, x(cs - 2500L, 1) >> 4));
    }

    @Test
    @DisplayName("a disabled Lost City era allows no city anywhere")
    void disabled() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        CycleLayout without = CycleLayout.parse(CycleLayout.DEFAULT_ORDER.replace("lost_city=4000:", ""),
                CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        WorldGenCycle c = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), without, 0);
        for (long u = 0; u < without.period(); u += 500) {
            assertFalse(LostCityStructures.allowedAt(c, x(u, 0) >> 4));
        }
    }

    @Test
    @DisplayName("only the big_lost_city namespace is a Lost City structure")
    void namespace() {
        assertTrue(LostCityStructures.isLostCityStructure(ResourceLocation.parse("big_lost_city:tallskyscraper")));
        assertFalse(LostCityStructures.isLostCityStructure(ResourceLocation.parse("minecraft:village_plains")));
        assertFalse(LostCityStructures.isLostCityStructure(ResourceLocation.parse("dungeontrain:lost_city")));
        assertFalse(LostCityStructures.isLostCityStructure(null));
    }
}
