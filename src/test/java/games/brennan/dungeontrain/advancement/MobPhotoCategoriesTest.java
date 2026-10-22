package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The category table behind the generated mob photo collections
 * ({@code scripts/advancements/mob-photos/categories.json}). Vanilla mobs are checked against the
 * game itself — a monster is hostile, anything else alive is an animal — so only a modded mob's
 * line is a judgement call. The generated collections hold exactly their category's ids.
 */
class MobPhotoCategoriesTest {

    private static final String MOST_WANTED = "enchiridion/most_wanted.json";
    private static final String NATURE_DOCUMENTARY = "enchiridion/nature_documentary.json";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject json(java.nio.file.Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject categories() throws IOException {
        return json(RepoPaths.root().resolve("scripts/advancements/mob-photos/categories.json"))
                .getAsJsonObject("categories");
    }

    @Test
    @DisplayName("vanilla hostile/animal calls match the game's mob category")
    void vanillaMatchesGame() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : categories().entrySet()) {
            ResourceLocation id = ResourceLocation.parse(e.getKey());
            String category = e.getValue().getAsString();
            if (!"minecraft".equals(id.getNamespace()) || "skip".equals(category)) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type == null) {
                wrong.add(id + " is not a vanilla entity type");
                continue;
            }
            boolean monster = type.getCategory() == MobCategory.MONSTER;
            if (monster != "hostile".equals(category)) wrong.add(id + " is " + type.getCategory() + " but listed " + category);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    @DisplayName("each collection holds only ids of its own category")
    void collectionsMatchCategories() throws IOException {
        JsonObject categories = categories();
        Map<String, String> collections = Map.of(MOST_WANTED, "hostile", NATURE_DOCUMENTARY, "animal");
        for (Map.Entry<String, String> c : collections.entrySet()) {
            JsonObject advancement = json(RepoPaths.advancements().resolve(c.getKey()));
            for (Map.Entry<String, JsonElement> criterion : advancement.getAsJsonObject("criteria").entrySet()) {
                JsonElement category = categories.get(criterion.getKey());
                assertTrue(category != null, criterion.getKey() + " in " + c.getKey() + " has no category");
                assertEquals(c.getValue(), category.getAsString(), criterion.getKey() + " in " + c.getKey());
                assertEquals("entity:" + criterion.getKey(), criterion.getValue().getAsJsonObject()
                        .getAsJsonObject("conditions").get("subject").getAsString());
            }
        }
    }
}
