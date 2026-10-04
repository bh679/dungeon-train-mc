package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedPhotosCameraFallbackTest {

    @Test
    @DisplayName("Camera chance tapers 30% / 12.5% / 5% / 0 as the relay's photo count grows; unknown counts as none")
    void tiers() {
        assertEquals(300, SharedPhotos.cameraFallbackPerMille(-1));
        assertEquals(300, SharedPhotos.cameraFallbackPerMille(0));
        assertEquals(300, SharedPhotos.cameraFallbackPerMille(999));
        assertEquals(125, SharedPhotos.cameraFallbackPerMille(1_000));
        assertEquals(125, SharedPhotos.cameraFallbackPerMille(4_999));
        assertEquals(50, SharedPhotos.cameraFallbackPerMille(5_000));
        assertEquals(50, SharedPhotos.cameraFallbackPerMille(9_999));
        assertEquals(0, SharedPhotos.cameraFallbackPerMille(10_000));
        assertEquals(0, SharedPhotos.cameraFallbackPerMille(50_000));
    }
}
