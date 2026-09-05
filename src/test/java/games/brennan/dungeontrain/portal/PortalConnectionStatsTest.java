package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-life tally of dimensional carriages that connected and ones that did not. */
final class PortalConnectionStatsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @BeforeEach
    void reset() {
        PortalConnectionStats.clear();
    }

    @Test
    @DisplayName("a life that met no portal is empty and reports nothing")
    void emptyLife() {
        assertTrue(PortalConnectionStats.peek(ALICE).isEmpty());
        assertTrue(PortalConnectionStats.takeForLife(ALICE).isEmpty());
    }

    @Test
    @DisplayName("connections count every crossing; breakages count once per corridor")
    void countsAndDedup() {
        PortalConnectionStats.noteConnected(ALICE);
        PortalConnectionStats.noteConnected(ALICE);
        assertTrue(PortalConnectionStats.noteBroken(ALICE, 30, "TWIN_NOT_LOADED"));
        assertFalse(PortalConnectionStats.noteBroken(ALICE, 30, "TWIN_NOT_LOADED"),
            "the same door twice is one breakage");
        assertFalse(PortalConnectionStats.noteBroken(ALICE, 30, "NO_LANDING"),
            "even under a different reason later");
        assertTrue(PortalConnectionStats.noteBroken(ALICE, 45, "SEVERED"));

        PortalConnectionStats.Life life = PortalConnectionStats.peek(ALICE);
        assertEquals(2, life.connected());
        assertEquals(2, life.broken());
        assertEquals(Map.of("TWIN_NOT_LOADED", 1, "SEVERED", 1), life.reasons());
        assertFalse(life.isEmpty());
    }

    @Test
    @DisplayName("takeForLife hands the tally over and starts the next life from nothing")
    void takeClears() {
        PortalConnectionStats.noteConnected(ALICE);
        PortalConnectionStats.noteBroken(ALICE, 30, "NO_TWIN_STRUCTURE");
        PortalConnectionStats.Life life = PortalConnectionStats.takeForLife(ALICE);
        assertEquals(1, life.connected());
        assertEquals(1, life.broken());
        assertTrue(PortalConnectionStats.peek(ALICE).isEmpty());
        // The same door breaks again in the next life: it is a new life's breakage.
        assertTrue(PortalConnectionStats.noteBroken(ALICE, 30, "NO_TWIN_STRUCTURE"));
    }

    @Test
    @DisplayName("players are independent, and forget drops one without touching another")
    void playersIndependent() {
        PortalConnectionStats.noteConnected(ALICE);
        PortalConnectionStats.noteBroken(BOB, 30, "SEVERED");
        PortalConnectionStats.forget(ALICE);
        assertTrue(PortalConnectionStats.peek(ALICE).isEmpty());
        assertEquals(1, PortalConnectionStats.peek(BOB).broken());
    }

    @Test
    @DisplayName("a null reason is tallied as UNKNOWN rather than dropped")
    void nullReason() {
        PortalConnectionStats.noteBroken(ALICE, 30, null);
        assertEquals(Map.of("UNKNOWN", 1), PortalConnectionStats.peek(ALICE).reasons());
    }
}
