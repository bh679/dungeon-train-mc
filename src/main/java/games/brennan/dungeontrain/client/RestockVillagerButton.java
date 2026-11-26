package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.MerchantScreenSelectionAccessor;
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
 * of the "Trades" heading, or in its place when both show (the heading is then hidden): Restock Villager (a restock symbol) and then Reroll Trades (a die), each
 * with the charges left on this villager, Reroll also with its next price in emeralds or FREE. Kept that small so they never reach the villager's name,
 * centred over the right pane. Each draws nothing until the server says its perk is active for this
 * menu.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RestockVillagerButton {

    private static final String RESTOCK_LABEL_KEY = "perk.dungeontrain.restock_villager.button";
    private static final String REROLL_PAID_KEY = "perk.dungeontrain.reroll_trades.tooltip_paid";
    private static final String REROLL_FREE_KEY = "perk.dungeontrain.reroll_trades.tooltip_free";
    private static final String REROLL_NONE_KEY = "perk.dungeontrain.reroll_trades.tooltip_none";
    private static final String REROLL_FREE_LABEL_KEY = "perk.dungeontrain.reroll_trades.free";
    private static final String RESTOCK_ICON = "↻";
    private static final String REROLL_ICON = "⚄";
    /** The die's glyph is a tall filled box, so it is drawn nearer its own size than Restock's arrow. */
    private static final float REROLL_ICON_SCALE = 1.0F;
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
        int afterTitle = screen.getGuiLeft() + labelRight + TradeChargeButton.GAP;
        int titleCentre = screen.getGuiLeft() + TRADES_LABEL_CENTRE_X;
        int y = screen.getGuiTop() + Y;
        TradeChargeButton[] pair = new TradeChargeButton[2];
        // Both showing: the pair takes the hidden "Trades" heading's place, centred where it was (widths from
        // the last frame). One showing: it sits after the heading, as before.
        java.util.function.IntSupplier home = () -> hidesTradesTitle() && pair[1] != null
            ? titleCentre - (pair[0].getWidth() + TradeChargeButton.GAP + pair[1].getWidth()) / 2
            : afterTitle;
        TradeChargeButton restock = new TradeChargeButton(home, y, RESTOCK_ICON, TradeChargeButton.DEFAULT_ICON_SCALE,
            (left, price) -> Component.translatable(RESTOCK_LABEL_KEY, left),
            () -> RestockVillagerClientState.remainingFor(menu.containerId),
            () -> -1, Component.empty(),
            () -> RestockVillager.needsRestock(menu.getOffers()),
            () -> PacketDistributor.sendToServer(new RestockVillagerRequestPacket(menu.containerId)),
            null);
        TradeChargeButton reroll = new TradeChargeButton(() -> afterTitle, y, REROLL_ICON, REROLL_ICON_SCALE,
            (left, price) -> left <= 0 ? Component.translatable(REROLL_NONE_KEY)
                : price == 0 ? Component.translatable(REROLL_FREE_KEY, left)
                : Component.translatable(REROLL_PAID_KEY, left, price),
            () -> RerollTradesClientState.remainingFor(menu.containerId),
            () -> RerollTradesClientState.priceFor(menu.containerId),
            Component.translatable(REROLL_FREE_LABEL_KEY),
            () -> canPay(RerollTradesClientState.priceFor(menu.containerId)),
            () -> {
                // The rerolled list may be shorter; the server also moves its selection to the first trade.
                ((MerchantScreenSelectionAccessor) screen).dungeontrain$setShopItem(0);
                ((MerchantScreenSelectionAccessor) screen).dungeontrain$setScrollOff(0);
                PacketDistributor.sendToServer(new RerollTradesRequestPacket(menu.containerId));
            },
            restock);
        pair[0] = restock;
        pair[1] = reroll;
        event.addListener(restock);
        event.addListener(reroll);
    }

    /**
     * True while the open trade menu shows both Restock and Reroll: the "Trades" heading is then left out
     * ({@code MerchantScreenTradesTitleMixin}) and the two buttons sit where it was.
     */
    public static boolean hidesTradesTitle() {
        if (!(Minecraft.getInstance().screen instanceof MerchantScreen screen)) return false;
        int id = screen.getMenu().containerId;
        return RestockVillagerClientState.remainingFor(id) >= 0 && RerollTradesClientState.remainingFor(id) >= 0;
    }

    /** Whether the player's own inventory holds the emeralds for a reroll at {@code price}. */
    private static boolean canPay(int price) {
        if (price <= 0) return price == 0;
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        int have = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(net.minecraft.world.item.Items.EMERALD)) have += stack.getCount();
            if (have >= price) return true;
        }
        return false;
    }
}
