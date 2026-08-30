package games.brennan.dungeontrain.builder.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The submission state crosses from a relay that deploys on its own schedule, so the only thing this
 * class must never do is surprise the screen: an unknown value has to read as never-submitted rather
 * than reaching the GUI as a lang key nobody wrote.
 */
final class BuilderReviewStateTest {

    @Test
    @DisplayName("the four states survive the wire; anything else reads as never-submitted")
    void coerces() {
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of("none"));
        assertEquals(BuilderReviewState.SUBMITTED, BuilderReviewState.of("submitted"));
        assertEquals(BuilderReviewState.ACCEPTED, BuilderReviewState.of("accepted"));
        assertEquals(BuilderReviewState.DECLINED, BuilderReviewState.of("declined"));

        // A relay that predates the queue sends no field at all, which SharedCarriageClient reads as
        // the empty string — the commonest of these by far while the relay rolls out.
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of(""));
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of(null));
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of("Accepted"), "states are exact, not case-folded");
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of("escalated"), "a state added on the relay first");
    }

    @Test
    @DisplayName("only the states worth naming get a tile caption")
    void labelKeys() {
        assertEquals("gui.dungeontrain.builder.profile.review.submitted",
                BuilderReviewState.labelKeyFor(BuilderReviewState.SUBMITTED));
        assertEquals("gui.dungeontrain.builder.profile.review.accepted",
                BuilderReviewState.labelKeyFor(BuilderReviewState.ACCEPTED));
        assertEquals("gui.dungeontrain.builder.profile.review.declined",
                BuilderReviewState.labelKeyFor(BuilderReviewState.DECLINED));
        // Never-submitted deliberately has none: the tile falls back to where the build lives, which
        // is the same fact told better.
        assertNull(BuilderReviewState.labelKeyFor(BuilderReviewState.NONE));
        assertNull(BuilderReviewState.labelKeyFor("nonsense"));
    }

    @Test
    @DisplayName("waiting and declined explain themselves under the grid; accepted needs no line")
    void noteKeys() {
        assertNotNull(BuilderReviewState.noteKeyFor(BuilderReviewState.SUBMITTED));
        assertNotNull(BuilderReviewState.noteKeyFor(BuilderReviewState.DECLINED));
        assertNull(BuilderReviewState.noteKeyFor(BuilderReviewState.ACCEPTED),
                "an accepted build is doing what its author asked; there is nothing to explain");
        assertNull(BuilderReviewState.noteKeyFor(BuilderReviewState.NONE));
    }
}
