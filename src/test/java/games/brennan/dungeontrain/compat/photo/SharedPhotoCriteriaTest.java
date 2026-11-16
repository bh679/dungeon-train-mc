package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SharedPhotoCriteria} copies ticks between lists by criterion name, which is only right while
 * a shared name means the same photo subject on every list.
 */
class SharedPhotoCriteriaTest {

    /** Criterion name → its {@code subject}, for one collection. */
    private static Map<String, String> subjects(String path) throws IOException {
        JsonObject advancement = JsonParser.parseString(
                Files.readString(RepoPaths.advancements().resolve(path + ".json"))).getAsJsonObject();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> c : advancement.getAsJsonObject("criteria").entrySet()) {
            JsonObject criterion = c.getValue().getAsJsonObject();
            assertEquals("dungeontrain:photo_subject", criterion.get("trigger").getAsString(), path + " " + c.getKey());
            out.put(c.getKey(), criterion.getAsJsonObject("conditions").get("subject").getAsString());
        }
        return out;
    }

    @Test
    @DisplayName("A criterion name shared by two collections names the same photo subject")
    void sharedNamesMatchSubjects() throws IOException {
        Map<String, String> seen = new HashMap<>();
        for (String path : SharedPhotoCriteria.GROUP) {
            for (Map.Entry<String, String> c : subjects(path).entrySet()) {
                String before = seen.putIfAbsent(c.getKey(), c.getValue());
                if (before != null) assertEquals(before, c.getValue(), path + " " + c.getKey());
            }
        }
    }

    @Test
    @DisplayName("Every zombie and skeleton type is also in Most Wanted, so its tick there carries over")
    void familiesAreInMostWanted() throws IOException {
        Map<String, String> mostWanted = subjects(SharedPhotoCriteria.GROUP.get(0));
        for (String path : SharedPhotoCriteria.GROUP.subList(1, SharedPhotoCriteria.GROUP.size())) {
            Map<String, String> family = subjects(path);
            assertFalse(family.isEmpty(), path);
            for (String criterion : family.keySet()) {
                assertTrue(mostWanted.containsKey(criterion), path + " " + criterion + " missing from Most Wanted");
            }
        }
    }

    @Test
    @DisplayName("Zombie and skeleton horses are not family: no list in the group names them")
    void undeadHorsesLeftOut() throws IOException {
        for (String path : SharedPhotoCriteria.GROUP) {
            Map<String, String> criteria = subjects(path);
            for (String horse : new String[] {"minecraft:zombie_horse", "minecraft:skeleton_horse"}) {
                assertFalse(criteria.containsKey(horse), path + " names " + horse);
                assertFalse(criteria.containsValue("entity:" + horse), path + " counts " + horse);
            }
        }
    }

    @Test
    @DisplayName("Only the three mob collections are in the group")
    void groupMembership() {
        assertTrue(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("dungeontrain", "enchiridion/family_photo")));
        assertFalse(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("dungeontrain", "enchiridion/nature_documentary")));
        assertFalse(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("other", "enchiridion/family_photo")));
    }
    private static final String MOST_WANTED = "enchiridion/most_wanted";
    private static final String FAMILY = "enchiridion/family_photo";

    @Test
    @DisplayName("A subset list has a photo when its own album or Most Wanted's holds one; Most Wanted only its own")
    void hasPhoto() {
        Map<String, Set<String>> photos = Map.of(FAMILY, Set.of("minecraft:husk"), MOST_WANTED, Set.of("minecraft:drowned"));
        assertTrue(SharedPhotoCriteria.hasPhoto(photos, FAMILY, "minecraft:husk"));
        assertTrue(SharedPhotoCriteria.hasPhoto(photos, FAMILY, "minecraft:drowned"));
        assertFalse(SharedPhotoCriteria.hasPhoto(photos, FAMILY, "minecraft:zombie"));
        assertFalse(SharedPhotoCriteria.hasPhoto(photos, MOST_WANTED, "minecraft:husk"));
        assertFalse(SharedPhotoCriteria.hasPhoto(Map.of(), FAMILY, "minecraft:husk"));
    }

    @Test
    @DisplayName("The one-time revoke takes only photoless ticks, only from a finished subset list")
    void photolessTicks() {
        Map<String, Set<String>> photos = Map.of(FAMILY, Set.of("minecraft:husk"), MOST_WANTED, Set.of("minecraft:drowned"));
        List<String> ticked = List.of("minecraft:husk", "minecraft:drowned", "minecraft:zombie");
        assertEquals(List.of("minecraft:zombie"), SharedPhotoCriteria.photolessTicks(FAMILY, true, ticked, photos));
        assertEquals(List.of(), SharedPhotoCriteria.photolessTicks(FAMILY, false, ticked, photos), "not finished");
        assertEquals(List.of(), SharedPhotoCriteria.photolessTicks(MOST_WANTED, true, ticked, photos), "Most Wanted untouched");
        assertEquals(List.of(), SharedPhotoCriteria.photolessTicks(FAMILY, true,
                List.of("minecraft:husk", "minecraft:drowned"), photos), "every photo held");
    }
}
