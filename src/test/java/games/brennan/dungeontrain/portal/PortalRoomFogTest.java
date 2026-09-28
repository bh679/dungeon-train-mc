package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The author's override of what the walls mode decides about fog. */
class PortalRoomFogTest {

    @Test
    @DisplayName("Auto answers exactly what the walls mode answers, for every mode")
    void autoFollowsTheMode() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            assertEquals(mode.fogs(), PortalRoomFog.AUTO.fogs(mode), mode.name());
        }
    }

    @Test
    @DisplayName("On and Off answer for themselves, whatever the walls say")
    void onAndOffOverrideTheMode() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            assertTrue(PortalRoomFog.ON.fogs(mode), mode.name());
            assertFalse(PortalRoomFog.OFF.fogs(mode), mode.name());
        }
    }

    @Test
    @DisplayName("Parsing is total — null, blank and nonsense all read as Auto")
    void parseIsTotal() {
        assertSame(PortalRoomFog.AUTO, PortalRoomFog.parse(null));
        assertSame(PortalRoomFog.AUTO, PortalRoomFog.parse("  "));
        assertSame(PortalRoomFog.AUTO, PortalRoomFog.parse("smog"));
        assertSame(PortalRoomFog.ON, PortalRoomFog.parse(" On "));
        assertSame(PortalRoomFog.OFF, PortalRoomFog.parse("off"));
    }

    @Test
    @DisplayName("The cycle button steps Auto → On → Off → Auto")
    void nextWraps() {
        assertSame(PortalRoomFog.ON, PortalRoomFog.AUTO.next());
        assertSame(PortalRoomFog.OFF, PortalRoomFog.ON.next());
        assertSame(PortalRoomFog.AUTO, PortalRoomFog.OFF.next());
    }
}
