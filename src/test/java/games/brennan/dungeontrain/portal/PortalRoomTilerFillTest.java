package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The picker behind {@code PortalRoomTiler.faceFill} — what an endless room's boundary wall is
 * built out of when a cell's mirror cannot be copied.
 *
 * <p>Tested through the generic form rather than with block states, which is why it is generic:
 * the rule is "the material the face is mostly made of, deterministically", and that needs no
 * NeoForge bootstrap to state. Same reason {@link PortalRoomTiling} keeps its geometry pure.</p>
 */
class PortalRoomTilerFillTest {

    @Test
    @DisplayName("the wall is built out of whatever the face is mostly made of")
    void majorityWins() {
        assertEquals("bricks", PortalRoomTiler.mostCommon(
            List.of("bricks", "lantern", "bricks", "bricks", "lantern")));
    }

    @Test
    @DisplayName("a tie goes to whichever was seen first, so the choice does not follow hash order")
    void tieGoesToFirstSeen() {
        assertEquals("bricks", PortalRoomTiler.mostCommon(List.of("bricks", "planks")));
        assertEquals("planks", PortalRoomTiler.mostCommon(List.of("planks", "bricks")));
    }

    @Test
    @DisplayName("a face with no usable block yields nothing — the caller falls back to the shell")
    void emptyYieldsNothing() {
        assertNull(PortalRoomTiler.mostCommon(List.of()));
    }

    @Test
    @DisplayName("one usable block is enough")
    void singleValue() {
        assertEquals("bricks", PortalRoomTiler.mostCommon(List.of("bricks")));
    }
}
