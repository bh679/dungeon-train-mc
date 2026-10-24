package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trader perk hooks that live on events. Restock Villager: right-clicking a villager whose
 * trades have sold out resets every offer's uses before vanilla opens the screen, while the
 * player has charges left on that villager ({@link RestockVillager}). Vanilla's own
 * {@code Villager.restock()} is not used: it also raises demand prices and spends the villager's
 * daily restock, and this perk is meant to be a clean reset.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TraderPerkEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraderPerkEvents.class);
    private static final String RESTOCKED_KEY = "perk.dungeontrain.restock_villager.restocked";

    private TraderPerkEvents() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof Villager villager) || !villager.isAlive()) return;
        try {
            restockIfEarned(player, villager);
        } catch (RuntimeException e) {
            // Never let a perk break the trade screen; vanilla trading carries on.
            LOGGER.warn("[DungeonTrain] Restock Villager perk failed for {}", player.getName().getString(), e);
        }
    }

    private static void restockIfEarned(ServerPlayer player, Villager villager) {
        int rank = Perks.rank(player, RestockVillager.ID);
        if (rank <= 0) return;
        MerchantOffers offers = villager.getOffers();
        if (!RestockVillager.needsRestock(offers)) return;
        int used = RestockVillager.used(player, villager.getUUID());
        if (RestockVillager.remaining(rank, used) <= 0) return;

        offers.forEach(MerchantOffer::resetUses);
        int left = RestockVillager.remaining(rank, RestockVillager.recordUse(player, villager.getUUID()));
        player.displayClientMessage(Component.translatable(RESTOCKED_KEY, left).withStyle(ChatFormatting.GREEN), true);
        LOGGER.debug("[DungeonTrain] Restock Villager: {} restocked {} ({} left this life)",
            player.getName().getString(), villager.getUUID(), left);
    }
}
