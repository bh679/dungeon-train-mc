package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveViewerBandsTest {

    @Test
    void bandsAreZeroThenPowersOfTen() {
        assertEquals(0, LiveViewerBands.band(-1));
        assertEquals(0, LiveViewerBands.band(0));
        assertEquals(1, LiveViewerBands.band(1));
        assertEquals(1, LiveViewerBands.band(10));
        assertEquals(2, LiveViewerBands.band(11));
        assertEquals(2, LiveViewerBands.band(100));
        assertEquals(3, LiveViewerBands.band(101));
        assertEquals(3, LiveViewerBands.band(1000));
        assertEquals(4, LiveViewerBands.band(1001));
        assertEquals(10, LiveViewerBands.band(Integer.MAX_VALUE));
    }

    @Test
    void silentBeforeStartAndWhileCountUnknown() {
        LiveViewerBands bands = new LiveViewerBands();
        assertFalse(bands.update(5, 0));
        bands.start(0);
        assertFalse(bands.update(-1, 0));
        assertFalse(bands.update(-1, LiveViewerBands.HOLD_MS * 2));
    }

    @Test
    void newBandAnnouncedOnceItHolds() {
        LiveViewerBands bands = new LiveViewerBands();
        bands.start(0);
        assertFalse(bands.update(0, 0));
        assertFalse(bands.update(3, 1_000));
        assertFalse(bands.update(4, 1_000 + LiveViewerBands.HOLD_MS - 1));
        assertTrue(bands.update(4, 1_000 + LiveViewerBands.HOLD_MS));
        assertFalse(bands.update(7, 1_000 + LiveViewerBands.HOLD_MS * 3));
    }

    @Test
    void wobbleAcrossBoundaryStaysQuiet() {
        LiveViewerBands bands = new LiveViewerBands();
        bands.start(10);
        long t = 0;
        for (int i = 0; i < 20; i++) {
            assertFalse(bands.update(i % 2 == 0 ? 11 : 10, t));
            t += LiveViewerBands.HOLD_MS / 2;
        }
    }

    @Test
    void fallingBandIsAnnouncedToo() {
        LiveViewerBands bands = new LiveViewerBands();
        bands.start(150);
        assertFalse(bands.update(40, 0));
        assertTrue(bands.update(40, LiveViewerBands.HOLD_MS));
        assertFalse(bands.update(0, LiveViewerBands.HOLD_MS + 1));
        assertTrue(bands.update(0, LiveViewerBands.HOLD_MS * 3));
    }
}
