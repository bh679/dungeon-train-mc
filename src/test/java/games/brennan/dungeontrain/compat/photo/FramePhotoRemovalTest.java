package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame-removal scope nests and never underflows, so a stray {@code end()} can't open it early. */
class FramePhotoRemovalTest {

    @Test
    void nestsAndClosesCleanly() {
        assertFalse(FramePhotoRemoval.inProgress());
        FramePhotoRemoval.begin();
        FramePhotoRemoval.begin();
        FramePhotoRemoval.end();
        assertTrue(FramePhotoRemoval.inProgress());
        FramePhotoRemoval.end();
        assertFalse(FramePhotoRemoval.inProgress());
    }

    @Test
    void extraEndDoesNotUnderflow() {
        FramePhotoRemoval.end();
        FramePhotoRemoval.begin();
        assertTrue(FramePhotoRemoval.inProgress());
        FramePhotoRemoval.end();
        assertFalse(FramePhotoRemoval.inProgress());
    }
}
