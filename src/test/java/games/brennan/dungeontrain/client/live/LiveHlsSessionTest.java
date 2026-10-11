package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveHlsSessionTest {

    @Test
    void aCleanExitAfterFramesIsTheEndOfTheStream() {
        assertTrue(LiveHlsSession.reachedEnd(0, 120, false), "ffmpeg played the playlist to its ENDLIST");
    }

    @Test
    void errorsAndEmptyRunsAreRetried() {
        assertFalse(LiveHlsSession.reachedEnd(8, 120, false), "a 404 or network drop exits non-zero");
        assertFalse(LiveHlsSession.reachedEnd(0, 0, false), "nothing decoded: try again");
    }

    @Test
    void aReplayLoopNeverEnds() {
        assertFalse(LiveHlsSession.reachedEnd(0, 120, true));
    }
}
