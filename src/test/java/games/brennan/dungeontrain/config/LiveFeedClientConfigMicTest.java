package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LiveFeedClientConfigMicTest {

    @Test
    void theMicrophoneOptionIsOffByDefault() {
        assertFalse(LiveFeedClientConfig.MIC_SUPPORT_DEFAULT, "a streamer opts in before their mic can join a stream");
        assertFalse(LiveFeedClientConfig.micSupport(), "off before the config loads too, so no stream ever asks for a mic unasked");
        assertEquals(Boolean.FALSE, LiveFeedClientConfig.MIC_SUPPORT.getDefault());
    }

    @Test
    void theBalanceStartsWithTheVoiceOverTheGameAndAlwaysOn() {
        assertEquals(75, LiveFeedClientConfig.MIC_MIX_DEFAULT, "voice at full, game at half");
        assertEquals(75, LiveFeedClientConfig.MIC_MIX_PERCENT.getDefault());
        assertEquals(75, LiveFeedClientConfig.micMixPercent(), "the same before the config loads");
        assertFalse(LiveFeedClientConfig.micPushToTalk());
    }
}
