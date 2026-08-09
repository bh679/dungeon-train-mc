package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Exits setting's own segment grammar.
 *
 * <p>Two things are worth pinning here. Parsing is <b>total</b> — the tag is free text on disk, and a
 * hand-edited typo has to stamp a room rather than fail a pair and leave a player walking into an
 * unbuilt corridor. And the spacing is <b>clamped, never rejected</b>: a number an author typed and
 * meant should land on the nearest legal one, visibly, rather than vanish.</p>
 */
class PortalRoomExitsTest {

    @Test
    @DisplayName("The bare kind means the default spacing, and writes back as the bare kind")
    void bareKindRoundTrips() {
        for (PortalRoomExits.Kind kind : PortalRoomExits.Kind.values()) {
            PortalRoomExits exits = new PortalRoomExits(kind, PortalRoomExits.DEFAULT_EVERY);
            assertEquals(kind.id(), exits.id(), kind.name());
            assertEquals(exits, PortalRoomExits.parse(exits.id()), kind.name());
        }
    }

    @Test
    @DisplayName("A non-default spacing is written after a colon, and read back off it")
    void spacingRoundTrips() {
        assertEquals("on:12", new PortalRoomExits(PortalRoomExits.Kind.ON, 12).id());
        assertEquals("random:3", new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 3).id());

        PortalRoomExits back = PortalRoomExits.parse("random:3");
        assertSame(PortalRoomExits.Kind.RANDOM, back.kind());
        assertEquals(3, back.every());
    }

    @Test
    @DisplayName("Off drops its spacing — a spacing for corridors nobody lays is not a setting")
    void offCarriesNoSpacing() {
        assertEquals("off", new PortalRoomExits(PortalRoomExits.Kind.OFF, 17).id());
        assertFalse(new PortalRoomExits(PortalRoomExits.Kind.OFF, 17).lays());
        assertTrue(PortalRoomExits.ON.lays());
        assertTrue(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 8).lays());
    }

    @Test
    @DisplayName("Parsing is total: a misspelt kind or an unreadable spacing still yields a setting")
    void parseIsTotal() {
        assertSame(PortalRoomExits.Kind.ON, PortalRoomExits.parse(null).kind());
        assertSame(PortalRoomExits.Kind.ON, PortalRoomExits.parse("").kind());
        assertSame(PortalRoomExits.Kind.ON, PortalRoomExits.parse("randm").kind());
        // A good kind with rubbish after the colon keeps the kind.
        assertSame(PortalRoomExits.Kind.RANDOM, PortalRoomExits.parse("random:lots").kind());
        assertEquals(PortalRoomExits.DEFAULT_EVERY, PortalRoomExits.parse("random:lots").every());
        assertEquals(PortalRoomExits.DEFAULT_EVERY, PortalRoomExits.parse("on:").every());
    }

    @Test
    @DisplayName("The spacing is clamped rather than refused, at both ends and through the parser")
    void spacingIsClamped() {
        assertEquals(PortalRoomExits.MIN_EVERY, new PortalRoomExits(PortalRoomExits.Kind.ON, 0).every());
        assertEquals(PortalRoomExits.MIN_EVERY, new PortalRoomExits(PortalRoomExits.Kind.ON, -4).every());
        assertEquals(PortalRoomExits.MAX_EVERY,
            new PortalRoomExits(PortalRoomExits.Kind.ON, 9999).every());
        assertEquals(PortalRoomExits.MIN_EVERY, PortalRoomExits.parse("on:1").every());
        assertEquals(PortalRoomExits.MAX_EVERY, PortalRoomExits.parse("on:9999").every());
        // One, not zero: at every=1 an ON lattice would put a corridor in every tile, and a corridor
        // is longer than a room — consecutive sets would overlap and the room would be corridor end
        // to end.
        assertTrue(PortalRoomExits.MIN_EVERY >= 2);
    }

    @Test
    @DisplayName("Cycling steps the kind and leaves the spacing where the author put it")
    void nextStepsTheKindOnly() {
        PortalRoomExits at12 = new PortalRoomExits(PortalRoomExits.Kind.ON, 12);
        assertSame(PortalRoomExits.Kind.RANDOM, at12.next().kind());
        assertEquals(12, at12.next().every());
        // …and wraps.
        assertSame(PortalRoomExits.Kind.ON,
            PortalRoomExits.Kind.OFF.next());
    }

    @Test
    @DisplayName("A quarter of the random draws are entries — the rest are the way onward")
    void entryShareIsAMinority() {
        assertEquals(25, PortalRoomExits.ENTRY_SHARE);
    }
}
