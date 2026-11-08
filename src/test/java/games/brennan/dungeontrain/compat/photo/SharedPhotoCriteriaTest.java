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
import java.util.Map;

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
    @DisplayName("Only the three mob collections are in the group")
    void groupMembership() {
        assertTrue(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("dungeontrain", "enchiridion/family_photo")));
        assertFalse(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("dungeontrain", "enchiridion/nature_documentary")));
        assertFalse(SharedPhotoCriteria.inGroup(ResourceLocation.fromNamespaceAndPath("other", "enchiridion/family_photo")));
    }
}
