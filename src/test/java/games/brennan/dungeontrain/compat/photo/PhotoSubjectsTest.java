package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The subject keys the Enchiridion's {@code photo_subject} criteria are written against. */
final class PhotoSubjectsTest {

    private static PhotoSubjects.Facts plain(Set<String> types) {
        return where(types, null, null);
    }

    private static PhotoSubjects.Facts where(Set<String> types, String dimension, String band) {
        return new PhotoSubjects.Facts(types, false, false, false, false, false, false, false, dimension, band);
    }

    @Test
    @DisplayName("an empty shot of nothing, nowhere, is worth nothing")
    void nothing() {
        assertEquals(Set.of(), PhotoSubjects.keys(plain(Set.of())));
    }

    @Test
    @DisplayName("each entity type becomes entity:<id>")
    void entities() {
        assertEquals(Set.of("entity:minecraft:cow", "entity:minecraft:warden"),
                PhotoSubjects.keys(plain(Set.of("minecraft:cow", "minecraft:warden"))));
    }

    @Test
    @DisplayName("a selfie only counts with a PlayerMob in it")
    void selfieNeedsAPlayerMob() {
        Set<String> alone = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), false, false, false, false, false,
                true, false, null, null));
        assertFalse(alone.contains(PhotoSubjects.SELFIE_PLAYERMOB));
        Set<String> together = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), true, true, false, false, false,
                true, false, null, null));
        assertTrue(together.containsAll(Set.of("playermob", "echo", "selfie_playermob")));
    }

    @Test
    @DisplayName("specials, cave, dimension and band keys")
    void places() {
        Set<String> keys = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), false, false, true, true, true,
                false, true, "minecraft:overworld", "reached_spheres"));
        assertTrue(keys.containsAll(Set.of("pigman_villager", "killer_bunny", "technoblade_pig", "cave",
                "dim:minecraft:overworld", "band:reached_spheres", "band:any")));
        assertFalse(keys.contains(PhotoSubjects.NETHER));
        assertFalse(keys.contains(PhotoSubjects.END));
    }

    @Test
    @DisplayName("the train's Nether and End count as the Nether and the End, as do the vanilla ones")
    void netherAndEnd() {
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:overworld", "reached_nether")).contains("nether"));
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:overworld", "reached_better_nether")).contains("nether"));
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:the_nether", null)).contains("nether"));
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:overworld", "reached_end_islands")).contains("end"));
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:overworld", "reached_better_end")).contains("end"));
        assertTrue(PhotoSubjects.keys(where(Set.of(), "minecraft:the_end", null)).contains("end"));
    }
}
