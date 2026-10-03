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
