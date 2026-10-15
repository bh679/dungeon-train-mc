package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveGiftClientTest {

    @Test
    @DisplayName("the gift prompt shows at most once every 12 minutes")
    void throttle() {
        assertEquals(12L * 60L * 1000L, LiveGiftClient.PROMPT_GAP_MS);
        assertTrue(LiveGiftClient.shouldPrompt(-1, 1_000), "never prompted");
        long last = 10_000_000L;
        assertFalse(LiveGiftClient.shouldPrompt(last, last));
        assertFalse(LiveGiftClient.shouldPrompt(last, last + LiveGiftClient.PROMPT_GAP_MS - 1));
        assertTrue(LiveGiftClient.shouldPrompt(last, last + LiveGiftClient.PROMPT_GAP_MS));
        assertTrue(LiveGiftClient.shouldPrompt(last, last - 1), "a clock that ran backwards does not lock it out");
    }
}
