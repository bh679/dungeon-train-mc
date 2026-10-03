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
        assertEquals(BuilderReviewState.FEEDBACK, BuilderReviewState.of("feedback"));

        // A relay that predates the queue sends no field at all, which SharedCarriageClient reads as
        // the empty string — the commonest of these by far while the relay rolls out.
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of(""));
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of(null));
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of("Accepted"), "states are exact, not case-folded");
        assertEquals(BuilderReviewState.NONE, BuilderReviewState.of("escalated"), "a state added on the relay first");
    }

    @Test
    @DisplayName("three states ring their tile; never-submitted is left alone")
    void borderColours() {
        assertEquals(BuilderReviewState.BORDER_SUBMITTED,
                BuilderReviewState.borderColourFor(BuilderReviewState.SUBMITTED));
        assertEquals(BuilderReviewState.BORDER_ACCEPTED,
                BuilderReviewState.borderColourFor(BuilderReviewState.ACCEPTED));
        assertEquals(BuilderReviewState.BORDER_DECLINED,
                BuilderReviewState.borderColourFor(BuilderReviewState.DECLINED));
        // Most builds are in this state most of the time; a fourth colour would be noise.
        assertEquals(BuilderReviewState.BORDER_NONE,
                BuilderReviewState.borderColourFor(BuilderReviewState.NONE));
        assertEquals(BuilderReviewState.BORDER_NONE, BuilderReviewState.borderColourFor("nonsense"));

        // Opaque, or the tile art shows through and the state reads as a different colour on every
        // build behind it.
        assertEquals(BuilderReviewState.BORDER_FEEDBACK,
                BuilderReviewState.borderColourFor(BuilderReviewState.FEEDBACK));
        for (int colour : new int[]{BuilderReviewState.BORDER_SUBMITTED,
                BuilderReviewState.BORDER_ACCEPTED, BuilderReviewState.BORDER_DECLINED,
                BuilderReviewState.BORDER_FEEDBACK}) {
            assertEquals(0xFF, (colour >>> 24) & 0xFF, "alpha must be full");
        }
    }

    @Test
    @DisplayName("waiting and declined explain themselves under the grid; accepted needs no line")
    void noteKeys() {
        assertNotNull(BuilderReviewState.noteKeyFor(BuilderReviewState.SUBMITTED));
        assertNotNull(BuilderReviewState.noteKeyFor(BuilderReviewState.DECLINED));
        assertEquals("gui.dungeontrain.builder.profile.review.feedback_note",
                BuilderReviewState.noteKeyFor(BuilderReviewState.FEEDBACK),
                "feedback is a verdict the author has to act on, so it is explained like declined");
        assertNull(BuilderReviewState.noteKeyFor(BuilderReviewState.ACCEPTED),
                "an accepted build is doing what its author asked; there is nothing to explain");
        assertNull(BuilderReviewState.noteKeyFor(BuilderReviewState.NONE));
    }
}
