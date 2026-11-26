package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.VillagerOfferRoll;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Reroll Trades, server side. Opening a villager's trade menu with the perk active sends the charges
 * left on that villager ({@link RerollTradesStatusPacket}); the menu's Reroll button asks for one
 * ({@link RerollTradesRequestPacket}) and {@link #tryReroll} spends it on a fresh
 * {@link VillagerOfferRoll} at the villager's current level, then refreshes the open menu.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RerollTradesEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(RerollTradesEvents.class);

    private RerollTradesEvents() {}

    @SubscribeEvent
    public static void onMenuOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getContainer() instanceof MerchantMenu menu)) return;
        try {
            int rank = Perks.rank(player, RerollTrades.ID);
            if (rank <= 0) return;
            TraderPerkEvents.tradingVillager(player).ifPresent(villager -> sendStatus(player, menu.containerId,
                RerollTrades.remaining(rank, RerollTrades.used(player, villager.getUUID()))));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Reroll Trades status failed for {}", player.getName().getString(), e);
        }
    }

    /** Rerolls the villager behind the player's open trade menu if the perk and a charge allow it. */
    static void tryReroll(ServerPlayer player, int containerId) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu) || menu.containerId != containerId) return;
            int rank = Perks.rank(player, RerollTrades.ID);
            if (rank <= 0) return;
            Optional<Villager> found = TraderPerkEvents.tradingVillager(player);
            if (found.isEmpty()) return;
            Villager villager = found.get();
            if (RerollTrades.remaining(rank, RerollTrades.used(player, villager.getUUID())) <= 0) return;

            int level = villager.getVillagerData().getLevel();
            Optional<MerchantOffers> rolled = VillagerOfferRoll.roll(villager, player.serverLevel(), level, true);
            if (rolled.isEmpty() || rolled.get().isEmpty()) return;
            villager.setOffers(rolled.get());
            int left = RerollTrades.remaining(rank, RerollTrades.recordUse(player, villager.getUUID()));
            player.sendMerchantOffers(containerId, villager.getOffers(), level, villager.getVillagerXp(),
                villager.showProgressBar(), villager.canRestock());
            sendStatus(player, containerId, left);
            LOGGER.debug("[DungeonTrain] Reroll Trades: {} rerolled {} ({} left this life)",
                player.getName().getString(), villager.getUUID(), left);
        } catch (RuntimeException e) {
            // Never let a perk break the trade screen; vanilla trading carries on.
            LOGGER.warn("[DungeonTrain] Reroll Trades failed for {}", player.getName().getString(), e);
        }
    }

    private static void sendStatus(ServerPlayer player, int containerId, int remaining) {
        PacketDistributor.sendToPlayer(player, new RerollTradesStatusPacket(containerId, remaining));
    }
}
