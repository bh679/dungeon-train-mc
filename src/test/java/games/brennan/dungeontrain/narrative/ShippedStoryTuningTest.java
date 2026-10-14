package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped lectern serving order: which built-in series are held back, the Aaro family
 * chain (Pip → Soren → Della → Tomas), and the start-pick weights. A typo in an {@code after} id is
 * silently ignored at runtime (the chain would just fall apart), so this guards the links resolve.
 */
final class ShippedStoryTuningTest {

    private static Map<String, StoryFile> shipped() throws Exception {
        Path dir = RepoPaths.root().resolve("src/main/resources/data/dungeontrain/narratives/stories");
        Map<String, StoryFile> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String name = p.getFileName().toString().replace(".json", "");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "dungeontrain", "narratives/stories/" + name);
                try (InputStream in = Files.newInputStream(p)) {
                    out.put(name, StoryCodec.parse(in, id));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("exactly the five intended series are held back")
    void heldBackSet() throws Exception {
        Set<String> deferred = new TreeSet<>();
        shipped().forEach((name, story) -> { if (story.deferred()) deferred.add(name); });
        assertEquals(Set.of(
            "edda_marsh_the_wither_at_the_window",
            "the_querys_and_life_of_fourteen",
            "wren_halloway_rebuttals",
            "weakness_then_gold",
            "the_letters_of_madame_ulster_the_answerless_prophet"), deferred);
    }

    @Test
    @DisplayName("the Aaro chain links resolve, stay ordinary, and share weight 20")
    void aaroChain() throws Exception {
        Map<String, StoryFile> stories = shipped();
        String[] chain = {
            "pip_aaro_the_waiting_child",
            "soren_the_keeper_of_the_door",
            "della_aaro_the_searching_mother",
            "tomas_aaro_the_walking_father"};
        for (int i = 0; i < chain.length; i++) {
            StoryFile story = stories.get(chain[i]);
            assertEquals(i == 0 ? null : chain[i - 1], story.after(), chain[i]);
            assertEquals(20.0, story.weight(), chain[i]);
            assertTrue(!story.deferred(), chain[i] + " must come before the deferred tier");
        }
        stories.forEach((name, story) -> {
            if (story.after() != null) {
                assertTrue(stories.containsKey(story.after()), name + " names unknown after: " + story.after());
            }
        });
    }

    @Test
    @DisplayName("Jay is weighted 4, Augustus 2, every other unchained series 1")
    void weights() throws Exception {
        Map<String, StoryFile> stories = shipped();
        assertEquals(4.0, stories.get("jay_kuruvilla_the_time_traveler").weight());
        assertEquals(2.0, stories.get("augustus_park").weight());
        Set<String> tuned = Set.of("jay_kuruvilla_the_time_traveler", "augustus_park",
            "pip_aaro_the_waiting_child", "soren_the_keeper_of_the_door",
            "della_aaro_the_searching_mother", "tomas_aaro_the_walking_father");
        stories.forEach((name, story) -> {
            if (!tuned.contains(name)) assertEquals(1.0, story.weight(), name);
        });
    }
}
