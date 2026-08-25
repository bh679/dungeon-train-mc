package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.ediblebackpacks.config.EBConfig;
import games.brennan.ediblebackpacks.item.EdibleBackpackItem;
import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.registry.ModItems;
import games.brennan.ediblebackpacks.storage.BackpackPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Awards the Edible Backpacks chain — {@code ate_edible_backpack} ("Apples?"),
 * {@code crafted_upgraded_backpack} ("Hungry for Apples") and
 * {@code maxed_backpack_slots} ("Got Apples").
 *
 * <p>Edible Backpacks is jarJar'd inside this mod (see {@code build.gradle} /
 * {@code neoforge.mods.toml}), so its classes are always present — the same
 * direct-reference assumption {@link games.brennan.dungeontrain.DungeonTrain}
 * makes when it calls {@code EdibleBackpacksApi} at setup and
 * {@code ContainerContentsRoller} makes when it rolls a backpack into train
 * loot.</p>
 *
 * <p>All three are one-shot markers fired through the shared
 * {@link games.brennan.dungeontrain.advancement.GameplayActionTrigger}; vanilla
 * advancement dedupe makes re-fires harmless, so no per-player bookkeeping is
 * kept here.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BackpackAdvancementEvents {

    private BackpackAdvancementEvents() {}

    /**
     * Eating any edible backpack (plain or upgraded) awards "Apples?", and
     * awards "Got Apples" when this bite leaves the player at the slot cap.
     *
     * <p>The cap test is written to hold regardless of whether this event runs
     * before or after {@code EdibleBackpackItem.finishUsingItem} has applied
     * the grant: either the player is already at the cap, or adding what this
     * backpack is worth reaches it.</p>
     */
    @SubscribeEvent
    public static void onUseItemFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getItem().getItem() instanceof EdibleBackpackItem backpack)) return;

        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "ate_edible_backpack");

        int cap = EBConfig.maxSlots();
        if (cap <= 0) return; // slots disabled by config — "maxed" would be meaningless
        int unlocked = player.getData(ModAttachments.BACKPACK).unlocked();
        boolean atCap = unlocked >= cap
            || unlocked + BackpackPolicy.effectiveGrant(unlocked, backpack.slotsGranted(), cap) >= cap;
        if (atCap) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "maxed_backpack_slots");
        }
    }

    /** Crafting the 3x3 compressed backpack awards "Hungry for Apples". */
    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getCrafting().is(ModItems.UPGRADED_BACKPACK.get())) return;
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "crafted_upgraded_backpack");
    }
}
