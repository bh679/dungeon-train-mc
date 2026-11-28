package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.VillagerOfferRoll;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
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
 * left on that villager and the next price ({@link RerollTradesStatusPacket}); the menu's Reroll button asks for one
 * ({@link RerollTradesRequestPacket}) and {@link #tryReroll} takes the price in emeralds (none for a free one) and spends a reroll on a fresh
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
            TraderPerkEvents.tradingVillager(player).ifPresent(villager -> sendStatus(player, menu.containerId, rank, villager));
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
            int used = RerollTrades.used(player, villager.getUUID());
            if (RerollTrades.remaining(rank, used) <= 0) return;
            int price = RerollTrades.nextPrice(rank, used, RerollTrades.paid(player, villager.getUUID()));
            if (emeralds(player) < price) {
                sendStatus(player, containerId, rank, villager);
                return;
            }

            int level = villager.getVillagerData().getLevel();
            Optional<MerchantOffers> rolled = VillagerOfferRoll.roll(villager, player.serverLevel(), level, true);
            if (rolled.isEmpty() || rolled.get().isEmpty()) return;
            if (price > 0) takeEmeralds(player, price);
            villager.setOffers(rolled.get());
            restoreSessionRows(villager, player);
            // The old selection may be past the end of the new list: start again from the first trade.
            menu.setSelectionHint(0);
            menu.slotsChanged(menu.getSlot(0).container);
            RerollTrades.recordUse(player, villager.getUUID(), price > 0);
            player.sendMerchantOffers(containerId, villager.getOffers(), level, villager.getVillagerXp(),
                villager.showProgressBar(), villager.canRestock());
            sendStatus(player, containerId, rank, villager);
            LOGGER.debug("[DungeonTrain] Reroll Trades: {} rerolled {} for {} emeralds",
                player.getName().getString(), villager.getUUID(), price);
        } catch (RuntimeException e) {
            // Never let a perk break the trade screen; vanilla trading carries on.
            LOGGER.warn("[DungeonTrain] Reroll Trades failed for {}", player.getName().getString(), e);
        }
    }

    /**
     * Puts back the rows other mods add to the offer list for the length of a trading session. Trade
     * Everything prepends its "Trade Anything" row when {@code setTradingPlayer(player)} runs, into the
     * villager's real offers; a freshly rolled list has no such row and the session is already open, so it
     * would be gone until the menu is reopened. Setting the same player again re-runs that hook (vanilla
     * only stops trading on a change to null), and the mod builds its row exactly as it does on open.
     */
    private static void restoreSessionRows(Villager villager, ServerPlayer player) {
        villager.setTradingPlayer(player);
    }

    private static void sendStatus(ServerPlayer player, int containerId, int rank, Villager villager) {
        int used = RerollTrades.used(player, villager.getUUID());
        int left = RerollTrades.remaining(rank, used);
        int price = RerollTrades.nextPrice(rank, used, RerollTrades.paid(player, villager.getUUID()));
        PacketDistributor.sendToPlayer(player, new RerollTradesStatusPacket(containerId, left, price));
    }

    /** Emeralds in the player's inventory. */
    private static int emeralds(ServerPlayer player) {
        return player.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), 0,
            player.inventoryMenu.getCraftSlots());
    }

    private static void takeEmeralds(ServerPlayer player, int count) {
        player.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), count,
            player.inventoryMenu.getCraftSlots());
        player.getInventory().setChanged();
    }
}
