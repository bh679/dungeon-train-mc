package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a shot counts once towards each kind of subject it holds")
    void countCategories() {
        assertEquals(List.of(), PhotoCounts.categories(PhotoSubjectTally.Subjects.NONE));
        assertEquals(List.of("animal", "hostile", "passenger"),
                PhotoCounts.categories(new PhotoSubjectTally.Subjects(true, true, true)));
        assertEquals(List.of("passenger"), PhotoCounts.categories(new PhotoSubjectTally.Subjects(true, false, false)));
    }
}
