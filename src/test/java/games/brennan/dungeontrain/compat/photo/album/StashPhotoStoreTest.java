package games.brennan.dungeontrain.compat.photo.album;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashPhotoStoreTest {

    private static final String H1 = "1".repeat(64);

    @TempDir
    Path dir;

    @Test
    @DisplayName("a stashed picture is kept by hash and read back, from a fresh store too")
    void keepsAndReadsBack() {
        byte[] png = {1, 2, 3};
        assertTrue(new StashPhotoStore(dir).put(H1, png));
        StashPhotoStore fresh = new StashPhotoStore(dir);
        assertTrue(fresh.has(H1));
        assertArrayEquals(png, fresh.image(H1).orElseThrow());
    }

    @Test
    @DisplayName("a picture already held is left as it is")
    void firstWriteWins() {
        StashPhotoStore store = new StashPhotoStore(dir);
        store.put(H1, new byte[]{1});
        assertTrue(store.put(H1, new byte[]{9}));
        assertArrayEquals(new byte[]{1}, store.image(H1).orElseThrow());
    }

    @Test
    @DisplayName("anything that is not a picture hash is neither kept nor read")
    void rejectsNonHashes() throws Exception {
        StashPhotoStore store = new StashPhotoStore(dir);
        assertFalse(store.put("../escape", new byte[]{1}));
        assertFalse(store.has("../escape"));
        assertTrue(store.image("player_123").isEmpty());
        try (var files = Files.list(dir)) {
            assertTrue(files.findAny().isEmpty());
        }
    }
}
