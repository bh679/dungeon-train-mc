package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every string the shipped {@code translation_contexts.json} puts in the Item preview must be one
 * the preview can draw: an item's name (the registry builds its tooltip), or a tooltip line
 * {@link ItemTooltipRecorder} records once seen. Anything else opened on an empty slot.
 */
class TranslationContextsItemKeysTest {

    /** Tooltip lines DT adds to items; recorded the first time the item is hovered. */
    private static final Set<String> ITEM_TOOLTIP_LINES = Set.of(
        "gui.dungeontrain.prefab_delete.hint");

    @Test
    @DisplayName("keys declared \"item\" are item names or known item tooltip lines")
    void itemKeysCanBePreviewed() throws Exception {
        try (InputStream in = TranslationContextsItemKeysTest.class
                .getResourceAsStream("/assets/dungeontrain/translation_contexts.json")) {
            assertNotNull(in, "translation_contexts.json must ship");
            JsonObject json = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> unplaceable = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String key = entry.getKey();
                boolean item = entry.getValue().isJsonArray() && entry.getValue().getAsJsonArray()
                    .asList().stream().anyMatch(c -> "item".equals(c.getAsString()));
                boolean name = key.startsWith("item.") || key.startsWith("block.");
                if (item && !name && !ITEM_TOOLTIP_LINES.contains(key)) {
                    unplaceable.add(key);
                }
            }
            assertTrue(unplaceable.isEmpty(), "declared \"item\" but neither an item name nor a "
                + "known item tooltip line: " + unplaceable);
        }
    }
}
