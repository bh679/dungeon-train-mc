package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillager;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerClientState;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Restock Villager's button on the villager trade menu, under the menu's bottom-right corner:
 * "Restock (N)" with the charges left on this villager. Hidden until the server says the perk is
 * active for this menu ({@link RestockVillagerClientState}), greyed out with no charges left or
 * nothing sold out. The server re-checks every press.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RestockVillagerButton {

    private static final String LABEL_KEY = "perk.dungeontrain.restock_villager.button";
    private static final int WIDTH = 80;
    private static final int HEIGHT = 20;
    private static final int GAP = 4;

    private RestockVillagerButton() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen screen)) return;
        MerchantMenu menu = screen.getMenu();
        int x = screen.getGuiLeft() + screen.getXSize() - WIDTH;
        int y = screen.getGuiTop() + screen.getYSize() + GAP;
        event.addListener(new RestockButton(x, y, menu));
    }

    private static final class RestockButton extends Button {

        private final MerchantMenu menu;

        RestockButton(int x, int y, MerchantMenu menu) {
            super(x, y, WIDTH, HEIGHT, Component.empty(),
                b -> PacketDistributor.sendToServer(new RestockVillagerRequestPacket(menu.containerId)),
                DEFAULT_NARRATION);
            this.menu = menu;
            this.visible = false;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = RestockVillagerClientState.remainingFor(menu.containerId);
            visible = left >= 0;
            if (!visible) return;
            active = left > 0 && RestockVillager.needsRestock(menu.getOffers());
            setMessage(Component.translatable(LABEL_KEY, left));
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }
    }
}
