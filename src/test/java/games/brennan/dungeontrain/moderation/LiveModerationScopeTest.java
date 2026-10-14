package games.brennan.dungeontrain.moderation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The filter is on only while someone is live — or a dev forces it. */
final class LiveModerationScopeTest {

    @AfterEach
    void reset() {
        LiveModerationScope.devForce = false;
    }

    @Test
    @DisplayName("server: anyone live on this server or the relay")
    void server() {
        assertFalse(LiveModerationScope.serverActive(false));
        assertTrue(LiveModerationScope.serverActive(true));
    }

    @Test
    @DisplayName("client: streaming, or in the stream's chat room")
    void client() {
        assertFalse(LiveModerationScope.clientActive(false, false));
        assertTrue(LiveModerationScope.clientActive(true, false));
        assertTrue(LiveModerationScope.clientActive(false, true));
    }

    @Test
    @DisplayName("a book page is on stream only when its writer is the streamer")
    void book() {
        assertFalse(LiveModerationScope.bookFieldActive(false));
        assertTrue(LiveModerationScope.bookFieldActive(true));
    }

    @Test
    @DisplayName("the dev override switches every scope on")
    void devForce() {
        LiveModerationScope.devForce = true;
        assertTrue(LiveModerationScope.serverActive(false));
        assertTrue(LiveModerationScope.clientActive(false, false));
        assertTrue(LiveModerationScope.bookFieldActive(false));
    }
}
