package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Door Wall setting: whether the copies standing against the portal carriages carry their own end
 * wall through the corridor mouth's plane.
 *
 * <p>The property that matters most here is the <b>default</b>. Turning this on changes what is
 * standing in a world that already exists, so every tag ever written — and every tag that names the
 * setting badly — has to come back as {@link PortalRoomDoorWall#SEALED}.</p>
 */
class PortalRoomDoorWallTest {

    @Test
    @DisplayName("An absent, blank or unreadable segment is Sealed — never the new behaviour")
    void parseIsTotalAndDefaultsToSealed() {
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse(null));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse(""));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse("   "));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse("repeeted"));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse("on"));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse("REPEATED"));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse(" repeated "));
    }

    @Test
    @DisplayName("repeats() is the one question the block writes ask")
    void repeatsNamesTheBehaviour() {
        assertFalse(PortalRoomDoorWall.SEALED.repeats());
        assertTrue(PortalRoomDoorWall.REPEATED.repeats());
    }

    @Test
    @DisplayName("The button cycles both ways round")
    void nextWraps() {
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.SEALED.next());
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.REPEATED.next());
    }

    @Test
    @DisplayName("Only Endless Repetition can use it — every other mode reads back Sealed")
    void appliesOnlyWhereATileHasAWall() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            PortalRoomSettings settings = new PortalRoomSettings(mode, PortalRoomCopies.DYNAMIC,
                PortalRoomContents.DEFAULT, null, PortalRoomBooks.DEFAULT, PortalRoomSky.NONE,
                PortalRoomDoorWall.REPEATED);
            if (mode == PortalRoomMode.ENDLESS_REPETITION) {
                assertTrue(settings.doorWallApplies(), mode.id() + " applies");
                assertEquals(PortalRoomDoorWall.REPEATED, settings.effectiveDoorWall(), mode.id());
                continue;
            }
            // Endless Open writes no walls, and the two sealed modes append no tiles at all — a copy
            // handed that plane there has nothing to fill it with, which is a hole in the one
            // boundary that may not have one.
            assertFalse(settings.doorWallApplies(), mode.id() + " does not apply");
            assertEquals(PortalRoomDoorWall.SEALED, settings.effectiveDoorWall(), mode.id());
        }
    }

    @Test
    @DisplayName("The value survives a change of Sky, Copies or Contents")
    void withersCarryItThrough() {
        PortalRoomSettings repeated = new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION,
            PortalRoomCopies.EXACT, PortalRoomContents.DEFAULT, null, PortalRoomBooks.DEFAULT,
            PortalRoomSky.NONE, PortalRoomDoorWall.REPEATED);
        assertEquals(PortalRoomDoorWall.REPEATED, repeated.withSky(PortalRoomSky.DAY).doorWall());
        assertEquals(PortalRoomDoorWall.REPEATED, repeated.nextCopies().doorWall());
        assertEquals(PortalRoomDoorWall.REPEATED,
            repeated.withContents(PortalRoomContents.DEFAULT).doorWall());
    }
}
