package games.brennan.dungeontrain.client.social;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A square button with a centred sprite that, unlike vanilla's SpriteIconButton, fades its icon with its alpha. */
@OnlyIn(Dist.CLIENT)
final class FadingIconButton extends Button {

    private static final int ICON_PX = 16;

    private final ResourceLocation icon;

    FadingIconButton(int x, int y, int size, ResourceLocation icon, Component name, OnPress onPress) {
        super(x, y, size, size, name, onPress, DEFAULT_NARRATION);
        this.icon = icon;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        g.setColor(1.0F, 1.0F, 1.0F, this.alpha);
        g.blitSprite(icon, getX() + (getWidth() - ICON_PX) / 2, getY() + (getHeight() - ICON_PX) / 2, ICON_PX, ICON_PX);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void renderString(GuiGraphics g, Font font, int color) {
        // Icon only: the name is the tooltip and the narration.
    }
}
