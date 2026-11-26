package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Totem Pole's checklist: found totems first, oldest find first, then the ones still to use in the advancement's
 * order; and every criterion is named for the Dungeon Totem it asks for, which is how the screen finds its item.
 */
class TotemPoleChecklistTest {

    private static ResourceLocation totem(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontotems", path);
    }

    @Test
    void foundTotemsComeFirstInOrderFound() {
        List<String> criteria = List.of("weakness_totem", "fatigue_totem", "rage_totem", "void_totem");
        Map<String, Instant> found = Map.of(
                "rage_totem", Instant.ofEpochSecond(100),
                "void_totem", Instant.ofEpochSecond(50));
        List<TotemPoleChecklist.Slot> slots = TotemPoleChecklist.slots(criteria, found::get);
        assertEquals(List.of(
                new TotemPoleChecklist.Slot(totem("void_totem"), true),
                new TotemPoleChecklist.Slot(totem("rage_totem"), true),
                new TotemPoleChecklist.Slot(totem("weakness_totem"), false),
                new TotemPoleChecklist.Slot(totem("fatigue_totem"), false)), slots);
    }

    @Test
    void sameTimeKeepsAdvancementOrder() {
        Instant at = Instant.ofEpochSecond(7);
        List<TotemPoleChecklist.Slot> slots = TotemPoleChecklist.slots(List.of("a_totem", "b_totem"), c -> at);
        assertEquals(List.of(totem("a_totem"), totem("b_totem")), slots.stream().map(TotemPoleChecklist.Slot::item).toList());
    }

    @Test
    void everyCriterionNamesItsTotem() throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(RepoPaths.root()
                .resolve("src/main/resources/data/dungeontrain/advancement/dungeon_train/totem_pole.json"))).getAsJsonObject();
        JsonObject criteria = json.getAsJsonObject("criteria");
        assertEquals(19, criteria.size());
        for (Map.Entry<String, JsonElement> e : criteria.entrySet()) {
            String item = e.getValue().getAsJsonObject().getAsJsonObject("conditions")
                    .getAsJsonObject("item").get("items").getAsString();
            assertEquals(TotemPoleChecklist.itemOf(e.getKey()).toString(), item, e.getKey());
        }
    }
}
