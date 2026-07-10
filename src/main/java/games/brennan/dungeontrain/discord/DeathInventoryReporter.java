package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * POSTs the player's full hotbar + main inventory + offhand at the moment of death to the Dungeon
 * Train relay, so the data explorer's death-detail view can list everything the player was
 * carrying — not just the worn armor + main-hand item {@link DeathEquipmentReporter} already
 * covers (those 4 armor slots are skipped here to avoid duplicating that record).
 *
 * <p>Mirrors {@link DeathEquipmentReporter}: same {@link DungeonTrainConfig#isWorldInfoToRelay()}
 * gate reused rather than a new toggle, same no-throw hand-off to the durable {@link RelayOutbox}.
 * Fires from {@code RunStatsEvents.onPlayerDeath}, same as the equipment/detail reporters — the
 * keep-inventory gamerule and respawn both run AFTER {@code LivingDeathEvent}, so the inventory
 * still reflects what the player died carrying.</p>
 *
 * <p>Slot numbering: {@code 0-35} are the raw {@link Inventory} hotbar (0-8) + main (9-35)
 * indices; {@code 36} is a sentinel for the offhand slot (read independently via {@link
 * ServerPlayer#getItemBySlot}, matching how {@link DeathEquipmentReporter} reads main-hand).
 * Empty slots are omitted entirely — the payload size is proportional to what was actually
 * carried, not the fixed inventory size.</p>
 */
public final class DeathInventoryReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int HOTBAR_MAIN_SIZE = 36; // Inventory.items: hotbar(0-8) + main(9-35)
    private static final int OFFHAND_SLOT = 36;

    /** One resolved inventory slot: raw slot index + item id + count + optional leather dye color. */
    record InventoryItem(int slot, String id, int count, Integer color) {}

    private DeathInventoryReporter() {}

    /**
     * Build and fire the death-inventory record for {@code player}. No-op when disabled or on any
     * error — this must never disrupt death handling.
     */
    public static void report(ServerPlayer player) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            String uuid = player.getUUID().toString().replace("-", "");
            List<InventoryItem> resolved = new ArrayList<>();
            Inventory inv = player.getInventory();
            for (int i = 0; i < HOTBAR_MAIN_SIZE; i++) {
                InventoryItem item = resolve(i, inv.getItem(i));
                if (item != null) {
                    resolved.add(item);
                }
            }
            InventoryItem offhand = resolve(OFFHAND_SLOT, player.getItemBySlot(EquipmentSlot.OFFHAND));
            if (offhand != null) {
                resolved.add(offhand);
            }
            JsonObject payload = buildPayload(uuid, resolved);
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] death-inventory relay report failed: {}", t.toString());
        }
    }

    /** Resolve one slot to its plain-data shape, or {@code null} when empty. */
    private static InventoryItem resolve(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        return new InventoryItem(slot, id.toString(), stack.getCount(), dye != null ? dye.rgb() : null);
    }

    /**
     * Pure payload assembly over plain data — package-private so the shape can be unit-tested
     * without bootstrapping the game.
     */
    static JsonObject buildPayload(String uuid, List<InventoryItem> items) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        JsonArray arr = new JsonArray();
        for (InventoryItem item : items) {
            JsonObject o = new JsonObject();
            o.addProperty("slot", item.slot());
            o.addProperty("id", item.id());
            o.addProperty("count", item.count());
            if (item.color() != null) {
                o.addProperty("color", item.color());
            }
            arr.add(o);
        }
        body.add("items", arr);
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/death-inventory", json);
        LOGGER.debug("[DungeonTrain] death-inventory report for {} queued to the relay outbox.", uuid);
    }
}
