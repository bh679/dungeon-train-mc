package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.trader.RerollTradesClientState;
import games.brennan.dungeontrain.perks.effects.trader.RerollTradesRequestPacket;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillager;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerClientState;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Trader perks' buttons on the villager trade menu, compact {@link TradeChargeButton}s just right
 * of the "Trades" heading: Restock Villager (a restock symbol) and then Reroll Trades (a die), each
 * with the charges left on this villager. Kept that small so they never reach the villager's name,
 * centred over the right pane. Each draws nothing until the server says its perk is active for this
 * menu.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RestockVillagerButton {

    private static final String RESTOCK_LABEL_KEY = "perk.dungeontrain.restock_villager.button";
    private static final String REROLL_LABEL_KEY = "perk.dungeontrain.reroll_trades.button";
    private static final String RESTOCK_ICON = "↻";
    private static final String REROLL_ICON = "⚄";
    /** Vanilla draws "Trades" centred on x 53 of the menu; the buttons start just past it. */
    private static final int TRADES_LABEL_CENTRE_X = 53;
    private static final int Y = 4;

    private RestockVillagerButton() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen screen)) return;
        MerchantMenu menu = screen.getMenu();
        Font font = Minecraft.getInstance().font;
        int labelRight = TRADES_LABEL_CENTRE_X + font.width(Component.translatable("merchant.trades")) / 2;
        int x = screen.getGuiLeft() + labelRight + TradeChargeButton.GAP;
        int y = screen.getGuiTop() + Y;
        TradeChargeButton restock = new TradeChargeButton(x, y, RESTOCK_ICON, RESTOCK_LABEL_KEY,
            () -> RestockVillagerClientState.remainingFor(menu.containerId),
            () -> RestockVillager.needsRestock(menu.getOffers()),
            () -> PacketDistributor.sendToServer(new RestockVillagerRequestPacket(menu.containerId)),
            null);
        TradeChargeButton reroll = new TradeChargeButton(x, y, REROLL_ICON, REROLL_LABEL_KEY,
            () -> RerollTradesClientState.remainingFor(menu.containerId),
            () -> true,
            () -> PacketDistributor.sendToServer(new RerollTradesRequestPacket(menu.containerId)),
            restock);
        event.addListener(restock);
        event.addListener(reroll);
    }
}
