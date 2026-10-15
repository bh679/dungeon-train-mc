package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feedback Loop: a held TV switched on by the player who is live. */
final class LiveTvAdvancementsTest {

    @Test
    @DisplayName("only switching a held TV on while you are the streamer counts")
    void watchingOwnStream() {
        UUID me = UUID.randomUUID();
        assertTrue(LiveTvAdvancements.watchingOwnStream(true, me, me));
        assertTrue(LiveTvAdvancements.watchingOwnStream(true, UUID.fromString(me.toString()), me));
        assertFalse(LiveTvAdvancements.watchingOwnStream(false, me, me));
        assertFalse(LiveTvAdvancements.watchingOwnStream(true, UUID.randomUUID(), me));
        assertFalse(LiveTvAdvancements.watchingOwnStream(true, null, me));
    }
}
