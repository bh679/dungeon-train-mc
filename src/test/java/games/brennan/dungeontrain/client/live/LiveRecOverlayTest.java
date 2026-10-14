package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void cornerSpansCoverExactlyTheMask() {
        boolean[][] painted = new boolean[LiveRecOverlay.CORNER_LEN][LiveRecOverlay.CORNER_LEN];
        for (int[] run : LiveRecOverlay.CORNER_SPANS) {
            for (int x = run[0]; x < run[1]; x++) {
                assertFalse(painted[x][run[2]], "runs overlap at " + x + "," + run[2]);
                painted[x][run[2]] = true;
            }
        }
        for (int x = 0; x < LiveRecOverlay.CORNER_LEN; x++) {
            for (int y = 0; y < LiveRecOverlay.CORNER_LEN; y++) {
                assertEquals(LiveRecOverlay.inCorner(x, y), painted[x][y], x + "," + y);
            }
        }
        assertTrue(LiveRecOverlay.CORNER_SPANS.length < 100, "a few runs, not one fill per pixel");
    }

    @Test
    void eyeSpansCoverExactlyTheHashes() {
        int set = 0;
        for (String row : LiveRecOverlay.EYE) set += (int) row.chars().filter(c -> c == '#').count();
        int covered = 0;
        for (int[] run : LiveRecOverlay.EYE_SPANS) {
            for (int x = run[0]; x < run[1]; x++) assertEquals('#', LiveRecOverlay.EYE[run[2]].charAt(x));
            covered += run[1] - run[0];
        }
        assertEquals(set, covered);
    }
}
