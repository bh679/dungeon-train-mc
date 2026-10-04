package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedPhotosCameraFallbackTest {

    @Test
    @DisplayName("Short of photos below 1000, and while the relay's count is unknown")
    void threshold() {
        assertTrue(SharedPhotos.isShort(-1));
        assertTrue(SharedPhotos.isShort(0));
        assertTrue(SharedPhotos.isShort(999));
        assertFalse(SharedPhotos.isShort(1000));
        assertFalse(SharedPhotos.isShort(5000));
    }
}
