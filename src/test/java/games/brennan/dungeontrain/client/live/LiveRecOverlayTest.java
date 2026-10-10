package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRecOverlayTest {

    @Test
    void viewerCountRowSitsUnderLiveAndIsPartOfTheBadge() {
        int liveBottom = LiveRecOverlay.MARGIN + LiveRecOverlay.LINE + LiveRecOverlay.BADGE_PAD
            + Math.round(9 * LiveRecOverlay.BADGE_SCALE);
        assertEquals(liveBottom + LiveRecOverlay.COUNT_GAP, LiveRecOverlay.countTop(9), "just under LIVE");
        assertTrue(LiveRecOverlay.badgeBottom(9) > LiveRecOverlay.countTop(9),
            "the count row is reserved, so the pinned viewer box clears it");
    }

    @Test
    void personGlyphIsAsTallAsADigitAndRectangular() {
        assertEquals(7, LiveRecOverlay.PERSON.length, "a Minecraft digit is 7 pixels tall");
        for (String row : LiveRecOverlay.PERSON) assertEquals(LiveRecOverlay.PERSON[0].length(), row.length());
    }
}
