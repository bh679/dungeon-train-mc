package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words of an accepted-build announcement — the only part of the post that is ours to get wrong. */
final class BuildReviewReporterTest {

    @Test
    @DisplayName("the title names reviewer, build and author, and copes with either name missing")
    void title() {
        assertEquals("Brennan accepted brick_cabin by Ada", BuildReviewReporter.title("Brennan", "brick_cabin", "Ada"));
        assertEquals("Brennan accepted a build by Ada", BuildReviewReporter.title("Brennan", "", "Ada"));
        assertEquals("Brennan accepted brick_cabin", BuildReviewReporter.title("Brennan", "brick_cabin", null));
    }

    @Test
    @DisplayName("feedback and resubmit get their own headline, colour and stock body; decline is never announced")
    void perVerdict() {
        assertEquals("Brennan sent feedback on brick_cabin by Ada",
                BuildReviewReporter.title("Brennan", "brick_cabin", "Ada", "feedback", "", ""));
        assertEquals("Brennan sent brick_cabin by Ada back for Dungeon Train 0.1130.0 or above",
                BuildReviewReporter.title("Brennan", "brick_cabin", "Ada", "resubmit", "0.1130.0", "gte"));
        assertEquals("Brennan sent brick_cabin back for Dungeon Train 1.0.0 exactly",
                BuildReviewReporter.title("Brennan", "brick_cabin", "", "resubmit", "1.0.0", "exact"));
        assertEquals("a newer version or above", BuildReviewReporter.ruleText("", "gte"));
        assertEquals(BuildReviewReporter.EMBED_FEEDBACK, BuildReviewReporter.colour("feedback"));
        assertEquals(BuildReviewReporter.EMBED_RESUBMIT, BuildReviewReporter.colour("resubmit"));
        assertEquals(BuildReviewReporter.EMBED_ACCEPTED, BuildReviewReporter.colour("accepted"));
        assertTrue(BuildReviewReporter.description("", "feedback").contains("feedback"));
        assertTrue(BuildReviewReporter.description(null, "resubmit").contains("re-submitted"));
        assertTrue(BuildReviewReporter.announces("accepted"));
        assertTrue(BuildReviewReporter.announces("feedback"));
        assertTrue(BuildReviewReporter.announces("resubmit"));
        assertEquals(false, BuildReviewReporter.announces("declined"));
        assertEquals(false, BuildReviewReporter.announces("submitted"));
    }

    @Test
    @DisplayName("the body is the comment, clipped, or a stock line when nothing was said")
    void description() {
        assertEquals("Lovely roofline.", BuildReviewReporter.description("  Lovely roofline. "));
        assertTrue(BuildReviewReporter.description("").contains("Accepted"));
        assertTrue(BuildReviewReporter.description(null).contains("Accepted"));
        String clipped = BuildReviewReporter.description("x".repeat(2000));
        assertTrue(clipped.endsWith("…"));
        assertTrue(clipped.length() <= BuildReviewReporter.COMMENT_MAX + 1);
    }
}
