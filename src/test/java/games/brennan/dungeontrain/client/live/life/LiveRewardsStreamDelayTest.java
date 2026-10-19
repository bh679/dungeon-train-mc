package games.brennan.dungeontrain.client.live.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The relay's stated stream delay: what the rewards screens print for Quicker Streams. */
class LiveRewardsStreamDelayTest {

    @Test
    void readsTheRelaysNumbers() {
        assertEquals(new LiveRewards.StreamDelay(30, 6),
                LiveRewards.StreamDelay.parseBody("{\"ok\":true,\"streamDelay\":{\"normal\":30,\"quick\":6}}"));
        assertEquals(new LiveRewards.StreamDelay(28, 4),
                LiveRewards.StreamDelay.parseBody("{\"streamDelay\":{\"normal\":28,\"quick\":4}}"));
    }

    @Test
    void anOlderRelayOrABadBlockIsIgnored() {
        assertNull(LiveRewards.StreamDelay.parseBody("{\"ok\":true,\"rewards\":[]}"));
        assertNull(LiveRewards.StreamDelay.parseBody("{\"streamDelay\":{\"normal\":5,\"quick\":30}}"));
        assertNull(LiveRewards.StreamDelay.parseBody("{\"streamDelay\":{\"normal\":0,\"quick\":0}}"));
        assertNull(LiveRewards.StreamDelay.parseBody("not json"));
    }

    @Test
    void theDefaultMatchesTheRelaysDefault() {
        assertEquals(new LiveRewards.StreamDelay(30, 6), LiveRewards.StreamDelay.DEFAULT_DELAY);
    }

    @Test
    void openRewardsAreReadFromTheSummaryAndNoneWithoutAList() {
        assertEquals(java.util.Set.of("quicker_streams"),
                LiveRewards.parseOpen("{\"rewardsOpen\":[\"quicker_streams\"]}"));
        assertEquals(java.util.Set.of(), LiveRewards.parseOpen("{\"ok\":true,\"rewards\":[]}"));
        assertEquals(java.util.Set.of(), LiveRewards.parseOpen("{\"rewardsOpen\":\"all\"}"));
        assertEquals(java.util.Set.of(), LiveRewards.parseOpen("not json"));
    }
}
