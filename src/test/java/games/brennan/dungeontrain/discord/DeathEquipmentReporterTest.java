package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link DeathEquipmentReporter#buildPayload}. Empty slots must serialize as
 * JSON {@code null} (not omitted, not {@code "minecraft:air"}), non-empty slots as {@code {id}},
 * and dyed leather adds {@code color}. Pure — no running server or Minecraft bootstrap needed.
 */
class DeathEquipmentReporterTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    @DisplayName("empty slots serialize as JSON null")
    void emptySlotsAreNull() {
        JsonObject out = DeathEquipmentReporter.buildPayload(UUID, null, null, null, null, null);

        assertEquals(UUID, out.get("uuid").getAsString());
        assertTrue(out.get("armorHead").isJsonNull());
        assertTrue(out.get("armorChest").isJsonNull());
        assertTrue(out.get("armorLegs").isJsonNull());
        assertTrue(out.get("armorFeet").isJsonNull());
        assertTrue(out.get("mainHand").isJsonNull());
    }

    @Test
    @DisplayName("a non-empty, undyed slot serializes as {id} with no color field")
    void undyedSlot() {
        var chest = new DeathEquipmentReporter.EquippedItem("minecraft:diamond_chestplate", null);
        JsonObject out = DeathEquipmentReporter.buildPayload(UUID, null, chest, null, null, null);

        JsonObject slot = out.getAsJsonObject("armorChest");
        assertEquals("minecraft:diamond_chestplate", slot.get("id").getAsString());
        assertFalse(slot.has("color"), "undyed armor must not carry a color field");
    }

    @Test
    @DisplayName("dyed leather carries its color int alongside the id")
    void dyedLeatherSlot() {
        var boots = new DeathEquipmentReporter.EquippedItem("minecraft:leather_boots", 0xA06540);
        JsonObject out = DeathEquipmentReporter.buildPayload(UUID, null, null, null, boots, null);

        JsonObject slot = out.getAsJsonObject("armorFeet");
        assertEquals("minecraft:leather_boots", slot.get("id").getAsString());
        assertEquals(0xA06540, slot.get("color").getAsInt());
    }

    @Test
    @DisplayName("mainHand is independent of the armor slots")
    void mainHandSlot() {
        var sword = new DeathEquipmentReporter.EquippedItem("minecraft:netherite_sword", null);
        JsonObject out = DeathEquipmentReporter.buildPayload(UUID, null, null, null, null, sword);

        assertEquals("minecraft:netherite_sword", out.getAsJsonObject("mainHand").get("id").getAsString());
        assertTrue(out.get("armorHead").isJsonNull());
    }
}
