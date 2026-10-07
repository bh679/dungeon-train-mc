package games.brennan.dungeontrain.compat.photo.album;

import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumPictureCacheTest {

    private static String hash(int n) {
        return String.format("%064x", n);
    }

    private static PhotoPngCodec.Decoded picture(int value) {
        return new PhotoPngCodec.Decoded(2, 2, new byte[] {(byte) value, (byte) value, (byte) value, (byte) value});
    }

    @Test
    @DisplayName("bounded: the picture used least recently goes first")
    void evictsLeastRecentlyUsed() {
        AlbumPictureCache cache = new AlbumPictureCache(2);
        cache.put(hash(1), picture(1));
        cache.put(hash(2), picture(2));
        cache.picture(hash(1));                 // 1 is used again, so 2 is now the oldest
        cache.put(hash(3), picture(3));
        assertTrue(cache.has(hash(1)));
        assertFalse(cache.has(hash(2)));
        assertTrue(cache.has(hash(3)));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("prewarm decodes each picture once, off the calling thread, and skips what it cannot read")
    void prewarmDecodesOnce() throws Exception {
        AlbumPictureCache cache = new AlbumPictureCache(8);
        byte[] png = PhotoPngCodec.encode(2, 2, new byte[] {0, 1, 2, 3}, new int[256]);
        AtomicInteger reads = new AtomicInteger();
        cache.prewarm(List.of(hash(1), hash(1), hash(2), "not-a-hash"), h -> {
            reads.incrementAndGet();
            return h.equals(hash(1)) ? Optional.of(png) : Optional.empty();
        });
        cache.awaitPrewarm();
        assertTrue(cache.has(hash(1)));
        assertFalse(cache.has(hash(2)));
        assertEquals(2, reads.get(), "duplicates and junk are never read");
        cache.prewarm(List.of(hash(1)), h -> {
            reads.incrementAndGet();
            return Optional.of(png);
        });
        cache.awaitPrewarm();
        assertEquals(2, reads.get(), "a picture already held is not read again");
    }

    @Test
    void junkHashIsNotKept() {
        AlbumPictureCache cache = new AlbumPictureCache(4);
        cache.put("nope", picture(1));
        assertEquals(0, cache.size());
    }
}
