package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The author-lock setting itself: its tokens, its totality, and the cycle the editor button walks. */
class PortalRoomBooksTest {

    @Test
    @DisplayName("Ids round-trip and are unique — the stored tag is these strings")
    void idsRoundTrip() {
        Set<String> seen = new HashSet<>();
        for (PortalRoomBooks b : PortalRoomBooks.values()) {
            assertTrue(seen.add(b.id()), "duplicate id " + b.id());
            assertSame(b, PortalRoomBooks.parse(b.id()));
            assertSame(b, PortalRoomBooks.parse(b.id().toUpperCase(java.util.Locale.ROOT)));
            assertSame(b, PortalRoomBooks.parse("  " + b.id() + " "));
        }
    }

    @Test
    @DisplayName("Parsing is total — a hand-edited typo leaves the room unlocked, never fails a stamp")
    void parseIsTotal() {
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse(null));
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse(""));
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse("   "));
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse("signatre"));
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse("random_player"));
    }

    @Test
    @DisplayName("Off is the default and the only value that does not lock")
    void offIsTheOnlyUnlockedValue() {
        assertSame(PortalRoomBooks.OFF, PortalRoomBooks.DEFAULT);
        assertFalse(PortalRoomBooks.OFF.locks());
        for (PortalRoomBooks b : PortalRoomBooks.values()) {
            if (b != PortalRoomBooks.OFF) assertTrue(b.locks(), b.id() + " should lock");
        }
    }

    @Test
    @DisplayName("Only Current Player starts from the holder")
    void onlySelfStartsFromTheHolder() {
        assertTrue(PortalRoomBooks.SELF.startsFromSelf());
        assertFalse(PortalRoomBooks.PLAYER.startsFromSelf());
        assertFalse(PortalRoomBooks.SIGNATURE.startsFromSelf());
        assertFalse(PortalRoomBooks.OFF.startsFromSelf());
    }

    @Test
    @DisplayName("Each value asks the relay directory for the kind it means")
    void directoryKindMatchesTheSetting() {
        assertEquals("self", PortalRoomBooks.SELF.directoryKind());
        assertEquals("player", PortalRoomBooks.PLAYER.directoryKind());
        assertEquals("signature", PortalRoomBooks.SIGNATURE.directoryKind());
        // Off never asks — callers gate on locks() — but it must still name a real kind rather than
        // return null into a URL builder.
        assertEquals("player", PortalRoomBooks.OFF.directoryKind());
    }

    @Test
    @DisplayName("The editor button walks every value and comes back round")
    void nextCyclesThroughEverything() {
        Set<PortalRoomBooks> walked = new HashSet<>();
        PortalRoomBooks at = PortalRoomBooks.DEFAULT;
        for (int i = 0; i < PortalRoomBooks.values().length; i++) {
            assertTrue(walked.add(at), "cycle revisited " + at + " early");
            at = at.next();
        }
        assertEquals(PortalRoomBooks.values().length, walked.size());
        assertSame(PortalRoomBooks.DEFAULT, at, "the cycle must return to where it started");
    }

    @Test
    @DisplayName("Every value has a label for the editor row")
    void everyValueHasALabel() {
        for (PortalRoomBooks b : PortalRoomBooks.values()) {
            assertFalse(b.displayName().isBlank(), b.id() + " has no label");
        }
    }
}
