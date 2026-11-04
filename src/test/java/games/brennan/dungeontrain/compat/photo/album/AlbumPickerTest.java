package games.brennan.dungeontrain.compat.photo.album;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumPickerTest {

    private static final String H = "1".repeat(64);

    private static AlbumStore.Entry withPictures(int pictures) {
        List<AlbumStore.Page> pages = new ArrayList<>();
        for (int i = 0; i < AlbumSavePayload.MAX_PAGES; i++) pages.add(new AlbumStore.Page(i < pictures ? H : null, "", null));
        return new AlbumStore.Entry(1, pages);
    }

    @Test
    @DisplayName("an album is full only when all 16 pages hold a picture")
    void fullness() {
        assertTrue(AlbumPicker.isFull(withPictures(16)));
        assertFalse(AlbumPicker.isFull(withPictures(15)));
        assertFalse(AlbumPicker.isFull(AlbumStore.Entry.EMPTY));
    }

    @Test
    @DisplayName("the unfinished album is the first one not full, or a new one past the last")
    void unfinished() {
        assertEquals(0, AlbumPicker.unfinished(List.of(false), 10));
        assertEquals(1, AlbumPicker.unfinished(List.of(true), 10));
        assertEquals(1, AlbumPicker.unfinished(List.of(true, false, true), 10));   // a page taken out of album 2
        assertEquals(3, AlbumPicker.unfinished(List.of(true, true, true), 10));
    }

    @Test
    @DisplayName("at the limit no new album starts: the unfinished one is the last allowed, and rolls stay within the limit")
    void limit() {
        List<Boolean> full = Collections.nCopies(10, true);
        assertEquals(9, AlbumPicker.unfinished(full, 10));
        assertEquals(0, AlbumPicker.unfinished(List.of(true), 1));
        for (double roll = 0; roll < 1; roll += 0.01) {
            int picked = AlbumPicker.found(full, 10, roll);
            assertTrue(picked >= 0 && picked < 10, "picked " + picked);
        }
        // more albums on disk than the limit allows (limit read lower): the extras are never picked
        for (double roll = 0; roll < 1; roll += 0.01) assertTrue(AlbumPicker.found(Collections.nCopies(12, true), 10, roll) < 10);
    }

    @Test
    @DisplayName("with no full albums, a found album is always the unfinished one")
    void noFullAlbums() {
        for (double roll = 0; roll < 1; roll += 0.05) assertEquals(0, AlbumPicker.found(List.of(false), 10, roll));
    }

    @Test
    @DisplayName("half the rolls give the unfinished album; the other half spread evenly over the full ones")
    void split() {
        List<Boolean> full = List.of(true, true, true, false);   // albums 1-3 full, album 4 unfinished
        Map<Integer, Integer> counts = new HashMap<>();
        int rolls = 6000;
        for (int i = 0; i < rolls; i++) counts.merge(AlbumPicker.found(full, 10, (i + 0.5) / rolls), 1, Integer::sum);
        assertEquals(3000, counts.get(3));
        assertEquals(1000, counts.get(0));
        assertEquals(1000, counts.get(1));
        assertEquals(1000, counts.get(2));
    }

    @Test
    @DisplayName("when every album is full, the unfinished half is a brand-new album")
    void allFull() {
        List<Boolean> full = Collections.nCopies(2, true);
        assertEquals(2, AlbumPicker.found(full, 10, 0.1));
        assertEquals(0, AlbumPicker.found(full, 10, 0.6));
        assertEquals(1, AlbumPicker.found(full, 10, 0.999999));
    }
}
