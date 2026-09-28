package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/** The author's veto on a locked room drifting through the relay. */
class PortalRoomDriftTest {

    @Test
    @DisplayName("Parsing is total — null, blank and nonsense all read as On")
    void parseIsTotal() {
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.parse(null));
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.parse("  "));
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.parse("sometimes"));
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.parse(" On "));
        assertSame(PortalRoomDrift.OFF, PortalRoomDrift.parse("off"));
    }

    @Test
    @DisplayName("The default is On: a locked room drifts unless its author says otherwise")
    void defaultIsOn() {
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.DEFAULT);
    }

    @Test
    @DisplayName("The cycle button steps On → Off → On")
    void nextWraps() {
        assertSame(PortalRoomDrift.OFF, PortalRoomDrift.ON.next());
        assertSame(PortalRoomDrift.ON, PortalRoomDrift.OFF.next());
    }
}
