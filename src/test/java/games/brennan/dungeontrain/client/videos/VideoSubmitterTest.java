package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.ALREADY_LISTED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.ALREADY_PENDING;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.BAD_URL;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.FAILED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.QUEUED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoSubmitterTest {

    @Test
    void mapsTheRelayAnswers() {
        assertEquals(QUEUED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"queued\"}"));
        assertEquals(ALREADY_LISTED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"already_listed\"}"));
        assertEquals(ALREADY_PENDING, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"already_pending\"}"));
        assertEquals(BAD_URL, VideoSubmitter.interpret(400, "{\"error\":\"bad_url\"}"));
        assertEquals(RATE_LIMITED, VideoSubmitter.interpret(429, "{\"error\":\"rate_limited\"}"));
    }

    @Test
    void anythingElseIsAFailure() {
        assertEquals(FAILED, VideoSubmitter.interpret(400, "{\"error\":\"bad_json\"}"));
        assertEquals(FAILED, VideoSubmitter.interpret(500, ""));
        assertEquals(FAILED, VideoSubmitter.interpret(200, "not json"));
        assertEquals(FAILED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"something_new\"}"));
        assertEquals(FAILED, VideoSubmitter.interpret(200, "{\"ok\":true}"));
    }
}
