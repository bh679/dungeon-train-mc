package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Restock Villager, server side. Opening a villager's trade menu with the perk active sends the
 * charges left on that villager ({@link RestockVillagerStatusPacket}); the menu's Restock button
 * asks for one ({@link RestockVillagerRequestPacket}) and {@link #tryRestock} spends it on
 * vanilla's own {@link Villager#restock()}, which also raises demand-based prices and counts as
 * one of the villager's restocks for the day, exactly as a work-station restock would.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TraderPerkEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraderPerkEvents.class);

    /** How far to look for the villager a player is trading with; trading range is 8 blocks. */
    private static final double TRADER_SEARCH_RADIUS = 16.0;

    private TraderPerkEvents() {}

    @SubscribeEvent
    public static void onMenuOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getContainer() instanceof MerchantMenu menu)) return;
        try {
            int rank = Perks.rank(player, RestockVillager.ID);
            if (rank <= 0) return;
            tradingVillager(player).ifPresent(villager -> sendStatus(player, menu.containerId,
                RestockVillager.remaining(rank, RestockVillager.used(player, villager.getUUID()))));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Restock Villager status failed for {}", player.getName().getString(), e);
        }
    }

    /** Restocks the villager behind the player's open trade menu if the perk and a charge allow it. */
    static void tryRestock(ServerPlayer player, int containerId) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu) || menu.containerId != containerId) return;
            int rank = Perks.rank(player, RestockVillager.ID);
            if (rank <= 0) return;
            Optional<Villager> found = tradingVillager(player);
            if (found.isEmpty()) return;
            Villager villager = found.get();
            int used = RestockVillager.used(player, villager.getUUID());
            if (RestockVillager.remaining(rank, used) <= 0 || !RestockVillager.needsRestock(villager.getOffers())) return;

            villager.restock();
            int left = RestockVillager.remaining(rank, RestockVillager.recordUse(player, villager.getUUID()));
            sendStatus(player, containerId, left);
            LOGGER.debug("[DungeonTrain] Restock Villager: {} restocked {} ({} left this life)",
                player.getName().getString(), villager.getUUID(), left);
        } catch (RuntimeException e) {
            // Never let a perk break the trade screen; vanilla trading carries on.
            LOGGER.warn("[DungeonTrain] Restock Villager failed for {}", player.getName().getString(), e);
        }
    }

    private static Optional<Villager> tradingVillager(ServerPlayer player) {
        List<Villager> nearby = player.serverLevel().getEntitiesOfClass(Villager.class,
            player.getBoundingBox().inflate(TRADER_SEARCH_RADIUS),
            v -> v.isAlive() && v.getTradingPlayer() == player);
        return nearby.stream().findFirst();
    }

    private static void sendStatus(ServerPlayer player, int containerId, int remaining) {
        PacketDistributor.sendToPlayer(player, new RestockVillagerStatusPacket(containerId, remaining));
    }
}
