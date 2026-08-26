package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the train's vote page puts its rows, as pure arithmetic over the book's real top.
 *
 * <p>The page itself needs a screen to test, but the geometry behind it does not — the same split
 * {@code BookVoteControlsTest} makes for the control logic. What is worth pinning here is that the
 * page follows the book rather than assuming vanilla's position:</p>
 *
 * <ul>
 *   <li><b>The book is not always at y=2.</b> Scribble ships {@code centerBookGui} ON by default and
 *       slides the whole book-view GUI down by {@code (height - 192) / 3}. DT draws from
 *       {@code Render.Post}, outside the matrix Scribble translates, so it has to follow the book
 *       itself. This is the arithmetic that does it.</li>
 *   <li><b>A 22px shift put the train's line through vanilla's page indicator.</b> That is the bug
 *       this file exists for — reported against v0.642.0, the release that bundled Scribble.</li>
 *   <li><b>With no such mod the numbers must not move at all.</b> A "fix" that nudged the page for
 *       everyone else would be a worse regression than the bug.</li>
 * </ul>
 *
 * <p>Mirrors {@code BookVoteClientEvents.bookTop()} and the {@code *_DY} constants beside it. If
 * those ever disagree with this, the page has come unstuck from the book again.</p>
 */
class BookVotePageGeometryTest {

    // --- vanilla BookViewScreen, 1.21.1 ---
    /** The book art is blitted at ((width-192)/2, 2). */
    private static final int BOOK_TOP = 2;
    /** Both page-turn buttons are constructed at this y in createPageControlButtons(). */
    private static final int VANILLA_PAGE_BUTTON_Y = 159;
    /** "Page N of N" is drawn at this y, and rides whatever offset a re-centring mod applies. */
    private static final int VANILLA_PAGE_INDICATOR_Y = 18;
    /** Font line height — one row of text occupies [y, y + LINE_HEIGHT). */
    private static final int LINE_HEIGHT = 9;

    // --- DT's rows, as deltas from the top of the book (BookVoteClientEvents) ---
    private static final int BUTTON_SIZE = 18;
    private static final int PREFIX_DY = 38;
    private static final int PROMPT_DY = PREFIX_DY + 12;
    private static final int BUTTONS_DY = 90;
    private static final int LABELS_DY = BUTTONS_DY + BUTTON_SIZE + 6;
    private static final int REPORT_DY = LABELS_DY + 12;
    private static final int REPORT_TEXT_DY = REPORT_DY + BUTTON_SIZE + 3;

    /** Scribble's own formula, so the offsets under test are ones a player can actually hit. */
    private static int scribbleOffset(int screenHeight) {
        return (screenHeight - 192) / 3;
    }

    /** Mirrors {@code BookVoteClientEvents.bookTop()}: read the shift off the back page-turn button. */
    private static int bookTop(int backButtonY) {
        return BOOK_TOP + backButtonY - VANILLA_PAGE_BUTTON_Y;
    }

    /** The back button as a re-centring mod leaves it, given a shift of {@code offset}. */
    private static int backButtonY(int offset) {
        return VANILLA_PAGE_BUTTON_Y + offset;
    }

    @Test
    @DisplayName("no re-centring mod: the book is where vanilla put it")
    void vanillaBookTop() {
        assertEquals(BOOK_TOP, bookTop(backButtonY(0)));
    }

    @Test
    @DisplayName("without Scribble every row keeps the exact y it has always had")
    void unshiftedRowsAreUnchanged() {
        int top = bookTop(backButtonY(0));
        // The literals are the values these rows resolved to before the page learned to follow the
        // book. Nobody without a re-centring mod should see a single pixel move.
        assertEquals(40, top + PREFIX_DY);
        assertEquals(52, top + PROMPT_DY);
        assertEquals(92, top + BUTTONS_DY);
        assertEquals(116, top + LABELS_DY);
        assertEquals(128, top + REPORT_DY);
        assertEquals(149, top + REPORT_TEXT_DY);
    }

    @Test
    @DisplayName("the book's shift is picked up whole, whatever it is")
    void shiftIsTrackedExactly() {
        for (int offset : new int[] {1, 22, 56, 137}) {
            assertEquals(BOOK_TOP + offset, bookTop(backButtonY(offset)),
                "offset " + offset + " should move the book top by exactly that much");
        }
    }

    @Test
    @DisplayName("the reported bug: at a 22px shift the train's line no longer sits on \"Page N of N\"")
    void prefixClearsThePageIndicator() {
        // ~258px effective screen height — the case in the bug report's screenshot.
        int offset = 22;
        int indicatorTop = VANILLA_PAGE_INDICATOR_Y + offset;   // the indicator rides the shift...
        int indicatorBottom = indicatorTop + LINE_HEIGHT;

        // ...and before the fix DT's prefix did NOT, so the two collided. Guard the premise, so this
        // test fails loudly if it ever stops describing the actual bug.
        int unshiftedPrefix = BOOK_TOP + PREFIX_DY;
        assertTrue(unshiftedPrefix < indicatorBottom && unshiftedPrefix + LINE_HEIGHT > indicatorTop,
            "the pinned-to-vanilla prefix should overlap the shifted indicator — that is the bug");

        int prefix = bookTop(backButtonY(offset)) + PREFIX_DY;
        assertTrue(prefix >= indicatorBottom,
            "the prefix line must start below the page indicator, not through it");
    }

    @Test
    @DisplayName("the prefix clears the page indicator at every offset Scribble can produce")
    void prefixClearsIndicatorAtEveryScribbleOffset() {
        // 192 is the book's own height (offset 0); beyond ~1600px the GUI is never scaled that small.
        for (int screenHeight = 192; screenHeight <= 1600; screenHeight++) {
            int offset = scribbleOffset(screenHeight);
            int prefix = bookTop(backButtonY(offset)) + PREFIX_DY;
            int indicatorBottom = VANILLA_PAGE_INDICATOR_Y + offset + LINE_HEIGHT;
            assertTrue(prefix >= indicatorBottom,
                "height " + screenHeight + " (offset " + offset + "): prefix " + prefix
                    + " should clear the indicator ending at " + indicatorBottom);
        }
    }

    @Test
    @DisplayName("the rows keep their spacing — the page moves as one piece")
    void rowSpacingIsPreserved() {
        int shifted = bookTop(backButtonY(56));   // 1080p @ GUI scale 3
        int flat = bookTop(backButtonY(0));

        assertEquals(PROMPT_DY - PREFIX_DY, (shifted + PROMPT_DY) - (shifted + PREFIX_DY));
        assertEquals((flat + LABELS_DY) - (flat + BUTTONS_DY),
            (shifted + LABELS_DY) - (shifted + BUTTONS_DY));
        assertEquals((flat + REPORT_TEXT_DY) - (flat + REPORT_DY),
            (shifted + REPORT_TEXT_DY) - (shifted + REPORT_DY));

        // The labels clear the thumbs above them, and the report icon clears the labels.
        assertTrue(LABELS_DY >= BUTTONS_DY + BUTTON_SIZE, "labels must sit below the thumbs");
        assertTrue(REPORT_DY >= LABELS_DY + LINE_HEIGHT, "the report row must sit below the labels");
        assertTrue(REPORT_TEXT_DY >= REPORT_DY + BUTTON_SIZE, "its line must sit below its icon");
    }

    @Test
    @DisplayName("the whole page still lands on the paper, not off the bottom of the book")
    void everyRowStaysWithinTheBook() {
        // book.png's paper runs y 8-172, book-local (BookVoteClientEvents.DIM_Y1/DIM_Y2).
        int paperTop = 8, paperBottom = 173;
        for (int dy : new int[] {PREFIX_DY, PROMPT_DY, BUTTONS_DY, LABELS_DY, REPORT_DY, REPORT_TEXT_DY}) {
            assertTrue(dy >= paperTop, "row at +" + dy + " should start below the paper's top edge");
        }
        assertTrue(REPORT_TEXT_DY + LINE_HEIGHT <= paperBottom,
            "the last row must finish above the paper's bottom edge");
    }
}
