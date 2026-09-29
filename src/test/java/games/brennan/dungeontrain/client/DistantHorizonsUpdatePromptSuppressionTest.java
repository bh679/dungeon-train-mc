package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.DistantHorizonsUpdatePromptSuppression.Decision.KEEP_CURRENT_SCREEN;
import static games.brennan.dungeontrain.client.DistantHorizonsUpdatePromptSuppression.Decision.OPEN_TITLE_SCREEN;
import static games.brennan.dungeontrain.client.DistantHorizonsUpdatePromptSuppression.Decision.RUN_INITIAL_SCREENS;
import static games.brennan.dungeontrain.client.DistantHorizonsUpdatePromptSuppression.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for what replaces Distant Horizons' update prompt once it is suppressed.
 * Registry-free booleans, so no Minecraft bootstrap — same pattern as {@link FramerateThrottleTest}.
 */
final class DistantHorizonsUpdatePromptSuppressionTest {

    private static final boolean SCREEN_SHOWING = true;
    private static final boolean NO_SCREEN = false;
    private static final boolean INITIAL_SCREENS_PENDING = true;
    private static final boolean NOTHING_PENDING = false;

    @Test
    @DisplayName("vanilla's task was captured → run it, even over the 'Loading Minecraft' placeholder screen")
    void pendingInitialScreens_runsThem() {
        // At game-load-finish the Minecraft constructor's GenericMessageScreen is always up, so a
        // visible screen must not stop the captured task — that is the quick-play join.
        assertEquals(RUN_INITIAL_SCREENS, decide(SCREEN_SHOWING, INITIAL_SCREENS_PENDING));
        assertEquals(RUN_INITIAL_SCREENS, decide(NO_SCREEN, INITIAL_SCREENS_PENDING));
    }

    @Test
    @DisplayName("nothing captured and a screen is up (title, connect, anything) → keep it, never replace it")
    void screenShowing_nothingPending_keepsCurrent() {
        assertEquals(KEEP_CURRENT_SCREEN, decide(SCREEN_SHOWING, NOTHING_PENDING));
    }

    @Test
    @DisplayName("no screen and nothing captured → fall back to a fresh title screen")
    void noScreen_nothingCaptured_opensTitle() {
        assertEquals(OPEN_TITLE_SCREEN, decide(NO_SCREEN, NOTHING_PENDING));
    }

    @Test
    @DisplayName("captured task is handed over exactly once")
    void capture_isOneShot() {
        Runnable task = () -> {};
        InitialScreensCapture.capture(task);
        assertTrue(InitialScreensCapture.hasPending());
        assertSame(task, InitialScreensCapture.consume());
        assertFalse(InitialScreensCapture.hasPending());
        assertNull(InitialScreensCapture.consume());

        InitialScreensCapture.capture(task);
        InitialScreensCapture.clear();
        assertNull(InitialScreensCapture.consume());
    }
}
