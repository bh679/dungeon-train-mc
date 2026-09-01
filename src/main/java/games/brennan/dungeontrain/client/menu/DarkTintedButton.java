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
 * Vanilla {@link Button} rendered with a darker tint so it reads as a
 * non-vanilla, mod-specific action rather than blending in with the
 * standard menu row.
 *
 * <p>Re-implements {@link net.minecraft.client.gui.components.AbstractButton#renderWidget}
 * with {@code GuiGraphics.setColor(TINT, TINT, TINT, alpha)} applied before the
 * sprite blit, then restored to white before the label is drawn — so only the
 * background is darkened, label text stays crisp.</p>
 *
 * <p>Subclassable so a button can vary its own label or press behaviour per frame while keeping
 * this background — see {@code TrainBuilderMenuButton}.</p>
 */
@OnlyIn(Dist.CLIENT)
public class DarkTintedButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    private static final float TINT = 0.6F;

    private final float tintR;
    private final float tintG;
    private final float tintB;

    public DarkTintedButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, onPress, TINT, TINT, TINT);
    }

    /**
     * The same button tinted a colour rather than plain dark — for an action whose colour is
     * carrying meaning, like the red of a delete or the blue of a link out. The tint multiplies the
     * vanilla button sprite, so values above 1 are legitimate when a channel needs to survive the
     * sprite's own shading.
     */
    public DarkTintedButton(int x, int y, int width, int height, Component message, OnPress onPress,
                            float tintR, float tintG, float tintB) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.tintR = tintR;
        this.tintG = tintG;
        this.tintB = tintB;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        g.setColor(tintR, tintG, tintB, this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        this.renderString(g, mc.font, textColor | Mth.ceil(this.alpha * 255.0F) << 24);
    }
}
