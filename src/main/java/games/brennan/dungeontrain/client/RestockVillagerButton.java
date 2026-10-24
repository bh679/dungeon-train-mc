package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillager;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerClientState;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillagerRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Restock Villager's button on the villager trade menu, a compact button just right of the
 * "Trades" heading: an enlarged restock symbol and a small count of the charges left on this
 * villager, with "Restock (N)" on hover (also its narration). Kept that small so it never reaches the villager's name, centred over the right pane. Draws nothing until the server says the perk is
 * active for this menu ({@link RestockVillagerClientState}), greyed out with no charges left or
 * nothing sold out. The server re-checks every press.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RestockVillagerButton {

    private static final String LABEL_KEY = "perk.dungeontrain.restock_villager.button";
    private static final String ICON = "\u21BB";
    /** The restock glyph comes from the small fallback font, so it is drawn enlarged... */
    private static final float ICON_SCALE = 2.0F;
    /** ...and the count shrunk, so the symbol reads first. */
    private static final float COUNT_SCALE = 0.75F;
    private static final int ICON_COUNT_GAP = 1;
    /** The fallback glyph sits low in its line and the shrunk count high; these centre both. */
    private static final float ICON_Y_NUDGE = -2.0F;
    private static final float COUNT_Y_NUDGE = 1.0F;
    /** Vanilla draws "Trades" centred on x 53 of the menu; the button starts just past it. */
    private static final int TRADES_LABEL_CENTRE_X = 53;
    private static final int GAP = 3;
    private static final int Y = 4;
    private static final int HEIGHT = 11;
    private static final int PADDING_X = 3;

    private RestockVillagerButton() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen screen)) return;
        MerchantMenu menu = screen.getMenu();
        Font font = Minecraft.getInstance().font;
        int labelRight = TRADES_LABEL_CENTRE_X + font.width(Component.translatable("merchant.trades")) / 2;
        int x = screen.getGuiLeft() + labelRight + GAP;
        int y = screen.getGuiTop() + Y;
        event.addListener(new RestockButton(x, y, menu));
    }

    private static final class RestockButton extends Button {

        private final MerchantMenu menu;
        private int shownLeft = -1;

        RestockButton(int x, int y, MerchantMenu menu) {
            super(x, y, 0, HEIGHT, Component.empty(),
                b -> PacketDistributor.sendToServer(new RestockVillagerRequestPacket(menu.containerId)),
                DEFAULT_NARRATION);
            this.menu = menu;
            this.active = false;
        }

        /**
         * Refreshed every frame. The widget stays {@code visible} (vanilla would stop calling this
         * otherwise) and simply draws nothing, inactive, until the server's status arrives.
         */
        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = RestockVillagerClientState.remainingFor(menu.containerId);
            if (left < 0) {
                active = false;
                return;
            }
            active = left > 0 && RestockVillager.needsRestock(menu.getOffers());
            if (left != shownLeft) {
                shownLeft = left;
                setMessage(Component.translatable(LABEL_KEY, left));
                setTooltip(Tooltip.create(getMessage()));
            }
            Font font = Minecraft.getInstance().font;
            setWidth(Math.round(iconWidth(font) + ICON_COUNT_GAP + countWidth(font)) + 2 * PADDING_X);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }

        /** Draws the enlarged restock symbol then the smaller count in place of the label text. */
        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
            float iconX = getX() + PADDING_X;
            drawScaled(graphics, font, ICON, iconX, ICON_Y_NUDGE, ICON_SCALE, color);
            drawScaled(graphics, font, String.valueOf(shownLeft), iconX + iconWidth(font) + ICON_COUNT_GAP, COUNT_Y_NUDGE, COUNT_SCALE, color);
        }

        private void drawScaled(GuiGraphics graphics, Font font, String text, float x, float yNudge, float scale, int color) {
            float y = getY() + (getHeight() - font.lineHeight * scale) / 2.0F + scale / 2.0F + yNudge;
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, text, 0, 0, color);
            graphics.pose().popPose();
        }

        private static float iconWidth(Font font) {
            return font.width(ICON) * ICON_SCALE;
        }

        private float countWidth(Font font) {
            return font.width(String.valueOf(shownLeft)) * COUNT_SCALE;
        }
    }
}
