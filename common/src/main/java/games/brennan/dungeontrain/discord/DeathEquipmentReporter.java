package games.brennan.dungeontrain.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;

/**
 * POSTs the armor + main-hand item a player was wearing/holding at the moment of death to the
 * Dungeon Train relay, so the private data explorer's player-card view (dp-relay) can render each
 * player's last-death loadout on their 3D model.
 *
 * <p>Mirrors {@link WorldInfoReporter}: same destination (the relay) and the same
 * {@link DungeonTrainConfig#isWorldInfoToRelay()} gate reused rather than a new toggle, same no-throw
 * hand-off to the durable {@link RelayOutbox} (persisted, delivered at-least-once on the next flush).
 * Fires from {@code RunStatsEvents.onPlayerDeath} instead of on join.</p>
 *
 * <p>Armor comes straight from the same {@code DeathStatsPacket} slots the death screen uses
 * (captured there before respawn/keep-inventory clear them — see
 * {@code RunStatsEvents.buildPacket}). The main-hand item is read independently here rather than
 * reusing {@code DeathStatsPacket#mostUsedWeapon}, which is a whole-run aggregate stat, not
 * literally what was in-hand at the moment of death.</p>
 */
public final class DeathEquipmentReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One resolved equipment slot: item id + optional leather dye color. Null means empty. */
    record EquippedItem(String id, Integer color) {}

    private DeathEquipmentReporter() {}

    /**
     * Build and fire the death-equipment record for {@code player}. No-op when disabled or on any
     * error — this must never disrupt death handling. {@code armorHead/Chest/Legs/Feet} should be
     * the same stacks snapshotted for the death screen; the main-hand item is read fresh here.
     */
    public static void report(ServerPlayer player, ItemStack armorHead, ItemStack armorChest,
                              ItemStack armorLegs, ItemStack armorFeet) {
        try {
            if (!DungeonTrainConfig.isWorldInfoToRelay()) {
                return;
            }
            ItemStack mainHand = player.getItemBySlot(EquipmentSlot.MAINHAND);
            String uuid = player.getUUID().toString().replace("-", "");
            JsonObject payload = buildPayload(uuid,
                    resolve(armorHead), resolve(armorChest), resolve(armorLegs), resolve(armorFeet),
                    resolve(mainHand));
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] death-equipment relay report failed: {}", t.toString());
        }
    }

    /** Resolve an {@link ItemStack} slot to its plain-data shape, or {@code null} when empty. */
    private static EquippedItem resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        return new EquippedItem(id.toString(), dye != null ? dye.rgb() : null);
    }

    /**
     * Pure payload assembly over plain data (no Minecraft registry types) — package-private so the
     * shape can be unit-tested without bootstrapping the game. Each slot is {@code {id, color?}}
     * (color only present on dyed leather) or JSON {@code null} when the slot is empty.
     */
    static JsonObject buildPayload(String uuid, EquippedItem head, EquippedItem chest,
                                   EquippedItem legs, EquippedItem feet, EquippedItem mainHand) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.add("armorHead", slot(head));
        body.add("armorChest", slot(chest));
        body.add("armorLegs", slot(legs));
        body.add("armorFeet", slot(feet));
        body.add("mainHand", slot(mainHand));
        return body;
    }

    private static JsonElement slot(EquippedItem item) {
        if (item == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", item.id());
        if (item.color() != null) {
            o.addProperty("color", item.color());
        }
        return o;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/telemetry/death-equipment", json);
        LOGGER.debug("[DungeonTrain] death-equipment report for {} queued to the relay outbox.", uuid);
    }
}
