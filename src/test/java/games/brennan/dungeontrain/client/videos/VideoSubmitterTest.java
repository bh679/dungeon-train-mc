package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.ALREADY_LISTED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.ALREADY_PENDING;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.BAD_URL;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.FAILED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.NOT_LIVE;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.PUBLISHED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.QUEUED;
import static games.brennan.dungeontrain.client.videos.VideoSubmitter.Result.RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoSubmitterTest {

    @Test
    void mapsTheRelayAnswers() {
        assertEquals(QUEUED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"queued\"}"));
        assertEquals(ALREADY_LISTED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"already_listed\"}"));
        assertEquals(ALREADY_PENDING, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"already_pending\"}"));
        assertEquals(PUBLISHED, VideoSubmitter.interpret(200, "{\"ok\":true,\"status\":\"published\",\"id\":7}"));
        assertEquals(NOT_LIVE, VideoSubmitter.interpret(400, "{\"error\":\"not_live\"}"));
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

    @Test
    void twitchChannelUrlIsABareLoginOnly() {
        assertTrue(VideoSubmitter.isTwitchChannelUrl("https://www.twitch.tv/DrOneLeg"));
        assertTrue(VideoSubmitter.isTwitchChannelUrl("https://twitch.tv/droneleg/"));
        assertTrue(VideoSubmitter.isTwitchChannelUrl("https://m.twitch.tv/droneleg?x=1"));
        assertFalse(VideoSubmitter.isTwitchChannelUrl("https://www.twitch.tv/videos/2864605897"), "a VOD");
        assertFalse(VideoSubmitter.isTwitchChannelUrl("https://clips.twitch.tv/SomeSlug"), "a clip");
        assertFalse(VideoSubmitter.isTwitchChannelUrl("https://www.twitch.tv/droneleg/clip/x"), "a clip");
        assertFalse(VideoSubmitter.isTwitchChannelUrl("https://www.twitch.tv/directory"), "a site route");
        assertFalse(VideoSubmitter.isTwitchChannelUrl("https://youtu.be/abc"), "another platform");
        assertFalse(VideoSubmitter.isTwitchChannelUrl("twitch.tv/droneleg"), "no scheme");
    }
}
