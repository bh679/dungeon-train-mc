package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedPhotosCameraFallbackTest {

    @Test
    @DisplayName("Camera chance tapers 50 / 20 / 5 / 0 as the relay's photo count grows; unknown counts as none")
    void tiers() {
        assertEquals(50, SharedPhotos.cameraFallbackPercent(-1));
        assertEquals(50, SharedPhotos.cameraFallbackPercent(0));
        assertEquals(50, SharedPhotos.cameraFallbackPercent(999));
        assertEquals(20, SharedPhotos.cameraFallbackPercent(1_000));
        assertEquals(20, SharedPhotos.cameraFallbackPercent(4_999));
        assertEquals(5, SharedPhotos.cameraFallbackPercent(5_000));
        assertEquals(5, SharedPhotos.cameraFallbackPercent(9_999));
        assertEquals(0, SharedPhotos.cameraFallbackPercent(10_000));
        assertEquals(0, SharedPhotos.cameraFallbackPercent(50_000));
    }
}
