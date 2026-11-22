package games.brennan.dungeontrain.client.live.life;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The summary's {@code rewardPrices} quotes: raised amounts parse, anything unusable falls back to the lang key. */
class LiveRewardsQuoteTest {

    @Test
    void parsesRaisedAndPlainQuotes() {
        Map<String, LiveRewards.Quote> q = LiveRewards.parseQuotes("""
                {"rewardPrices": {
                  "two_streams": {"aud": 140, "base": 100, "raisedBy": [{"id": "quicker_streams", "untilMs": 1}]},
                  "quicker_streams": {"aud": 94, "base": 94, "raisedBy": []}
                }}""");
        assertEquals(new LiveRewards.Quote(140, 100, List.of("quicker_streams")), q.get("two_streams"));
        assertTrue(q.get("two_streams").raised());
        assertFalse(q.get("quicker_streams").raised());
    }

    @Test
    void anOlderRelayOrJunkGivesNoQuotes() {
        assertTrue(LiveRewards.parseQuotes("{\"rewardsOpen\": []}").isEmpty());
        assertTrue(LiveRewards.parseQuotes("not json").isEmpty());
        // The web support page's shape (a bare number) is not a quote.
        assertTrue(LiveRewards.parseQuotes("{\"rewardPrices\": {\"two_streams\": 140}}").isEmpty());
        // Below its base or zero: skipped, never guessed.
        assertTrue(LiveRewards.parseQuotes("{\"rewardPrices\": {\"two_streams\": {\"aud\": 50, \"base\": 100}}}").isEmpty());
        assertTrue(LiveRewards.parseQuotes("{\"rewardPrices\": {\"two_streams\": {\"aud\": 0, \"base\": 0}}}").isEmpty());
    }

    @Test
    void readsTheDevShareWhenTheRelaySendsOne() {
        Map<String, LiveRewards.Quote> q = LiveRewards.parseQuotes("""
                {"rewardPrices": {
                  "hd_streams": {"aud": 90, "base": 90, "raisedBy": [], "devAud": 69},
                  "quicker_streams": {"aud": 94, "base": 94, "raisedBy": [], "devAud": 0},
                  "two_streams": {"aud": 100, "base": 100, "raisedBy": []},
                  "voice_on_stream": {"aud": 160, "base": 160, "raisedBy": [], "devAud": -4},
                  "replays_kept": {"aud": 80, "base": 80, "raisedBy": [], "devAud": 500},
                  "live_translation": {"aud": 90, "base": 90, "raisedBy": [], "devAud": "lots"}
                }}""");
        assertEquals(69, q.get("hd_streams").devAud());
        assertTrue(q.get("quicker_streams").hasDevShare(), "0 is a real answer, shown as $0");
        assertEquals(0, q.get("quicker_streams").devAud());
        // An older relay, a negative, a share above the gift or junk: no line, the quote itself still stands.
        for (String id : List.of("two_streams", "voice_on_stream", "replays_kept", "live_translation")) {
            assertFalse(q.get(id).hasDevShare(), id);
            assertEquals(LiveRewards.NO_DEV_SHARE, q.get(id).devAud(), id);
        }
    }
}
