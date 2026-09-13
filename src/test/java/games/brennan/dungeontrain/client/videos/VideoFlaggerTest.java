package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoFlaggerTest {

    @Test
    void mapsTheRelayAnswers() {
        VideoFlagger.Outcome o = VideoFlagger.interpret(200, "{\"ok\":true,\"status\":\"recorded\",\"hidden\":false,\"kidsHidden\":true}");
        assertTrue(o.recorded());
        assertFalse(o.hidden());
        assertTrue(o.kidsHidden());
        assertFalse(o.rateLimited());
        assertTrue(VideoFlagger.interpret(200, "{\"status\":\"recorded\",\"hidden\":true}").hidden());
        assertTrue(VideoFlagger.interpret(429, "{\"error\":\"rate_limited\"}").rateLimited());
    }

    @Test
    void anythingElseIsAFailure() {
        for (VideoFlagger.Outcome o : new VideoFlagger.Outcome[] {
                VideoFlagger.interpret(404, "{\"error\":\"not_found\"}"),
                VideoFlagger.interpret(400, "{\"error\":\"bad_reason\"}"),
                VideoFlagger.interpret(500, ""),
                VideoFlagger.interpret(200, "nope"),
                VideoFlagger.interpret(200, "{\"ok\":true}"),
        }) {
            assertEquals(VideoFlagger.Outcome.FAILED, o);
        }
    }

    @Test
    void reasonsMatchTheRelayWireValues() {
        assertEquals("not_dt", VideoFlagger.Reason.NOT_DT.wire());
        assertEquals("nsfk", VideoFlagger.Reason.NSFK.wire());
        assertEquals("custom", VideoFlagger.Reason.CUSTOM.wire());
        assertEquals("not_dt", VideoFlagger.Reason.NOT_DT.key());
    }
}
