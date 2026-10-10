package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.Test;

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
}
