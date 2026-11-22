package games.brennan.dungeontrain.compat.photo.album;

import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumPngFetchTest {

    private static final byte[] PIXELS_A = {0, 1, 2, 3};
    private static final byte[] PIXELS_B = {3, 2, 1, 0};
    private static final String HASH_A = AlbumImageHash.of(2, 2, PIXELS_A);
    private static final String HASH_B = AlbumImageHash.of(2, 2, PIXELS_B);
    private static final byte[] RELAY_BYTES = {42};

    @TempDir
    Path dir;

    private AlbumStore store;
    private final List<String> fetched = new ArrayList<>();

    @AfterEach
    void flush() {
        if (store != null) store.flush();
    }

    private static byte[] png(byte[] pixels) throws IOException {
        return PhotoPngCodec.encode(2, 2, pixels, new int[256]);
    }

    private Path imageFile(String hash) {
        return dir.resolve("images").resolve(hash + ".png");
    }

    private void onDisk(String hash, byte[] bytes) throws IOException {
        Files.createDirectories(imageFile(hash).getParent());
        Files.write(imageFile(hash), bytes);
    }

    private Map<String, byte[]> collect(String... hashes) {
        store = new AlbumStore(dir);
        List<AlbumSavePayload.Page> pages = Arrays.stream(hashes).map(h -> new AlbumSavePayload.Page(h, "")).toList();
        return AlbumPngFetch.collect(pages, store::verifiedImage, page -> {
            fetched.add(page.hash());
            return Optional.of(RELAY_BYTES);
        });
    }

    @Test
    @DisplayName("a picture already kept on this computer is not downloaded again")
    void keptPictureNotDownloaded() throws IOException {
        byte[] kept = png(PIXELS_A);
        onDisk(HASH_A, kept);
        Map<String, byte[]> pngs = collect(HASH_A, HASH_B);
        assertEquals(List.of(HASH_B), fetched);
        assertArrayEquals(kept, pngs.get(HASH_A));
        assertArrayEquals(RELAY_BYTES, pngs.get(HASH_B));
    }

    @Test
    @DisplayName("a picture named twice is asked for once")
    void repeatedPictureOnce() {
        collect(HASH_B, HASH_B);
        assertEquals(List.of(HASH_B), fetched);
    }

    @Test
    @DisplayName("a truncated picture on disk is downloaded again")
    void truncatedPictureDownloaded() throws IOException {
        byte[] whole = png(PIXELS_A);
        onDisk(HASH_A, Arrays.copyOf(whole, whole.length / 2));
        Map<String, byte[]> pngs = collect(HASH_A);
        assertEquals(List.of(HASH_A), fetched);
        assertArrayEquals(RELAY_BYTES, pngs.get(HASH_A));
    }

    @Test
    @DisplayName("a file whose pixels no longer match its name is downloaded again")
    void mismatchedPictureDownloaded() throws IOException {
        onDisk(HASH_A, png(PIXELS_B));
        collect(HASH_A);
        assertEquals(List.of(HASH_A), fetched);
    }

    @Test
    @DisplayName("verifiedImage returns a sound picture and refuses a missing or damaged one")
    void verifiedImage() throws IOException {
        byte[] kept = png(PIXELS_A);
        onDisk(HASH_A, kept);
        onDisk(HASH_B, new byte[] {1, 2, 3});
        store = new AlbumStore(dir);
        assertArrayEquals(kept, store.verifiedImage(HASH_A).orElseThrow());
        assertFalse(store.verifiedImage(HASH_B).isPresent());
        assertFalse(store.verifiedImage("c".repeat(64)).isPresent());
        assertTrue(store.hasImage(HASH_B));   // the damaged file is still there until a fresh copy replaces it
    }

    @Test
    @DisplayName("a fresh copy replaces a damaged file on disk")
    void freshCopyRepairsDamagedFile() throws IOException {
        onDisk(HASH_A, new byte[] {1, 2, 3});
        byte[] fresh = png(PIXELS_A);
        store = new AlbumStore(dir);
        store.putImages(Map.of(HASH_A, fresh));
        store.flush();
        assertArrayEquals(fresh, Files.readAllBytes(imageFile(HASH_A)));
        assertArrayEquals(fresh, store.verifiedImage(HASH_A).orElseThrow());
    }
}
