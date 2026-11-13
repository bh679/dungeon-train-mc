package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boot log line of {@link SablePathfinderPresence}, without a live {@code ModList}. */
class SablePathfinderPresenceTest {

    @Test
    void presentLineCarriesVersion() {
        String line = SablePathfinderPresence.describe(true, "1.4.0", true);
        assertTrue(line.startsWith("[DungeonTrain] Sable Pathfinder present (v1.4.0)"), line);
        assertTrue(line.contains("sub-level"), line);
    }

    @Test
    void presentWithoutVersionSaysSo() {
        assertTrue(SablePathfinderPresence.describe(true, null, true).contains("(unknown version)"));
    }

    @Test
    void absentLineNamesVanillaPathing() {
        assertEquals(
            "[DungeonTrain] Sable Pathfinder absent — vanilla mob pathing; carriages are opaque to pathfinding",
            SablePathfinderPresence.describe(false, null, false));
    }

    @Test
    void absentLineNamesOwnPathsWhenLoaded() {
        assertEquals(
            "[DungeonTrain] Sable Pathfinder absent — Sable Mob Paths carries mobs across carriages",
            SablePathfinderPresence.describe(false, null, true));
    }
}
