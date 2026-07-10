package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link DeathInventoryReporter#buildPayload}. Only non-empty slots are ever
 * passed in (the reporter itself skips empty stacks before building the list) — these tests check
 * the JSON shape each {@link DeathInventoryReporter.InventoryItem} serializes to. Pure — no
 * running server or Minecraft bootstrap needed.
 */
class DeathInventoryReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("empty item list serializes as an empty JSON array")
    void emptyList() {
        JsonObject out = DeathInventoryReporter.buildPayload(UUID, List.of());

        assertEquals(UUID, out.get("uuid").getAsString());
        assertTrue(out.getAsJsonArray("items").isEmpty());
    }

    @Test
    @DisplayName("an undyed slot serializes as {slot, id, count} with no color field")
    void undyedSlot() {
        var pick = new DeathInventoryReporter.InventoryItem(9, "minecraft:diamond_pickaxe", 1, null);
        JsonObject out = DeathInventoryReporter.buildPayload(UUID, List.of(pick));

        JsonArray items = out.getAsJsonArray("items");
        assertEquals(1, items.size());
        JsonObject slot = items.get(0).getAsJsonObject();
        assertEquals(9, slot.get("slot").getAsInt());
        assertEquals("minecraft:diamond_pickaxe", slot.get("id").getAsString());
        assertEquals(1, slot.get("count").getAsInt());
        assertFalse(slot.has("color"), "undyed item must not carry a color field");
    }

    @Test
    @DisplayName("dyed leather carries its color int alongside the id, and stack count > 1 round-trips")
    void dyedStackedSlot() {
        var shulker = new DeathInventoryReporter.InventoryItem(20, "minecraft:leather", 12, 0xA06540);
        JsonObject out = DeathInventoryReporter.buildPayload(UUID, List.of(shulker));

        JsonObject slot = out.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(20, slot.get("slot").getAsInt());
        assertEquals(12, slot.get("count").getAsInt());
        assertEquals(0xA06540, slot.get("color").getAsInt());
    }

    @Test
    @DisplayName("the offhand sentinel slot (36) round-trips like any other slot")
    void offhandSentinel() {
        var totem = new DeathInventoryReporter.InventoryItem(36, "minecraft:totem_of_undying", 1, null);
        JsonObject out = DeathInventoryReporter.buildPayload(UUID, List.of(totem));

        assertEquals(36, out.getAsJsonArray("items").get(0).getAsJsonObject().get("slot").getAsInt());
    }

    @Test
    @DisplayName("multiple slots preserve insertion order")
    void multipleSlotsOrdered() {
        var a = new DeathInventoryReporter.InventoryItem(0, "minecraft:torch", 64, null);
        var b = new DeathInventoryReporter.InventoryItem(1, "minecraft:cobblestone", 32, null);
        JsonObject out = DeathInventoryReporter.buildPayload(UUID, List.of(a, b));

        JsonArray items = out.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("minecraft:torch", items.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:cobblestone", items.get(1).getAsJsonObject().get("id").getAsString());
    }
}
