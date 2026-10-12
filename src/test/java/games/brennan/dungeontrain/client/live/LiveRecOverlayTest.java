package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveRecOverlayTest {

    @Test
    void badgeStartsInsideTheTopLeftCorner() {
        assertEquals(LiveRecOverlay.MARGIN + LiveRecOverlay.LINE + LiveRecOverlay.BADGE_PAD, LiveRecOverlay.innerLeft());
    }

    @Test
    void viewerCountRowSitsJustUnderLive() {
        int liveBottom = LiveRecOverlay.MARGIN + LiveRecOverlay.LINE + LiveRecOverlay.BADGE_PAD
            + Math.round(9 * LiveRecOverlay.BADGE_SCALE);
        assertEquals(liveBottom + LiveRecOverlay.COUNT_GAP, LiveRecOverlay.countTop(9));
    }

    @Test
    void eyeIsAsTallAsADigitAndRectangular() {
        assertEquals(7, LiveRecOverlay.EYE.length, "a Minecraft digit is 7 pixels tall");
        for (String row : LiveRecOverlay.EYE) assertEquals(LiveRecOverlay.EYE[0].length(), row.length());
    }
}
