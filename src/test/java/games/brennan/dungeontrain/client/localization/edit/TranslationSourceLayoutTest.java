package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How much of the screen the English above the edit box is allowed to take.
 * Pure — no Minecraft bootstrap, which is why the arithmetic lives outside the widget.
 */
class TranslationSourceLayoutTest {

    /** Minecraft's default font metrics, so the numbers here mean what they do in game. */
    private static final int LINE = 9;
    private static final int GAP = 4;

    @Test
    @DisplayName("a heading and one line of English cost a heading, a gap and a line")
    void shortContent() {
        assertEquals(LINE + GAP + LINE, TranslationSourceLayout.contentHeight(LINE, GAP, 1, 0));
    }

    @Test
    @DisplayName("a reviewer's reply adds its own gap and byline on top of its lines")
    void replyCostsAGapAndAByline() {
        int withoutReply = TranslationSourceLayout.contentHeight(LINE, GAP, 3, 0);
        assertEquals(withoutReply + GAP + LINE * 3,
            TranslationSourceLayout.contentHeight(LINE, GAP, 3, 2));
    }

    @Test
    @DisplayName("text that fits gets exactly what it needs, and nothing more")
    void shortTextTakesOnlyWhatItNeeds() {
        assertEquals(40, TranslationSourceLayout.viewportHeight(40, 480, 300, LINE));
    }

    @Test
    @DisplayName("text longer than half the window stops at half the window")
    void longTextStopsAtHalfTheScreen() {
        assertEquals(240, TranslationSourceLayout.viewportHeight(900, 480, 300, LINE));
    }

    @Test
    @DisplayName("on a short window the editor's room wins over the half-screen cap")
    void editorKeepsItsRoomOnAShortWindow() {
        // Half of 200 is 100, but only 60 is left once the edit box keeps its two rows.
        assertEquals(60, TranslationSourceLayout.viewportHeight(900, 200, 60, LINE));
    }

    @Test
    @DisplayName("a window with no room left still shows one line rather than nothing")
    void neverCollapsesBelowOneLine() {
        assertEquals(LINE, TranslationSourceLayout.viewportHeight(900, 100, -20, LINE));
    }

    @Test
    @DisplayName("nothing scrolls while everything is on screen")
    void noScrollWhenItAllFits() {
        assertEquals(0, TranslationSourceLayout.maxScroll(40, 240));
    }

    @Test
    @DisplayName("what scrolls is exactly what does not fit")
    void scrollIsTheOverflow() {
        assertEquals(660, TranslationSourceLayout.maxScroll(900, 240));
    }
}
