package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientConfigSizeTest {

    @Test
    void fourSizesTwoSmallerNormalOneBigger() {
        assertEquals(80, LiveFeedClientConfig.sizeWidth(160, 0));
        assertEquals(120, LiveFeedClientConfig.sizeWidth(160, 1));
        assertEquals(160, LiveFeedClientConfig.sizeWidth(160, LiveFeedClientConfig.NORMAL_SIZE));
        assertEquals(240, LiveFeedClientConfig.sizeWidth(160, 3));
    }

    @Test
    void widthsAreAlwaysEvenAndIndexesClamp() {
        for (int base = 80; base <= 480; base++) {
            for (int i = -1; i <= 4; i++) assertTrue(LiveFeedClientConfig.sizeWidth(base, i) % 2 == 0, base + "@" + i);
        }
        assertEquals(80, LiveFeedClientConfig.sizeWidth(160, -1));
        assertEquals(240, LiveFeedClientConfig.sizeWidth(160, 9));
    }

    @Test
    void clickingStepsUpAndWrapsToTheSmallest() {
        assertEquals(1, LiveFeedClientConfig.nextSize(0));
        assertEquals(3, LiveFeedClientConfig.nextSize(2));
        assertEquals(0, LiveFeedClientConfig.nextSize(3));
    }

    @Test
    void streamersStartSmallerThanViewers() {
        assertTrue(LiveFeedClientConfig.headViewerSize(true) < LiveFeedClientConfig.headViewerSize(false));
    }

    @Test
    void videoSizesAreEvenSixteenByNineAndAscending() {
        int prev = 0;
        for (int w : LiveFeedClientConfig.CAPTURE_SIZES) {
            assertTrue(w % 2 == 0 && LiveFeedClientConfig.heightFor(w) % 2 == 0, "even " + w);
            assertTrue(w > prev, "ascending " + w);
            prev = w;
        }
        assertEquals(List.of(360, 480, 540, 720, 1080),
            LiveFeedClientConfig.CAPTURE_SIZES.stream().map(LiveFeedClientConfig::heightFor).toList());
    }

    @Test
    void videoSizeRowKeepsAHandSetWidth() {
        assertEquals(LiveFeedClientConfig.CAPTURE_SIZES, LiveFeedClientConfig.captureSizeValues(1280));
        assertEquals(List.of(640, 854, 960, 1100, 1280, 1920), LiveFeedClientConfig.captureSizeValues(1100));
    }

    @Test
    void liveViewerSizeRowOffersEveryClickSizeAsAPercent() {
        assertEquals(List.of(0, 1, 2, 3), LiveFeedClientConfig.headViewerSizeIndexes());
        assertEquals(List.of(50, 75, 100, 150),
            LiveFeedClientConfig.headViewerSizeIndexes().stream().map(LiveFeedClientConfig::headViewerSizePercent).toList());
        assertEquals(0, LiveFeedClientConfig.clampSize(-3));
        assertEquals(3, LiveFeedClientConfig.clampSize(7));
    }
}
