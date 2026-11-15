package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PlayerDropMarker#hasPlayerDropTag}: the tag the runway sweep
 * reads to spare items a player dropped or earned.
 */
final class PlayerDropMarkerTest {

    @Test
    @DisplayName("an item carrying the player-drop tag is spared")
    void taggedItem_isSpared() {
        assertTrue(PlayerDropMarker.hasPlayerDropTag(
            Set.of("other_tag", PlayerDropMarker.PLAYER_DROP_TAG)));
    }

    @Test
    @DisplayName("an untagged item is not spared")
    void untaggedItem_isNotSpared() {
        assertFalse(PlayerDropMarker.hasPlayerDropTag(Set.of()));
        assertFalse(PlayerDropMarker.hasPlayerDropTag(Set.of("dungeontrain_player_drop_x")));
    }
}
