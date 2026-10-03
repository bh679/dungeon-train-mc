package games.brennan.dungeontrain.client.deathphotos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StripLayoutTest {

    @Test
    @DisplayName("thumbnails take the row height times their aspect, clamped, separated by the gap")
    void widthsFollowAspect() {
        StripLayout l = StripLayout.of(new float[] {16f / 9f, 1f, 5f, 0.1f}, 90, 8);
        assertArrayEquals(new int[] {160, 90, 180, 45}, l.widths());
        assertArrayEquals(new int[] {0, 168, 266, 454}, l.xs());
        assertEquals(499, l.totalWidth());
    }

    @Test
    @DisplayName("a bad aspect falls back to square; an empty strip has no width")
    void degenerateInputs() {
        assertArrayEquals(new int[] {50, 50}, StripLayout.of(new float[] {Float.NaN, 0f}, 50, 4).widths());
        assertEquals(0, StripLayout.of(new float[0], 50, 4).totalWidth());
    }

    @Test
    @DisplayName("a row narrower than the viewport is centred and can't scroll")
    void narrowRowCentres() {
        StripLayout l = StripLayout.of(new float[] {1f, 1f}, 100, 10); // 210 wide
        assertEquals(0, l.maxScroll(400));
        assertEquals(95, l.centreInset(400));
        assertEquals(0, l.indexAt(100, 0, 400));
        assertEquals(-1, l.indexAt(200, 0, 400)); // the gap
        assertEquals(1, l.indexAt(210, 0, 400));
        assertEquals(-1, l.indexAt(20, 0, 400));  // left of the row
    }

    @Test
    @DisplayName("an overflowing row scrolls and hit-tests in scrolled coordinates")
    void wideRowScrolls() {
        StripLayout l = StripLayout.of(new float[] {2f, 2f, 2f}, 100, 10); // 620 wide
        assertEquals(320, l.maxScroll(300));
        assertEquals(0, l.centreInset(300));
        assertEquals(0, l.indexAt(5, 0, 300));
        assertEquals(1, l.indexAt(5, 210, 300));
        assertEquals(2, l.indexAt(299, 320, 300));
    }

    @Test
    @DisplayName("Exposure ARGB pixels become opaque NativeImage ABGR")
    void argbToAbgr() {
        assertEquals(0xFF332211, CameraDeathPhoto.argbToAbgr(0x80112233));
        assertEquals(0xFF000000, CameraDeathPhoto.argbToAbgr(0));
    }
}
