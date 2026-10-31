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
}
