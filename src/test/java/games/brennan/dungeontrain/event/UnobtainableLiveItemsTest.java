package games.brennan.dungeontrain.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnobtainableLiveItemsTest {

    @Test
    @DisplayName("the Live Feed Cassette and the camcorder placeholder are unobtainable; the real headpiece is not")
    void unobtainableIds() {
        assertTrue(UnobtainableLiveItems.isUnobtainable(ResourceLocation.parse("dungeontrain:live_cassette")));
        assertTrue(UnobtainableLiveItems.isUnobtainable(ResourceLocation.parse("dungeontrain:random_live_headpiece")));
        assertFalse(UnobtainableLiveItems.isUnobtainable(ResourceLocation.parse("dungeontrain:live_headpiece")));
        assertFalse(UnobtainableLiveItems.isUnobtainable(ResourceLocation.parse("vista:cassette")));
        assertFalse(UnobtainableLiveItems.isUnobtainable((ResourceLocation) null));
    }

    @Test
    @DisplayName("both items are hidden from recipe viewers (JEI/EMI) through c:hidden_from_recipe_viewers")
    void hiddenFromRecipeViewers() throws IOException {
        Set<String> values = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(
                RepoPaths.resources().resolve("data/c/tags/item/hidden_from_recipe_viewers.json"))) {
            for (JsonElement value : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                values.add(value.getAsString());
            }
        }
        assertEquals(UnobtainableLiveItems.IDS, values);
    }
}
