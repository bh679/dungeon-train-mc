package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveDecodeWidthTest {

    @Test
    void viewersDecodeAtTheViewerWidth() {
        assertEquals(640, LiveDecodeWidth.forConsumer(false, false, 120, 3, 640));
        assertEquals(640, LiveDecodeWidth.forConsumer(false, true, 120, 3, 640));
    }

    @Test
    void aStreamerAloneDecodesAtTheBoxsRealPixelSize() {
        assertEquals(240, LiveDecodeWidth.forConsumer(true, false, 120, 2, 640));
        assertEquals(360, LiveDecodeWidth.forConsumer(true, false, 120, 3, 640));
    }

    @Test
    void aTvInViewGivesTheStreamerTheFullWidth() {
        assertEquals(640, LiveDecodeWidth.forConsumer(true, true, 120, 3, 640));
    }

    @Test
    void oddWidthsRoundUpToEven() {
        assertEquals(272, LiveDecodeWidth.forConsumer(true, false, 90, 3.01, 640), "270.9 → 271 → 272");
        assertEquals(182, LiveDecodeWidth.forConsumer(true, false, 91, 2, 640), "even stays even");
    }

    @Test
    void neverNarrowerThanTheFloor() {
        assertEquals(LiveDecodeWidth.MIN, LiveDecodeWidth.forConsumer(true, false, 60, 1, 640));
    }

    @Test
    void neverWiderThanTheViewerWidth() {
        assertEquals(640, LiveDecodeWidth.forConsumer(true, false, 240, 4, 640));
        assertEquals(160, LiveDecodeWidth.forConsumer(true, false, 60, 1, 160));
    }
}
