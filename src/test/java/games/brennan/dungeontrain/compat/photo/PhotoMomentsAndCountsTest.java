package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Final Moments' one-second window, and which photo tallies a shot counts towards. */
final class PhotoMomentsAndCountsTest {

    @Test
    @DisplayName("a death counts for Final Moments only within a second after the shot")
    void finalMomentsWindow() {
        assertTrue(PhotoFinalMoments.inWindow(100, 100));
        assertTrue(PhotoFinalMoments.inWindow(100, 120));
        assertFalse(PhotoFinalMoments.inWindow(100, 121));
        assertFalse(PhotoFinalMoments.inWindow(100, 99));
    }

    @Test
    @DisplayName("a Wither photo more than a second but under ten before its death is a near miss, in whole seconds")
    void beaconTooEarly() {
        assertEquals(Optional.empty(), BeaconPhotoHints.tooEarlySeconds(100, 120));
        assertEquals(Optional.of(2), BeaconPhotoHints.tooEarlySeconds(100, 121));
        assertEquals(Optional.of(3), BeaconPhotoHints.tooEarlySeconds(100, 160));
        assertEquals(Optional.of(10), BeaconPhotoHints.tooEarlySeconds(100, 300));
        assertEquals(Optional.empty(), BeaconPhotoHints.tooEarlySeconds(100, 301));
    }

    @Test
    @DisplayName("a shot counts once towards each kind of subject it holds")
    void countCategories() {
        assertEquals(List.of(), PhotoCounts.categories(PhotoSubjectTally.Subjects.NONE));
        assertEquals(List.of("animal", "hostile", "passenger"),
                PhotoCounts.categories(new PhotoSubjectTally.Subjects(true, true, true)));
        assertEquals(List.of("passenger"), PhotoCounts.categories(new PhotoSubjectTally.Subjects(true, false, false)));
    }

    @Test
    @DisplayName("each counted photo up to the top tier's 50 gets its own numbered album entry")
    void albumEntries() {
        assertEquals("dungeontrain:photo_001", PhotoCounts.albumEntry(1));
        assertEquals("dungeontrain:photo_050", PhotoCounts.albumEntry(50));
        assertNull(PhotoCounts.albumEntry(51));
        assertNull(PhotoCounts.albumEntry(0));
    }
}
