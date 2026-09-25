package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.portal.StretchSites;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld.Stretch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldGenCycle#isOverworldGapAt}, {@link WorldGenCycle#overworldGapRanges} and the
 * dimensional-carriage site rules in {@link StretchSites}: the plain room is only ever cut from vanilla
 * overworld, and each modded room only from its own stretch.
 */
final class OverworldStretchSitesTest {

    /** {@link SecondLapOverworldTest}'s classic geometry: lead [0,300), Nether [300,960), post [960,1260), End [1260,1940). */
    private static final WorldGenCycle CLASSIC =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
    private static final int PERIOD = 1940;

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final WorldGenCycle SHIPPED = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    @Test
    @DisplayName("classic: the gaps and everything before the anchor are overworld; the bands are not")
    void classicGaps() {
        assertTrue(CLASSIC.isOverworldGapAt(-50_000));
        assertTrue(CLASSIC.isOverworldGapAt(1000));
        assertTrue(CLASSIC.isOverworldGapAt(1299));
        assertFalse(CLASSIC.isOverworldGapAt(1300));          // Nether band
        assertFalse(CLASSIC.isOverworldGapAt(1959));
        assertTrue(CLASSIC.isOverworldGapAt(1960));
        assertFalse(CLASSIC.isOverworldGapAt(2260));          // End band
        assertTrue(CLASSIC.isOverworldGapAt(1000 + PERIOD));
    }

    @Test
    @DisplayName("layout: every overworld slot is a gap, every band and legacy slot is not")
    void layoutGaps() {
        assertTrue(SHIPPED.isOverworldGapAt((int) START - 1));
        long p = LAYOUT.period();
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < LAYOUT.count(); i++) {
                long mid = START + CycleLayout.runStart(k, p) + ((LAYOUT.start(i) + LAYOUT.length(i) / 2) << k);
                boolean overworld = LAYOUT.slot(i).type() == CycleLayout.Type.OVERWORLD;
                assertEquals(overworld, SHIPPED.isOverworldGapAt((int) mid),
                        "run " + k + " slot " + i + " " + LAYOUT.slot(i));
            }
        }
    }

    @Test
    @DisplayName("gap ranges cover exactly the overworld slots, and name the modded ones by their style")
    void gapRanges() {
        List<long[]> gaps = SHIPPED.overworldGapRanges(2);
        assertFalse(gaps.isEmpty());
        boolean sawWwoo = false;
        boolean sawBop = false;
        for (long[] g : gaps) {
            assertTrue(SHIPPED.isOverworldGapAt((int) g[0]));
            assertTrue(SHIPPED.isOverworldGapAt((int) g[1] - 1));
            Stretch s = SecondLapOverworld.at(SHIPPED, (int) ((g[0] + g[1]) / 2));
            sawWwoo |= s == Stretch.WWOO;
            sawBop |= s == Stretch.BOP;
        }
        assertTrue(sawWwoo && sawBop, "the shipped layout has both modded stretches in its first run");

        List<long[]> classic = CLASSIC.overworldGapRanges(2);
        assertEquals(4, classic.size());
        assertEquals(1000L, classic.get(0)[0]);
        assertEquals(1300L, classic.get(0)[1]);
        assertEquals(1960L, classic.get(1)[0]);
        assertEquals(2260L, classic.get(1)[1]);
    }

    @Test
    @DisplayName("modded rooms: every picked site sits inside its own stretch, clear of the edges")
    void moddedSitesStayInTheirStretch() {
        for (WorldGenCycle cycle : List.of(CLASSIC, SHIPPED)) {
            for (Stretch stretch : List.of(Stretch.WWOO, Stretch.BOP)) {
                List<int[]> ranges = StretchSites.chunkRanges(cycle, stretch);
                assertFalse(ranges.isEmpty(), stretch + " has somewhere to sample");
                SplittableRandom rnd = new SplittableRandom(42);
                for (int i = 0; i < 5000; i++) {
                    int chunkX = StretchSites.chunkXIn(ranges, rnd.nextDouble(), rnd.nextDouble());
                    assertTrue(StretchSites.matches(cycle, stretch, chunkX * 16),
                            stretch + " site at chunk " + chunkX + " is outside its stretch");
                }
            }
        }
    }

    @Test
    @DisplayName("plain room: scattered sites in a band or a modded stretch are turned away")
    void plainRoomRejectsEverythingElse() {
        // Classic lap 1: WWOO lead [2940,3240), Nether band [3240,3900), BoP post [3900,4200).
        assertFalse(StretchSites.matches(CLASSIC, Stretch.VANILLA, 3000));
        assertFalse(StretchSites.matches(CLASSIC, Stretch.VANILLA, 3500));
        assertFalse(StretchSites.matches(CLASSIC, Stretch.VANILLA, 4000));
        assertTrue(StretchSites.matches(CLASSIC, Stretch.VANILLA, 1100));      // lap 0 lead gap
        assertTrue(StretchSites.matches(CLASSIC, Stretch.VANILLA, -80_000));   // before the anchor
        // A chunk straddling the gap's end is too close to the band.
        assertFalse(StretchSites.matches(CLASSIC, Stretch.VANILLA, 1280));

        // Across the shipped layout, a scattered site is accepted only where it really is vanilla overworld.
        SplittableRandom rnd = new SplittableRandom(7);
        int accepted = 0;
        for (int i = 0; i < 20_000; i++) {
            int x = (int) (START + rnd.nextLong(4 * LAYOUT.period())) & ~15;
            if (!StretchSites.matches(SHIPPED, Stretch.VANILLA, x)) continue;
            accepted++;
            assertTrue(SHIPPED.isOverworldGapAt(x + 8));
            assertEquals(Stretch.VANILLA, SecondLapOverworld.at(SHIPPED, x + 8));
        }
        assertTrue(accepted > 0);
    }

    @Test
    @DisplayName("with no cycle there is no modded stretch to sample, and everything is vanilla")
    void noCycleNoModdedStretch() {
        assertTrue(StretchSites.chunkRanges(null, Stretch.WWOO).isEmpty());
        assertTrue(StretchSites.chunkRanges(null, Stretch.BOP).isEmpty());
        assertTrue(StretchSites.matches(null, Stretch.VANILLA, 12345));
        assertFalse(StretchSites.matches(null, Stretch.WWOO, 12345));
    }
}
