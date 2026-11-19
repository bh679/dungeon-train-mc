package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The stream keeps the streamer's window ratio inside the configured size, and crops, never stretches. */
class LiveAspectTest {

    @Test
    void sixteenNineWindowKeepsTheConfiguredSize() {
        assertArrayEquals(new int[] {1280, 720}, LiveAspect.frameSize(1280, 720, 2560, 1440));
        assertArrayEquals(new int[] {1280, 720}, LiveAspect.frameSize(1280, 720, 1366, 768));
    }

    @Test
    void fourThreeWindowKeepsTheHeightAndNarrows() {
        assertArrayEquals(new int[] {960, 720}, LiveAspect.frameSize(1280, 720, 1600, 1200));
    }

    @Test
    void wideWindowKeepsTheWidthAndShortens() {
        assertArrayEquals(new int[] {1280, 548}, LiveAspect.frameSize(1280, 720, 3440, 1474));
    }

    @Test
    void ratiosBeyondTheLimitsAreClamped() {
        // Portrait clamps to square; a 32:9 window to 21:9.
        assertArrayEquals(new int[] {720, 720}, LiveAspect.frameSize(1280, 720, 1080, 1920));
        assertArrayEquals(new int[] {1280, 548}, LiveAspect.frameSize(1280, 720, 5120, 1440));
    }

    @Test
    void sidesAreEven() {
        int[] s = LiveAspect.frameSize(854, 480, 1000, 777);
        assertArrayEquals(new int[] {618, 480}, s);
    }

    @Test
    void badInputGivesTheBox() {
        assertArrayEquals(new int[] {1280, 720}, LiveAspect.frameSize(1280, 720, 0, 1440));
    }

    @Test
    void matchingWindowIsNotCropped() {
        assertArrayEquals(new int[] {0, 0, 1600, 1200}, LiveAspect.cropRect(1600, 1200, 960, 720));
        assertArrayEquals(new int[] {0, 0, 1366, 768}, LiveAspect.cropRect(1366, 768, 1280, 720));
    }

    @Test
    void widerWindowLosesItsSidesCentred() {
        // 16:9 window into a 4:3 stream: 1440 of 1920 kept, 240 off each side.
        assertArrayEquals(new int[] {240, 0, 1680, 1080}, LiveAspect.cropRect(1920, 1080, 960, 720));
    }

    @Test
    void tallerWindowLosesTopAndBottomCentred() {
        assertArrayEquals(new int[] {0, 420, 1080, 1500}, LiveAspect.cropRect(1080, 1920, 720, 720));
    }

    @Test
    void badCropInputGivesTheWholeWindow() {
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, LiveAspect.cropRect(1920, 1080, 0, 720));
    }
}
