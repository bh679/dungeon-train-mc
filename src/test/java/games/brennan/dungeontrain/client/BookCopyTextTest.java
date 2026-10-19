package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the supporters' Copy button puts on the clipboard. */
class BookCopyTextTest {

    @Test
    void titleAuthorThenPagesSeparatedByBlankLines() {
        assertEquals("The Long Ride\nby Steve\n\nFirst page.\n\nSecond page.",
                BookCopyText.format("The Long Ride", "by Steve", List.of("First page.", "Second page.")));
    }

    @Test
    void stripsFormattingAndDropsTrailingEmptyPages() {
        assertEquals("Bold\n\n\n\nafter a blank",
                BookCopyText.format(null, null, List.of("§lBold§r", "", "after a blank", "", "  ")));
    }

    @Test
    void worksWithoutTitleOrAuthor() {
        assertEquals("by Alex\n\nOnly page", BookCopyText.format("", "by Alex", List.of("Only page")));
        assertEquals("", BookCopyText.format(null, null, List.of("", "")));
    }
}
