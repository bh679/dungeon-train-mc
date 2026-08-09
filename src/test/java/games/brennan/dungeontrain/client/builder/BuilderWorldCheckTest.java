package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source-precedence decision behind {@link BuilderWorldCheck#isBuilderWorld()}.
 *
 * <p>Two sources answer at different points in the join — the client level exists by the time
 * the death screen or pause menu opens, the integrated server exists earlier, during the
 * loading screen. {@code null} means "this source can't answer yet", which is the case that
 * matters: getting it wrong would either theme a builder world's loading screen or, worse,
 * strip DT's chrome from a normal world.</p>
 */
final class BuilderWorldCheckTest {

    @Test
    @DisplayName("The client level answers when it can")
    void levelWins() {
        assertTrue(BuilderWorldCheck.shouldUseBuilderChrome(true, null));
        assertFalse(BuilderWorldCheck.shouldUseBuilderChrome(false, null));
    }

    @Test
    @DisplayName("Before the client level exists, the integrated server answers")
    void serverAnswersDuringLoad() {
        assertTrue(BuilderWorldCheck.shouldUseBuilderChrome(null, true));
        assertFalse(BuilderWorldCheck.shouldUseBuilderChrome(null, false));
    }

    @Test
    @DisplayName("Neither source available — not a builder world")
    void neitherSourceMeansNo() {
        assertFalse(BuilderWorldCheck.shouldUseBuilderChrome(null, null),
                "a multiplayer client has no integrated server; it must keep DT's chrome");
    }

    @Test
    @DisplayName("The level wins outright if the two ever disagree")
    void levelBeatsServerOnDisagreement() {
        assertTrue(BuilderWorldCheck.shouldUseBuilderChrome(true, false));
        assertFalse(BuilderWorldCheck.shouldUseBuilderChrome(false, true));
    }
}
