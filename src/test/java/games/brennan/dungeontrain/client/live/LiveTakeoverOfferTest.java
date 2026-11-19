package games.brennan.dungeontrain.client.live;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.ChatClickValues;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Takeover;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LiveTakeoverOffer.eligible;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aRemovedLiveEyeIsNotCalledADeath() {
        assertEquals("chat.dungeontrain.live.takeover.offer",
            LiveTakeoverOffer.offerKey(new Takeover("ab12cd34", "A", 1000)));
        assertEquals("chat.dungeontrain.live.takeover.offer.removed",
            LiveTakeoverOffer.offerKey(new Takeover("ab12cd34", "A", 1000, true)));
    }

    @Test
    void noTicketWithoutAWin() {
        assertNull(LiveTakeoverOffer.takeTicket(System.currentTimeMillis()));
    }

    // ---- the chat button's token -----------------------------------------------------------------

    @Test
    void theChatButtonOnlyTakesTheOfferWithItsPrintedToken() {
        AtomicLong now = new AtomicLong(1_000_000);
        ChatClickTokens.useClock(now::get);
        try {
            var t = new Takeover("ab12cd34", "A", 90_000);
            List<String> parts = ChatClickValues.of(LiveTakeoverOffer.promptLine(t)).get(0);
            assertEquals(List.of(LiveTakeoverOffer.CLICK_ACTION, "ab12cd34"), parts.subList(0, 2));
            assertEquals(3, parts.size(), "id and token");
            List<String> args = parts.subList(1, 3);
            now.addAndGet(ChatClickTokens.MIN_AGE_MS);

            assertFalse(LiveTakeoverOffer.clickAllowed(List.of("ab12cd34"), "ab12cd34"), "the public id alone");
            assertFalse(LiveTakeoverOffer.clickAllowed(args, "ffff0000"), "not the open offer");
            assertFalse(LiveTakeoverOffer.clickAllowed(args, null), "no open offer");
            assertTrue(LiveTakeoverOffer.clickAllowed(args, "ab12cd34"));
            assertFalse(LiveTakeoverOffer.clickAllowed(args, "ab12cd34"), "used up");
        } finally {
            ChatClickTokens.useClock(null);
        }
    }

    @Test
    void aForgedTokenIsRefused() {
        AtomicLong now = new AtomicLong(1_000_000);
        ChatClickTokens.useClock(now::get);
        try {
            LiveTakeoverOffer.promptLine(new Takeover("ab12cd34", "A", 90_000));
            now.addAndGet(ChatClickTokens.MIN_AGE_MS);
            assertFalse(LiveTakeoverOffer.clickAllowed(List.of("ab12cd34", "forged"), "ab12cd34"));
        } finally {
            ChatClickTokens.useClock(null);
        }
    }
}
