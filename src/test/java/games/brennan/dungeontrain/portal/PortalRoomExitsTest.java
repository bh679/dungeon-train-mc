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

    // ---- the sealed exit ----

    @Test
    @DisplayName("The seal is written as a third part, and read back off it")
    void sealRoundTrips() {
        PortalRoomExits sealed = new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12, 7);
        assertEquals("random:12:7", sealed.id());

        PortalRoomExits back = PortalRoomExits.parse("random:12:7");
        assertSame(PortalRoomExits.Kind.RANDOM, back.kind());
        assertEquals(12, back.every());
        assertEquals(7, back.sealChance());
    }

    @Test
    @DisplayName("A default spacing is written as a placeholder so the seal has somewhere to sit")
    void sealForcesTheSpacingToBeWritten() {
        // The parts are positional, so the seal cannot be written without a spacing in front of it.
        PortalRoomExits sealed = new PortalRoomExits(
            PortalRoomExits.Kind.RANDOM, PortalRoomExits.DEFAULT_EVERY, 3);
        assertEquals("random:8:3", sealed.id());
        assertEquals(3, PortalRoomExits.parse(sealed.id()).sealChance());
    }

    @Test
    @DisplayName("Every tag written before the seal existed still reads, as a room that never seals")
    void shorterSegmentsStillParse() {
        assertEquals(PortalRoomExits.SEAL_NEVER, PortalRoomExits.parse("random").sealChance());
        assertEquals(PortalRoomExits.SEAL_NEVER, PortalRoomExits.parse("random:12").sealChance());
        assertEquals(PortalRoomExits.SEAL_NEVER, PortalRoomExits.parse("on").sealChance());
        // …and the two-arg form the record had before it grew the third component.
        assertEquals(PortalRoomExits.SEAL_NEVER,
            new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12).sealChance());
    }

    @Test
    @DisplayName("The seal is dropped where it means nothing — under On, under Off, and at zero")
    void sealOmittedWhereItMeansNothing() {
        assertEquals("random:12", new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12, 0).id());
        // On is a lattice a player can work out in advance, so it never seals.
        assertEquals("on:12", new PortalRoomExits(PortalRoomExits.Kind.ON, 12, 9).id());
        // Off has no other way onward to send them to.
        assertEquals("off", new PortalRoomExits(PortalRoomExits.Kind.OFF, 12, 9).id());
        assertEquals(0, new PortalRoomExits(PortalRoomExits.Kind.ON, 12, 9).effectiveSealChance());
        assertFalse(new PortalRoomExits(PortalRoomExits.Kind.ON, 12, 9).sealsApply());
        assertTrue(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12, 9).sealsApply());
    }

    @Test
    @DisplayName("The seal is clamped to the scale, and an unreadable one keeps the rest of the tag")
    void sealIsClampedAndTotal() {
        assertEquals(PortalRoomExits.SEAL_NEVER,
            new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 8, -4).sealChance());
        assertEquals(PortalRoomExits.SEAL_ALWAYS,
            new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 8, 99).sealChance());
        assertEquals(PortalRoomExits.SEAL_ALWAYS, PortalRoomExits.parse("random:8:99").sealChance());

        PortalRoomExits typo = PortalRoomExits.parse("random:12:lots");
        assertSame(PortalRoomExits.Kind.RANDOM, typo.kind());
        assertEquals(12, typo.every());
        assertEquals(PortalRoomExits.SEAL_NEVER, typo.sealChance());
    }

    @Test
    @DisplayName("Cycling and re-spacing carry the seal along")
    void withersCarryTheSeal() {
        PortalRoomExits sealed = new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12, 7);
        assertEquals(7, sealed.next().sealChance());
        assertEquals(7, sealed.withEvery(3).sealChance());
        assertEquals(7, sealed.withKind(PortalRoomExits.Kind.ON).sealChance());
        assertEquals(2, sealed.withSealChance(2).sealChance());
        assertEquals(12, sealed.withSealChance(2).every());
    }
}
