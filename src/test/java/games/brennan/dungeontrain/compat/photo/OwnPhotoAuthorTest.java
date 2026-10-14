package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A found photo counts as your own only when its credited author is you. */
final class OwnPhotoAuthorTest {

    @Test
    @DisplayName("your name is yours; anyone else's, a blank or a missing author is not")
    void ownAuthor() {
        assertTrue(SharedPhotos.isOwnAuthor("Dev", "Dev"));
        assertFalse(SharedPhotos.isOwnAuthor("Steve", "Dev"));
        assertFalse(SharedPhotos.isOwnAuthor("", "Dev"));
        assertFalse(SharedPhotos.isOwnAuthor(null, "Dev"));
    }
}
