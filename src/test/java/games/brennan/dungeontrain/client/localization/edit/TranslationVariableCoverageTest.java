package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped examples file against the shipped English: every placeholder a translator can hover
 * has something to say, and nothing in the file describes a slot that does not exist.
 *
 * <p>This is the guard that keeps the data from rotting. A new string with a {@code %s} in it fails
 * here until somebody writes down what that {@code %s} holds — which is the only moment anyone
 * still remembers.</p>
 */
class TranslationVariableCoverageTest {

    private static final Path LANG =
        Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");
    private static final Path EXAMPLES =
        Path.of("src/main/resources/assets/dungeontrain/translation_examples.json");

    private static Map<String, Map<Integer, TranslationVariableExamples.Entry>> examples()
            throws IOException {
        try (Reader reader = Files.newBufferedReader(RepoPaths.root().resolve(EXAMPLES),
                StandardCharsets.UTF_8)) {
            return TranslationVariableExamples.parse(reader);
        }
    }

    private static JsonObject english() throws IOException {
        try (Reader reader = Files.newBufferedReader(RepoPaths.root().resolve(LANG),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("every English string with a placeholder has a curated entry for every slot")
    void everyPlaceholderIsCovered() throws IOException {
        var curated = examples();
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : english().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            int slots = TranslationVariableScanner.slotCount(entry.getValue().getAsString());
            if (slots == 0) {
                continue;
            }
            Map<Integer, TranslationVariableExamples.Entry> forKey = curated.get(entry.getKey());
            if (forKey == null) {
                gaps.add(entry.getKey() + " (no entry; " + slots + " slot(s))");
                continue;
            }
            for (int slot = 1; slot <= slots; slot++) {
                if (!forKey.containsKey(slot)) {
                    gaps.add(entry.getKey() + " slot " + slot);
                }
            }
        }
        assertTrue(gaps.isEmpty(),
            "translation_examples.json is missing entries — add them, then re-run:\n  "
                + String.join("\n  ", gaps));
    }

    @Test
    @DisplayName("the examples file names no key or slot the English does not have")
    void nothingCuratedIsStale() throws IOException {
        JsonObject english = english();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, TranslationVariableExamples.Entry>> entry
                : examples().entrySet()) {
            JsonElement source = english.get(entry.getKey());
            if (source == null || !source.isJsonPrimitive()) {
                stale.add(entry.getKey() + " (not in en_us.json)");
                continue;
            }
            int slots = TranslationVariableScanner.slotCount(source.getAsString());
            for (int slot : entry.getValue().keySet()) {
                if (slot > slots) {
                    stale.add(entry.getKey() + " slot " + slot + " (string has " + slots + ")");
                }
            }
        }
        assertTrue(stale.isEmpty(),
            "translation_examples.json describes strings that changed:\n  "
                + String.join("\n  ", stale));
    }

    @Test
    @DisplayName("every curated entry actually says something — a label, and examples")
    void entriesAreUseful() throws IOException {
        List<String> empty = new ArrayList<>();
        for (var key : examples().entrySet()) {
            for (var slot : key.getValue().entrySet()) {
                if (slot.getValue().label().isBlank() || slot.getValue().examples().isEmpty()) {
                    empty.add(key.getKey() + " slot " + slot.getKey());
                }
            }
        }
        assertTrue(empty.isEmpty(), "entries with no label or no examples:\n  "
            + String.join("\n  ", empty));
    }

    @Test
    @DisplayName("the file covers the strings this feature was built for")
    void coversTheWorkedExample() throws IOException {
        var slots = examples().get("chat.dungeontrain.familiar_book.4");
        assertEquals(2, slots.size());
        assertTrue(slots.get(2).label().contains("reading time"));
    }
}
