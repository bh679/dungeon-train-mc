package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faulthurst's Favourite carries one {@code random_book_variant_seen} criterion per variant of every
 * Faulthurst random-book (what its tooltip progress bar counts), each in its own requirement group
 * beside {@code completed} (so players who earned it before the bar stay earned). A new Faulthurst
 * book or variant fails here until the advancement gains its criterion.
 */
class FaulthurstFavouriteCriteriaTest {

    private static final Path DATA = RepoPaths.root().resolve("src/main/resources/data/dungeontrain");

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    /** {@code <basename>/<index>} for every variant of every shipped Faulthurst random-book. */
    private static Set<String> expectedCriteria() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(DATA.resolve("narratives/random_books"))) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") || !name.toLowerCase().contains("faulthurst")) continue;
                String basename = name.substring(0, name.length() - ".json".length());
                int variants = json(file).getAsJsonArray("variants").size();
                for (int i = 0; i < variants; i++) out.add(basename + "/" + i);
            }
        }
        return out;
    }

    private static JsonObject advancement() throws IOException {
        return json(DATA.resolve("advancement/dungeon_train/faulthursts_favourite.json"));
    }

    @Test
    void everyVariantHasItsCriterion() throws IOException {
        Set<String> expected = expectedCriteria();
        assertTrue(expected.size() > 1, "no Faulthurst random-books found");
        JsonObject criteria = advancement().getAsJsonObject("criteria");

        Set<String> actual = new LinkedHashSet<>(criteria.keySet());
        actual.remove("completed");
        assertEquals(expected, actual, "faulthursts_favourite.json criteria must match the Faulthurst variants");

        for (String key : expected) {
            JsonObject criterion = criteria.getAsJsonObject(key);
            assertEquals("dungeontrain:random_book_variant_seen", criterion.get("trigger").getAsString(), key);
            JsonObject conditions = criterion.getAsJsonObject("conditions");
            int slash = key.lastIndexOf('/');
            assertEquals(key.substring(0, slash), conditions.get("book").getAsString(), key);
            assertEquals(Integer.parseInt(key.substring(slash + 1)), conditions.get("variant").getAsInt(), key);
        }
    }

    @Test
    void eachVariantIsItsOwnGroupBesideCompleted() throws IOException {
        JsonObject adv = advancement();
        assertEquals("story_set_completed",
                adv.getAsJsonObject("criteria").getAsJsonObject("completed").get("trigger").getAsString()
                        .replace("dungeontrain:", ""));

        List<String> groups = new ArrayList<>();
        for (JsonElement group : adv.getAsJsonArray("requirements")) {
            JsonArray names = group.getAsJsonArray();
            assertEquals(2, names.size(), group.toString());
            assertEquals("completed", names.get(0).getAsString(), group.toString());
            groups.add(names.get(1).getAsString());
        }
        assertEquals(List.copyOf(expectedCriteria()), groups);
    }
}
