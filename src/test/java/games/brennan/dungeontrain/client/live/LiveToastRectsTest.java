package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A popup's GUI rectangle maps onto the stream picture and back onto the game frame exactly. */
class LiveToastRectsTest {

    @Test
    void popupTopRightMapsToStreamAndFramePixels() {
        // 2560×1440 window at GUI scale 4 (640×360 GUI), streamed at 1280×720: half size.
        // A 160×32 popup fully in at the top right: GUI x 480..640, y 0..32.
        int[] r = LiveToastRects.blitRect(480f, 0f, 160, 32, 4.0, 2560, 1440, 1280, 720);
        // Stream: x 960..1280, top 0..64 → GL bottom-up y 656..720. Frame: double each.
        assertArrayEquals(new int[] {1920, 1312, 2560, 1440, 960, 656, 1280, 720}, r);
    }

    @Test
    void halfSlidPopupRoundsOutwardToWholeStreamPixels() {
        // Window 1920×1080, GUI scale 3, stream 1280×720 (ratio 2/3): stream px per GUI px = 2.
        int[] r = LiveToastRects.blitRect(560.25f, 32f, 160, 32, 3.0, 1920, 1080, 1280, 720);
        // x 1120.5 → 1120 .. clamped 1280; y 64..128 → GL 592..656.
        assertArrayEquals(new int[] {1680, 888, 1920, 984, 1120, 592, 1280, 656}, r);
    }

    @Test
    void croppedWindowMapsThroughTheCrop() {
        // 1920×1080 window streamed 4:3 at 960×720: window x 240..1680 kept. GUI scale 3.
        // An 80×32 popup at GUI x 480 (window 1440..1680), y 0..32 (window 0..96).
        int[] crop = LiveAspect.cropRect(1920, 1080, 960, 720);
        int[] r = LiveToastRects.blitRect(480f, 0f, 80, 32, 3.0, crop, 1080, 960, 720);
        // Stream: x 800..960, top 0..64 → GL 656..720. Window: back through the crop.
        assertArrayEquals(new int[] {1440, 984, 1680, 1080, 800, 656, 960, 720}, r);
    }

    @Test
    void popupInTheCroppedOffSideGivesNothing() {
        int[] crop = LiveAspect.cropRect(1920, 1080, 960, 720);
        assertNull(LiveToastRects.blitRect(0f, 0f, 60, 32, 3.0, crop, 1080, 960, 720));
    }

    @Test
    void offscreenOrDegenerateGivesNothing() {
        assertNull(LiveToastRects.blitRect(700f, 0f, 160, 32, 4.0, 2560, 1440, 1280, 720));
        assertNull(LiveToastRects.blitRect(0f, 0f, 160, 32, 0.0, 2560, 1440, 1280, 720));
        assertNull(LiveToastRects.blitRect(0f, 0f, 160, 32, 4.0, 0, 1440, 1280, 720));
    }
}
