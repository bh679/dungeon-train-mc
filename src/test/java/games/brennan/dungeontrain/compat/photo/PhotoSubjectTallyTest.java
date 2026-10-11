package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.photo.PhotoSubjectTally.Subjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    @DisplayName("a shot's advancements ride along with its subjects and are taken together")
    void advancementsJoinSubjects() {
        Subjects s = new Subjects(false, false, true);
        PhotoSubjectTally.record("exp", s, 1000);
        PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/say_cheese", 1001);
        PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/pet_portrait", 1002);
        PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/say_cheese", 1003); // once only
        PhotoSubjectTally.Shot shot = PhotoSubjectTally.takeShot("exp", 2000);
        assertEquals(s, shot.subjects());
        assertEquals(List.of("dungeontrain:enchiridion/say_cheese", "dungeontrain:enchiridion/pet_portrait"), shot.advancements());
        assertEquals(PhotoSubjectTally.Shot.NONE, PhotoSubjectTally.takeShot("exp", 2001));
    }

    @Test
    @DisplayName("a shot with no subjects is still held when it earned an advancement")
    void advancementsWithoutSubjects() {
        PhotoSubjectTally.record("exp", Subjects.NONE, 1000);
        PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/photo_alpha", 1001);
        PhotoSubjectTally.Shot shot = PhotoSubjectTally.takeShot("exp", 1002);
        assertEquals(Subjects.NONE, shot.subjects());
        assertEquals(List.of("dungeontrain:enchiridion/photo_alpha"), shot.advancements());
    }

    @Test
    @DisplayName("subjects recorded after an advancement keep it; take() still answers subjects only")
    void subjectsAfterAdvancement() {
        PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/shh", 1000);
        PhotoSubjectTally.record("exp", new Subjects(false, true, false), 1001);
        assertEquals(List.of("dungeontrain:enchiridion/shh"), PhotoSubjectTally.takeShot("exp", 1002).advancements());
        PhotoSubjectTally.record("exp2", new Subjects(true, false, false), 1003);
        assertTrue(PhotoSubjectTally.take("exp2", 1004).passengers());
    }

    @Test
    @DisplayName("advancements are capped per shot and expire with it")
    void advancementCapAndExpiry() {
        for (int i = 0; i < PhotoSubjectTally.MAX_ADVANCEMENTS + 5; i++) {
            PhotoSubjectTally.recordAdvancement("exp", "dungeontrain:enchiridion/a" + i, 1000);
        }
        assertEquals(PhotoSubjectTally.MAX_ADVANCEMENTS, PhotoSubjectTally.takeShot("exp", 1001).advancements().size());
        PhotoSubjectTally.recordAdvancement("old", "dungeontrain:enchiridion/say_cheese", 0);
        assertEquals(PhotoSubjectTally.Shot.NONE, PhotoSubjectTally.takeShot("old", PhotoSubjectTally.TTL_MS + 1));
        PhotoSubjectTally.recordAdvancement(null, "x", 0);
        PhotoSubjectTally.recordAdvancement("e", " ", 0);
        assertEquals(0, PhotoSubjectTally.size());
    }
}
