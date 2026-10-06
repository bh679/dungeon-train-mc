package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.photo.PhotoSubjectTally.Subjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoSubjectTallyTest {

    @BeforeEach
    void reset() { PhotoSubjectTally.clear(); }

    @Test
    @DisplayName("players, PlayerMobs and villagers are passengers; enemies hostile; peaceful creatures animals")
    void classifies() {
        assertEquals(new Subjects(true, false, false), PhotoSubjectTally.classify(true, false, false, false, false));
        assertEquals(new Subjects(true, false, false), PhotoSubjectTally.classify(false, true, false, false, false));
        assertEquals(new Subjects(true, false, false), PhotoSubjectTally.classify(false, false, true, false, false));
        assertEquals(new Subjects(false, true, false), PhotoSubjectTally.classify(false, false, false, true, false));
        assertEquals(new Subjects(false, false, true), PhotoSubjectTally.classify(false, false, false, false, true));
        // A hoglin is an Animal and an Enemy: it is a monster, not wildlife.
        assertEquals(new Subjects(false, true, false), PhotoSubjectTally.classify(false, false, false, true, true));
        assertFalse(PhotoSubjectTally.classify(false, false, false, false, false).any());
    }

    @Test
    @DisplayName("subjects wait for their print and are taken once")
    void recordAndTake() {
        Subjects s = new Subjects(true, false, true);
        PhotoSubjectTally.record("exp1", s, 1000);
        assertEquals(s, PhotoSubjectTally.take("exp1", 2000));
        assertEquals(Subjects.NONE, PhotoSubjectTally.take("exp1", 2001));
        assertEquals(Subjects.NONE, PhotoSubjectTally.take("unknown", 2002));
    }

    @Test
    @DisplayName("an empty shot is not held, and the JSON carries only the true flags")
    void emptyAndJson() {
        PhotoSubjectTally.record("exp", Subjects.NONE, 1000);
        assertEquals(0, PhotoSubjectTally.size());
        assertEquals("{\"passengers\":true,\"animals\":true}", new Subjects(true, false, true).toJson().toString());
    }

    @Test
    @DisplayName("held subjects expire, and the oldest go first past the cap")
    void expiryAndCap() {
        PhotoSubjectTally.record("old", new Subjects(false, true, false), 0);
        assertEquals(Subjects.NONE, PhotoSubjectTally.take("old", PhotoSubjectTally.TTL_MS + 1));
        for (int i = 0; i <= PhotoSubjectTally.MAX_ENTRIES; i++) {
            PhotoSubjectTally.record("e" + i, new Subjects(true, false, false), 10);
        }
        assertEquals(PhotoSubjectTally.MAX_ENTRIES, PhotoSubjectTally.size());
        assertEquals(Subjects.NONE, PhotoSubjectTally.take("e0", 11));
        assertTrue(PhotoSubjectTally.take("e" + PhotoSubjectTally.MAX_ENTRIES, 11).passengers());
    }
}
