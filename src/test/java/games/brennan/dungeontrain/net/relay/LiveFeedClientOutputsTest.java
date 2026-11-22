package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The claim reply's {@code outputs} (relay stream-outputs.js): which re-sends are paused right now. */
final class LiveFeedClientOutputsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void anOlderRelaySaysNothingSoNothingIsPaused() {
        assertEquals(LiveFeedClient.Outputs.NONE_PAUSED, LiveFeedClient.parseOutputs(json("{\"ok\":true}")));
        assertEquals(LiveFeedClient.Outputs.NONE_PAUSED, LiveFeedClient.parseOutputs(null));
        assertEquals(LiveFeedClient.Outputs.NONE_PAUSED, LiveFeedClient.parseOutputs(json("{\"outputs\":\"paused\"}")), "malformed");
    }

    @Test
    void theNovemberPauseReadsAsPaused() {
        LiveFeedClient.Outputs o = LiveFeedClient.parseOutputs(json(
            "{\"outputs\":{\"twitch\":\"paused\",\"youtube\":\"paused\",\"kick\":\"paused\",\"facebook\":\"paused\",\"restream\":\"paused\"}}"));
        assertEquals(new LiveFeedClient.Outputs(true, true, true, true), o);
    }

    @Test
    void onAndLiveAndUnknownWordsAreNotPaused() {
        LiveFeedClient.Outputs o = LiveFeedClient.parseOutputs(json(
            "{\"outputs\":{\"twitch\":\"live\",\"youtube\":\"on\",\"kick\":\"someday\",\"restream\":true}}"));
        assertEquals(LiveFeedClient.Outputs.NONE_PAUSED, o);
    }

    @Test
    void aClaimCarriesItsOutputs() {
        LiveFeedClient.Result r = new LiveFeedClient.Result(200, json(
            "{\"ok\":true,\"session\":\"s\",\"token\":\"t\",\"playlistUrl\":\"https://x/live.m3u8\",\"outputs\":{\"twitch\":\"on\",\"youtube\":\"paused\",\"restream\":\"paused\"}}"),
            null);
        LiveFeedClient.Claim c = LiveFeedClient.parseClaim(r);
        assertEquals(new LiveFeedClient.Outputs(false, true, false, true), c.outputs());
        assertEquals(LiveFeedClient.Outputs.NONE_PAUSED, new LiveFeedClient.Claim("s", "t", "u", null).outputs());
    }
}
