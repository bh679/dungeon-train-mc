package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link WorldGenCycle#overworldGapAt} and {@link SecondLapOverworld}. Same geometry as
 * {@link WorldGenCycleTest}: anchor 1000, owGap 300, netherLen 660, endLen 680, period 1940. Offsets
 * from the anchor: lead gap [0,300), Nether [300,960), post-Nether gap [960,1260), End [1260,1940).
 */
final class SecondLapOverworldTest {

    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
    private static final int PERIOD = 1940;

    @Test
    @DisplayName("gaps: lead [0,300), post-Nether [960,1260), none inside the bands or before the anchor")
    void gaps() {
        assertEquals(WorldGenCycle.OverworldGap.NONE, C.overworldGapAt(999));
        assertEquals(WorldGenCycle.OverworldGap.LEAD, C.overworldGapAt(1000));
        assertEquals(WorldGenCycle.OverworldGap.LEAD, C.overworldGapAt(1299));
        assertEquals(WorldGenCycle.OverworldGap.NONE, C.overworldGapAt(1300));   // Nether band
        assertEquals(WorldGenCycle.OverworldGap.NONE, C.overworldGapAt(1959));
        assertEquals(WorldGenCycle.OverworldGap.POST_NETHER, C.overworldGapAt(1960));
        assertEquals(WorldGenCycle.OverworldGap.POST_NETHER, C.overworldGapAt(2259));
        assertEquals(WorldGenCycle.OverworldGap.NONE, C.overworldGapAt(2260));   // End band
        assertEquals(WorldGenCycle.OverworldGap.LEAD, C.overworldGapAt(1000 + PERIOD)); // next lap
    }

    @Test
    @DisplayName("modded laps are the odd ones: 1, 3, 5 — never 0, even laps or before the anchor")
    void moddedLaps() {
        assertFalse(SecondLapOverworld.isModdedLap(-1));
        assertFalse(SecondLapOverworld.isModdedLap(0));
        assertTrue(SecondLapOverworld.isModdedLap(1));
        assertFalse(SecondLapOverworld.isModdedLap(2));
        assertTrue(SecondLapOverworld.isModdedLap(3));
    }

    @Test
    @DisplayName("stretches: WWOO before and BoP after the Nether band on odd laps only")
    void stretches() {
        // Lap 0 — all vanilla.
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 1100));
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 2000));
        // Lap 1 — lead gap [2940,3240) is WWOO, post-Nether gap [3900,4200) is BoP.
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 2939));
        assertEquals(SecondLapOverworld.Stretch.WWOO, SecondLapOverworld.at(C, 2940));
        assertEquals(SecondLapOverworld.Stretch.WWOO, SecondLapOverworld.at(C, 3239));
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 3240)); // Nether band
        assertEquals(SecondLapOverworld.Stretch.BOP, SecondLapOverworld.at(C, 3900));
        assertEquals(SecondLapOverworld.Stretch.BOP, SecondLapOverworld.at(C, 4199));
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 4200)); // End band
        // Lap 2 — vanilla again; lap 3 — modded again.
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, 2940 + PERIOD));
        assertEquals(SecondLapOverworld.Stretch.WWOO, SecondLapOverworld.at(C, 2940 + 2 * PERIOD));
        assertEquals(SecondLapOverworld.Stretch.BOP, SecondLapOverworld.at(C, 3900 + 2 * PERIOD));
        assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(null, 3000));
    }
}
