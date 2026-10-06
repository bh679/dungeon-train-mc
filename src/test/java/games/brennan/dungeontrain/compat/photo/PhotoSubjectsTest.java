package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The subject keys the Enchiridion's {@code photo_subject} criteria are written against. */
final class PhotoSubjectsTest {

    private static PhotoSubjects.Facts where(Set<String> types, String dimension, String band) {
        return new PhotoSubjects.Facts(types, false, false, false, false, false, false, false, false, false, dimension, band);
    }

    @Test
    @DisplayName("an empty shot of nothing, nowhere, is worth nothing")
    void nothing() {
        assertEquals(Set.of(), PhotoSubjects.keys(where(Set.of(), null, null)));
    }

    @Test
    @DisplayName("each entity type becomes entity:<id>")
    void entities() {
        assertEquals(Set.of("entity:minecraft:cow", "entity:minecraft:warden"),
                PhotoSubjects.keys(where(Set.of("minecraft:cow", "minecraft:warden"), null, null)));
    }

    @Test
    @DisplayName("passengers: any, a friend, an Echo — yours or someone else's")
    void passengers() {
        Set<String> plain = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), true, false, false, false, false,
                false, false, false, false, null, null));
        assertEquals(Set.of("playermob"), plain);
        Set<String> all = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), true, true, true, true, true,
                false, false, false, false, null, null));
        assertTrue(all.containsAll(Set.of("playermob", "friend_playermob", "echo", "echo_own", "echo_other")));
    }

    @Test
    @DisplayName("specials, cave, dimension and band keys")
    void places() {
        Set<String> keys = PhotoSubjects.keys(new PhotoSubjects.Facts(Set.of(), false, false, false, false, false,
                true, true, true, true, "minecraft:overworld", "reached_spheres"));
        assertTrue(keys.containsAll(Set.of("pigman_villager", "killer_bunny", "technoblade_pig", "cave",
                "dim:minecraft:overworld", "band:reached_spheres")));
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

    @Test
    @DisplayName("moments: a fight, your pet, an Echo's pet, a courtship")
    void moments() {
        PhotoSubjects.Facts nowhere = where(Set.of(), null, null);
        assertEquals(Set.of(), PhotoSubjects.keys(nowhere, PhotoSubjects.Scene.NONE));
        assertEquals(Set.of("combat", "pet_own", "pet_echo", "breeding"),
                PhotoSubjects.keys(nowhere, new PhotoSubjects.Scene(true, true, true, true)));
        assertEquals(Set.of("breeding"), PhotoSubjects.keys(nowhere, new PhotoSubjects.Scene(false, false, false, true)));
    }
}
