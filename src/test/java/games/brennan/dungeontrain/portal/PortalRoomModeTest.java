package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a portal room does at its walls — id round-trip, total parsing, and the per-mode rules. */
final class PortalRoomModeTest {

    @Test
    @DisplayName("every mode round-trips through its id")
    void idRoundTrip() {
        for (PortalRoomMode m : PortalRoomMode.values()) {
            assertSame(m, PortalRoomMode.parse(m.id()), m.id());
        }
    }

    @Test
    @DisplayName("ids are unique and lower-case — they are on-disk and command-line tokens")
    void idsAreUniqueTokens() {
        Set<String> seen = new HashSet<>();
        for (PortalRoomMode m : PortalRoomMode.values()) {
            assertTrue(seen.add(m.id()), "duplicate id " + m.id());
            assertEquals(m.id().toLowerCase(java.util.Locale.ROOT), m.id());
        }
    }

    @Test
    @DisplayName("parse is total: null, blank and nonsense all fall back to the default")
    void parseIsTotal() {
        assertSame(PortalRoomMode.DEFAULT, PortalRoomMode.parse(null));
        assertSame(PortalRoomMode.DEFAULT, PortalRoomMode.parse(""));
        assertSame(PortalRoomMode.DEFAULT, PortalRoomMode.parse("   "));
        // A hand-edited typo must stamp a normal sealed room, not fail the pair's stamp.
        assertSame(PortalRoomMode.DEFAULT, PortalRoomMode.parse("endles_open"));
    }

    @Test
    @DisplayName("parse tolerates case and surrounding whitespace")
    void parseNormalises() {
        assertSame(PortalRoomMode.ENDLESS_OPEN, PortalRoomMode.parse("  Endless_Open "));
        assertSame(PortalRoomMode.BEDROCK_LOCK, PortalRoomMode.parse("BEDROCK_LOCK"));
    }

    @Test
    @DisplayName("the default is the sealed mode — an unset variant behaves as it did before modes existed")
    void defaultIsBedrockLock() {
        assertSame(PortalRoomMode.BEDROCK_LOCK, PortalRoomMode.DEFAULT);
        assertFalse(PortalRoomMode.BEDROCK_LOCK.tiles());
    }

    @Test
    @DisplayName("both endless modes tile; only Endless Repetition carries the whole room")
    void tilingRules() {
        assertTrue(PortalRoomMode.ENDLESS_REPETITION.tiles());
        assertTrue(PortalRoomMode.ENDLESS_OPEN.tiles());
        assertTrue(PortalRoomMode.ENDLESS_REPETITION.tilesWholeRoom());
        assertFalse(PortalRoomMode.ENDLESS_OPEN.tilesWholeRoom());
    }

    @Test
    @DisplayName("Endless Open is the one mode that leaves outer faces open — that is what 'open' means")
    void onlyEndlessOpenLeavesFacesOpen() {
        assertFalse(PortalRoomMode.ENDLESS_OPEN.closesOuterFaces());
        assertTrue(PortalRoomMode.ENDLESS_REPETITION.closesOuterFaces());
        assertTrue(PortalRoomMode.BEDROCK_LOCK.closesOuterFaces());
    }

    @Test
    @DisplayName("next() cycles through every mode and returns to where it started")
    void nextCycles() {
        PortalRoomMode start = PortalRoomMode.DEFAULT;
        Set<PortalRoomMode> visited = new HashSet<>();
        PortalRoomMode m = start;
        for (int i = 0; i < PortalRoomMode.values().length; i++) {
            assertTrue(visited.add(m), "next() revisited " + m + " before covering every mode");
            assertNotEquals(m, m.next());
            m = m.next();
        }
        assertSame(start, m);
        assertEquals(PortalRoomMode.values().length, visited.size());
    }
}
