package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which pastes the Book & Quill refuses after the supporters' Copy button was used. */
class CopiedBookGuardTest {

    private static final List<String> PAGES = List.of(
            "The train left at dawn and never stopped.", "Hi", "§lThe end§r of the line.");
    private static final String COPIED = BookCopyText.format("The Long Ride", "by Steve", PAGES);
    private static final List<List<String>> REMEMBERED =
            List.of(CopiedBookGuard.pieces(COPIED, "The Long Ride", PAGES));

    @Test
    void refusesTheWholeCopy() {
        assertTrue(CopiedBookGuard.matches(REMEMBERED, COPIED));
    }

    @Test
    void refusesOnePageOrAChunkOfOne() {
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "The train left at dawn and never stopped."));
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "left at dawn and never"));
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "The end of the line."));
    }

    @Test
    void refusesAShortPageOnlyWhole() {
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "hi"));
        assertFalse(CopiedBookGuard.matches(REMEMBERED, "the"));
    }

    @Test
    void ignoresCaseSpacingAndFormatting() {
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "  THE TRAIN   left\nat dawn  "));
        assertTrue(CopiedBookGuard.matches(REMEMBERED, "§othe train left at dawn"));
    }

    @Test
    void allowsUnrelatedOrEmptyText() {
        assertFalse(CopiedBookGuard.matches(REMEMBERED, "My own story about a boat."));
        assertFalse(CopiedBookGuard.matches(REMEMBERED, ""));
        assertFalse(CopiedBookGuard.matches(REMEMBERED, null));
        assertFalse(CopiedBookGuard.matches(List.of(), COPIED));
    }

    @Test
    void piecesAreNormalisedAndDistinct() {
        assertEquals(List.of("a b", "c"), CopiedBookGuard.pieces("A  b", "a b", List.of("", "§lC", "c")));
    }
}
