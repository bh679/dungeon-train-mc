package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of a review: which words are verdicts, how a comment is cleaned, what chat says. */
final class BuilderReviewEditsTest {

    @Test
    @DisplayName("only accept, feedback and decline are verdicts a button can set")
    void verdicts() {
        assertTrue(BuilderReviewEdits.isVerdict(BuilderReviewState.ACCEPTED));
        assertTrue(BuilderReviewEdits.isVerdict(BuilderReviewState.FEEDBACK));
        assertTrue(BuilderReviewEdits.isVerdict(BuilderReviewState.DECLINED));
        assertFalse(BuilderReviewEdits.isVerdict(BuilderReviewState.SUBMITTED), "re-queueing is not a decision");
        assertFalse(BuilderReviewEdits.isVerdict(BuilderReviewState.NONE));
        assertFalse(BuilderReviewEdits.isVerdict(null));
    }

    @Test
    @DisplayName("a comment is trimmed, CRLF-folded and clipped to the relay's ceiling")
    void cleaning() {
        assertEquals("", BuilderReviewEdits.clean(null));
        assertEquals("", BuilderReviewEdits.clean("   \r\n "));
        assertEquals("a\nb", BuilderReviewEdits.clean("  a\r\nb  "));
        assertEquals(BuilderReviewEdits.COMMENT_MAX, BuilderReviewEdits.clean("x".repeat(5000)).length());
    }

    @Test
    @DisplayName("a developer cannot review their own build")
    void noSelfReview() {
        // No ServerPlayer in a unit test; the owner check is the uuid comparison BuilderNoteEdits shares.
        assertTrue(BuilderNoteEdits.sameOwner("ABCDEF00-0000-4000-8000-000000000001", "abcdef00000040008000000000000001"));
        assertFalse(BuilderReviewEdits.canReview(null, "anyone"), "no player, no review");
    }

    @Test
    @DisplayName("chat says saved in green, and anything that did not save in red or yellow")
    void chatLines() {
        assertEquals(ChatFormatting.GREEN.getColor(),
                BuilderReviewEdits.said(SharedCarriageClient.CallStatus.OK).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.RED.getColor(),
                BuilderReviewEdits.said(SharedCarriageClient.CallStatus.ERROR).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.YELLOW.getColor(),
                BuilderReviewEdits.said(SharedCarriageClient.CallStatus.FORBIDDEN).getStyle().getColor().getValue());
    }
}
