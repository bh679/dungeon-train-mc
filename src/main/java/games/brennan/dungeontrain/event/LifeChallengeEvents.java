package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.LifeChallengeAdvancements;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Watches the habits {@link LifeChallengeAdvancements} rules out for a life: an apple or edible backpack
 * in the inventory (scanned once a second, so picking one up, crafting one or being given one all
 * count), eating anything but melon, and wearing armor other than an elytra.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LifeChallengeEvents {

    private static final int SCAN_INTERVAL_TICKS = 20;

    private LifeChallengeEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % SCAN_INTERVAL_TICKS != 0) return;
        if (player.getData(ModDataAttachments.HELD_APPLE_THIS_LIFE.get())) return;
        if (player.isSpectator() || !player.isAlive()) return;
        for (ItemStack stack : player.getInventory().items) {
            if (isAppleOrBackpack(stack)) { lostApple(player); return; }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isAppleOrBackpack(stack)) { lostApple(player); return; }
        }
    }

    @SubscribeEvent
    public static void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack eaten = event.getItem();
        if (eaten.getFoodProperties(player) == null) return; // drinks without food, bows, shields…
        if (!LifeChallengeAdvancements.breaksMelonDiet(id(eaten))) return;
        LifeChallengeAdvancements.markLost(player, ModDataAttachments.ATE_NON_MELON_THIS_LIFE,
            LifeChallengeAdvancements.MELON_TIERS);
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSlot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return;
        if (!countsAsArmor(event.getTo())) return;
        LifeChallengeAdvancements.markLost(player, ModDataAttachments.WORE_ARMOR_THIS_LIFE,
            LifeChallengeAdvancements.NAKED_TIERS);
    }

    /** Armor pieces count; an elytra, mob heads and carved pumpkins are not {@link ArmorItem}s. */
    static boolean countsAsArmor(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ArmorItem;
    }

    private static boolean isAppleOrBackpack(ItemStack stack) {
        return !stack.isEmpty() && LifeChallengeAdvancements.isAppleOrBackpack(id(stack));
    }

    private static void lostApple(ServerPlayer player) {
        LifeChallengeAdvancements.markLost(player, ModDataAttachments.HELD_APPLE_THIS_LIFE,
            LifeChallengeAdvancements.APPLE_TIERS);
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
