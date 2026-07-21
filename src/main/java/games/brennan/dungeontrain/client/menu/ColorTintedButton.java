package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Vanilla {@link Button} rendered with a caller-supplied RGB tint multiplied
 * over the button sprite — used to colour-code actions (e.g. a green Patreon
 * button, a blue hosting button). Mirrors {@link DarkTintedButton} but with an
 * arbitrary colour instead of a flat grey; the label stays white for contrast,
 * and the tint brightens slightly on hover/focus so the button still reacts to
 * the mouse.
 */
@OnlyIn(Dist.CLIENT)
public final class ColorTintedButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    private static final float HOVER_BOOST = 1.15F;

    private final float red;
    private final float green;
    private final float blue;

    public ColorTintedButton(int x, int y, int width, int height, Component message,
                             float red, float green, float blue, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        float boost = this.isHoveredOrFocused() ? HOVER_BOOST : 1.0F;
        g.setColor(Math.min(1.0F, red * boost), Math.min(1.0F, green * boost), Math.min(1.0F, blue * boost), this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        this.renderString(g, mc.font, textColor | Mth.ceil(this.alpha * 255.0F) << 24);
    }
}
