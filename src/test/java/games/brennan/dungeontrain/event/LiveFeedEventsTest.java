package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The camcorder is consumed only when the feed is taken from the wearer. */
final class LiveFeedEventsTest {

    @Test
    @DisplayName("taking it off, replacement, death and cut-off burn the camcorder; a failed start or a logout keeps it")
    void whichExitsBurn() {
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.REMOVED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.REPLACED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.DIED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.CUT_OFF));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.DISABLED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.FREE_PLAY));
        assertFalse(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.LEFT));
        assertFalse(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.FAILED));
    }

    @Test
    @DisplayName("a second START for the player already live (login, then the first-tick equip event) is ignored")
    void alreadyStreamingSkipsTheSecondStart() {
        UUID me = UUID.randomUUID();
        assertTrue(LiveFeedEvents.alreadyStreaming(me, me));
        assertTrue(LiveFeedEvents.alreadyStreaming(me, UUID.fromString(me.toString())));
        assertFalse(LiveFeedEvents.alreadyStreaming(me, UUID.randomUUID()));
        assertFalse(LiveFeedEvents.alreadyStreaming(me, null));
    }

    @Test
    @DisplayName("Free Play can't go live on a live build; dev builds stay exempt for testing")
    void freePlayBlocksStreaming() {
        assertTrue(LiveFeedEvents.streamBlocked(false, true));
        assertFalse(LiveFeedEvents.streamBlocked(false, false));
        assertFalse(LiveFeedEvents.streamBlocked(true, true));
        assertFalse(LiveFeedEvents.streamBlocked(true, false));
    }

    @Test
    @DisplayName("the burn line says why: taken off, moved to the next streamer, or the generic flare")
    void burnLineSaysWhy() {
        assertEquals("chat.dungeontrain.live.burns_removed", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.REMOVED));
        assertEquals("chat.dungeontrain.live.burns_next_streamer", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.REPLACED));
        assertEquals("chat.dungeontrain.live.burns_next_streamer", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.CUT_OFF));
        assertEquals("chat.dungeontrain.live.headpiece_burns", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.DIED));
        assertEquals("chat.dungeontrain.live.headpiece_burns", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.FREE_PLAY));
        assertEquals("chat.dungeontrain.live.headpiece_burns", LiveFeedEvents.burnLineKey(LiveFeedEvents.Exit.DISABLED));
    }
}
