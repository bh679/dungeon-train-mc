package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A small button in the details bar showing what pressing it costs: the currency's icon (an
 * Advancement Point to unlock, a Cosmic Apple to add a point) and the amount beside it.
 */
@OnlyIn(Dist.CLIENT)
final class CostButton extends DarkTintedButton {

    enum Currency { ADVANCEMENT, APPLE }

    private static final int ICON = 8, ICON_GAP = 3;
    private final Currency currency;

    CostButton(int x, int y, int w, int h, Currency currency, int cost, Component name, OnPress onPress) {
        super(x, y, w, h, Component.literal(Integer.toString(cost)), onPress);
        this.currency = currency;
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(name));
    }

    @Override
    public void renderString(GuiGraphics g, Font font, int colour) {
        String cost = getMessage().getString();
        int w = ICON + ICON_GAP + font.width(cost);
        int x = getX() + (getWidth() - w) / 2, iy = getY() + (getHeight() - ICON) / 2;
        if (currency == Currency.ADVANCEMENT) {
            g.blit(PerkCounters.ADVANCEMENT_ICON, x, iy, ICON, ICON, 0, 0, PerkCounters.ICON_TEX, PerkCounters.ICON_TEX,
                PerkCounters.ICON_TEX, PerkCounters.ICON_TEX);
        } else {
            g.pose().pushPose();
            g.pose().translate(x, iy, 0);
            g.pose().scale(0.5F, 0.5F, 1F);
            g.renderItem(new ItemStack(ModItems.COSMIC_APPLE.get()), 0, 0);
            g.pose().popPose();
        }
        g.drawString(font, cost, x + ICON + ICON_GAP, getY() + (getHeight() - 8) / 2, colour, true);
    }
}
