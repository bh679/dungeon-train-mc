package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The datapack rewrite: override lands on the criterion, the description grows a {@code with}
 * argument, nothing else is touched, and the input is never mutated.
 */
final class RequirementJsonRewriterTest {

    private static final String MILESTONE = """
        {
          "parent": "dungeontrain:dungeon_train/carts_100",
          "display": {
            "icon": { "id": "minecraft:chest_minecart" },
            "title": { "translate": "advancements.dungeontrain.dungeon_train.carts_1000.title" },
            "description": { "translate": "advancements.dungeontrain.dungeon_train.carts_1000.description" },
            "frame": "goal"
          },
          "criteria": {
            "milestone": { "trigger": "dungeontrain:carts_in_run", "conditions": { "threshold": 1000 } }
          },
          "requirements": [["milestone"]]
        }""";

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("No override: threshold kept, description gains the shipped value as its argument")
    void shippedValueBecomesArgument() {
        JsonObject in = obj(MILESTONE);
        RequirementJsonRewriter.Rewritten r = RequirementJsonRewriter.rewrite(in, null);
        assertEquals(1000, r.json().getAsJsonObject("criteria").getAsJsonObject("milestone")
            .getAsJsonObject("conditions").get("threshold").getAsLong());
        JsonObject desc = r.json().getAsJsonObject("display").getAsJsonObject("description");
        assertEquals("1,000", desc.getAsJsonArray("with").get(0).getAsString());
        assertTrue(r.requirement().isPresent());
        assertEquals(1000, r.requirement().get().shipped());
        assertEquals(1000, r.requirement().get().effective());
        assertFalse(r.requirement().get().overridden());
        // Input untouched.
        assertFalse(in.getAsJsonObject("display").getAsJsonObject("description").has("with"));
    }

    @Test
    @DisplayName("Override replaces the threshold and the argument, and reports both values")
    void overrideApplied() {
        RequirementJsonRewriter.Rewritten r = RequirementJsonRewriter.rewrite(obj(MILESTONE), 500L);
        assertEquals(500, r.json().getAsJsonObject("criteria").getAsJsonObject("milestone")
            .getAsJsonObject("conditions").get("threshold").getAsLong());
        assertEquals("500", r.json().getAsJsonObject("display").getAsJsonObject("description")
            .getAsJsonArray("with").get(0).getAsString());
        assertEquals("milestone", r.requirement().get().criterion());
        assertEquals(RequirementField.THRESHOLD, r.requirement().get().field());
        assertEquals(1000, r.requirement().get().shipped());
        assertEquals(500, r.requirement().get().effective());
        assertTrue(r.requirement().get().overridden());
    }

    @Test
    @DisplayName("Tick requirements render as a nested plural translatable in hours or days")
    void ticksBecomeDuration() {
        String json = MILESTONE.replace("\"threshold\": 1000", "\"thresholdTicks\": 144000");
        JsonObject desc = RequirementJsonRewriter.rewrite(obj(json), null).json()
            .getAsJsonObject("display").getAsJsonObject("description");
        JsonObject arg = desc.getAsJsonArray("with").get(0).getAsJsonObject();
        assertEquals(RequirementField.HOURS_KEY + ".some", arg.get("translate").getAsString());
        assertEquals("2", arg.getAsJsonArray("with").get(0).getAsString());

        JsonObject one = RequirementJsonRewriter.rewrite(obj(json), 72000L).json()
            .getAsJsonObject("display").getAsJsonObject("description")
            .getAsJsonArray("with").get(0).getAsJsonObject();
        assertEquals(RequirementField.HOURS_KEY + ".single", one.get("translate").getAsString());

        JsonObject days = RequirementJsonRewriter.rewrite(obj(json), 5_184_000L).json()
            .getAsJsonObject("display").getAsJsonObject("description")
            .getAsJsonArray("with").get(0).getAsJsonObject();
        assertEquals(RequirementField.DAYS_KEY + ".some", days.get("translate").getAsString());
        assertEquals("3", days.getAsJsonArray("with").get(0).getAsString());
    }

    @Test
    @DisplayName("An advancement without a requirement field passes through as the same object")
    void noRequirementPassesThrough() {
        JsonObject in = obj(MILESTONE.replace("\"conditions\": { \"threshold\": 1000 }",
            "\"conditions\": { \"actionId\": \"the_upside_down\" }"));
        RequirementJsonRewriter.Rewritten r = RequirementJsonRewriter.rewrite(in, 5L);
        assertSame(in, r.json());
        assertTrue(r.requirement().isEmpty());
    }

    @Test
    @DisplayName("A description that already carries `with`, or is a literal, is left alone")
    void descriptionNotForced() {
        JsonObject withArgs = obj(MILESTONE.replace(
            "\"description\": { \"translate\": \"advancements.dungeontrain.dungeon_train.carts_1000.description\" }",
            "\"description\": { \"translate\": \"x\", \"with\": [\"y\"] }"));
        JsonObject desc = RequirementJsonRewriter.rewrite(withArgs, null).json()
            .getAsJsonObject("display").getAsJsonObject("description");
        assertEquals("y", desc.getAsJsonArray("with").get(0).getAsString());

        JsonObject literal = obj(MILESTONE.replace(
            "\"description\": { \"translate\": \"advancements.dungeontrain.dungeon_train.carts_1000.description\" }",
            "\"description\": \"plain\""));
        assertEquals("plain", RequirementJsonRewriter.rewrite(literal, null).json()
            .getAsJsonObject("display").get("description").getAsString());
    }

    @Test
    @DisplayName("rewriteAll only touches our tab, keeps order, and fills the registry")
    void rewriteAllScopesAndRegisters() {
        ResourceLocation ours = ResourceLocation.parse("dungeontrain:dungeon_train/carts_1000");
        ResourceLocation editor = ResourceLocation.parse("dungeontrain:editor/used_tunnel_stairs");
        ResourceLocation vanilla = ResourceLocation.parse("minecraft:story/root");
        Map<ResourceLocation, JsonElement> loaded = new LinkedHashMap<>();
        loaded.put(vanilla, obj(MILESTONE));
        loaded.put(editor, obj(MILESTONE));
        loaded.put(ours, obj(MILESTONE));
        Map<ResourceLocation, JsonElement> out = RequirementJsonRewriter.rewriteAll(
            loaded, Map.of(ours, 250L, vanilla, 7L), "dungeontrain");
        assertEquals(loaded.keySet().stream().toList(), out.keySet().stream().toList());
        assertSame(loaded.get(vanilla), out.get(vanilla));
        assertSame(loaded.get(editor), out.get(editor));
        assertEquals(250, out.get(ours).getAsJsonObject().getAsJsonObject("criteria")
            .getAsJsonObject("milestone").getAsJsonObject("conditions").get("threshold").getAsLong());
        assertEquals(250, AdvancementRequirements.value(ours, -1));
        assertEquals(-1, AdvancementRequirements.value(vanilla, -1));
        assertEquals(1, AdvancementRequirements.snapshot().size());
        AdvancementRequirements.clear();
        assertEquals(-1, AdvancementRequirements.value(ours, -1));
    }
}
