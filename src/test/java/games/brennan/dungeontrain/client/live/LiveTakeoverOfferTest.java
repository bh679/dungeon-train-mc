package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LiveTakeoverOffer.eligible;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is offered a dead streamer's stream. */
class LiveTakeoverOfferTest {

    @Test
    void aTvWearerWhoCouldStreamIsOffered() {
        assertTrue(eligible(true, false, true, true, true));
    }

    @Test
    void everyBlockerRulesThemOut() {
        assertFalse(eligible(false, false, true, true, true), "no TV on their head");
        assertFalse(eligible(true, true, true, true, true), "Free Play never goes live");
        assertFalse(eligible(true, false, false, true, true), "streaming off or no internet consent");
        assertFalse(eligible(true, false, true, false, true), "no ffmpeg");
        assertFalse(eligible(true, false, true, true, false), "already streaming");
    }

    @Test
    void noTicketWithoutAWin() {
        assertNull(LiveTakeoverOffer.takeTicket(System.currentTimeMillis()));
    }
}
