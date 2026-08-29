package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link Trains#gateWorldXOrRecord} — the anchor→gate-X memory that keeps a re-spawned
 * carriage group on the stage it was originally built with.
 *
 * <p>An anchor can be spawned FRESH more than once: {@code TrainCarriageAppender.cleanupGhostAnchors}
 * deletes and unregisters anchors past the visible edge, and the appender then re-spawns that same
 * pIdx. The stage is resolved from the group's placed world-X, and the train has travelled in the
 * meantime — so without this memory the second spawn lands in a different band, resolves a different
 * stage, and (because the variant pick indexes into the stage-gated pool) builds a different
 * carriage. That was the "exactly one carriage at render distance loads from a different stage"
 * report.</p>
 *
 * <p>Registry-free ints and UUIDs, so no Minecraft bootstrap.</p>
 */
final class TrainsGateWorldXTest {

    @AfterEach
    void reset() {
        Trains.clearRegistry();
    }

    @Test
    @DisplayName("first call records and returns the placed X")
    void firstCallRecords() {
        UUID train = UUID.randomUUID();
        assertEquals(1000, Trains.gateWorldXOrRecord(train, -21, 1000));
    }

    @Test
    @DisplayName("a re-spawn of the same anchor gets the FIRST X back, however far the train has moved")
    void respawnReusesFirstX() {
        UUID train = UUID.randomUUID();
        Trains.gateWorldXOrRecord(train, -21, 1000);
        // The train has since travelled thousands of blocks; the re-spawn is placed way out there.
        assertEquals(1000, Trains.gateWorldXOrRecord(train, -21, 48_000));
        assertEquals(1000, Trains.gateWorldXOrRecord(train, -21, 96_000));
    }

    @Test
    @DisplayName("anchors and trains are independent")
    void perAnchorPerTrain() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Trains.gateWorldXOrRecord(a, -21, 1000);
        assertEquals(2000, Trains.gateWorldXOrRecord(a, -24, 2000), "a different anchor records its own X");
        assertEquals(3000, Trains.gateWorldXOrRecord(b, -21, 3000), "a different train records its own X");
        assertEquals(1000, Trains.gateWorldXOrRecord(a, -21, 9999), "the original is untouched by either");
    }

    @Test
    @DisplayName("unregisterGroup does NOT erase the memory — surviving it is the whole point")
    void survivesUnregister() {
        UUID train = UUID.randomUUID();
        Trains.gateWorldXOrRecord(train, -21, 1000);
        Trains.unregisterGroup(train, -21);   // what cleanupGhostAnchors does before the re-spawn
        assertEquals(1000, Trains.gateWorldXOrRecord(train, -21, 48_000));
    }

    @Test
    @DisplayName("clearRegistry drops it (train wipe / server stop)")
    void clearedWithRegistry() {
        UUID train = UUID.randomUUID();
        Trains.gateWorldXOrRecord(train, -21, 1000);
        Trains.clearRegistry();
        assertEquals(48_000, Trains.gateWorldXOrRecord(train, -21, 48_000), "a wiped train starts fresh");
    }

    @Test
    @DisplayName("eviction drops the anchors farthest from the one in play, keeps the near ones")
    void evictionKeepsTheWindow() {
        Map<Integer, Integer> map = new HashMap<>();
        for (int anchor = -50; anchor <= 50; anchor++) map.put(anchor, anchor * 100);

        Trains.evictFarthestFrom(map, 0, 21);

        assertEquals(21, map.size());
        for (int anchor = -10; anchor <= 10; anchor++) {
            assertTrue(map.containsKey(anchor), "near anchor " + anchor + " must survive");
        }
        assertFalse(map.containsKey(-50), "farthest anchor must be evicted");
        assertFalse(map.containsKey(50), "farthest anchor must be evicted");
    }

    @Test
    @DisplayName("eviction is a no-op within the cap")
    void evictionNoopWithinCap() {
        Map<Integer, Integer> map = new HashMap<>();
        for (int anchor = 0; anchor < 10; anchor++) map.put(anchor, anchor);
        Trains.evictFarthestFrom(map, 0, Trains.MAX_REMEMBERED_GATE_ANCHORS);
        assertEquals(10, map.size());
    }

    @Test
    @DisplayName("the live path stays under its cap as anchors march away from the origin")
    void livePathBounded() {
        UUID train = UUID.randomUUID();
        int newest = 0;
        for (int anchor = 0; anchor > -(Trains.MAX_REMEMBERED_GATE_ANCHORS + 500); anchor -= 3) {
            Trains.gateWorldXOrRecord(train, anchor, -anchor * 37);
            newest = anchor;
        }
        // The most recent anchor is still pinned; that is the one a frontier re-spawn asks for.
        assertEquals(-newest * 37, Trains.gateWorldXOrRecord(train, newest, 123_456));
    }
}
