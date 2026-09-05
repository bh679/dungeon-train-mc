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
 * <p>The property that matters most here is the <b>default</b>: {@link PortalRoomDoorWall#REPEATED}
 * since 2026-09-05, so every tag with no seventh segment — and every tag that names the setting
 * badly — tiles the room exactly as its author built it. Merged is the opt-in.</p>
 */
class PortalRoomDoorWallTest {

    @Test
    @DisplayName("An absent, blank or unreadable segment is Kept — the default — and Merged must be named")
    void parseIsTotalAndDefaultsToKept() {
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.DEFAULT);
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse(null));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse(""));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse("   "));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse("seeled"));
        assertEquals(PortalRoomDoorWall.REPEATED, PortalRoomDoorWall.parse("on"));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse("SEALED"));
        assertEquals(PortalRoomDoorWall.SEALED, PortalRoomDoorWall.parse(" sealed "));
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
            // boundary that may not have one. Pinned to SEALED by name: it is no longer the default,
            // and Endless Open's faces are only opened because its walls do not "repeat".
            assertFalse(settings.doorWallApplies(), mode.id() + " does not apply");
            assertEquals(PortalRoomDoorWall.SEALED, settings.effectiveDoorWall(), mode.id());
            assertFalse(settings.effectiveDoorWall().repeats(), mode.id() + " must not repeat");
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
