package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying the Bilibili mark, blitted from
 * {@code textures/gui/bilibili.png} and scaled into the button's size. Unlike its neighbours
 * {@link DiscordIconButton} and {@link PatreonIconButton}, whose marks are plotted with
 * {@code fill} calls, this one ships as real artwork — the logo has more shape in it than
 * rectangles reproduce honestly at any size.
 *
 * <p>Shown only to Chinese-language clients, one slot above Discord in the icon column (see
 * {@code TitleScreenCreditsButton}), because Discord — the community link directly below it — is
 * blocked in mainland China and is a dead end for exactly those players.</p>
 *
 * <p>No pulse behaviour: the sine pulse on {@link DiscordIconButton} is the opted-out-of-the-welcome-
 * popup affordance, and two pulsing title-screen icons would compete with each other.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BilibiliIconButton extends Button {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/gui/bilibili.png");

    /** Source artwork is square at this side length. */
    private static final int TEX = 32;

    /**
     * Translucent white laid over the tile on hover/focus. The drawn marks beside this one lighten
     * their body colour to say "this is a control"; a bare blit would sit inert and stop reading as
     * one, so the wash stands in for that.
     */
    private static final int HOVER_WASH = 0x33FFFFFF;

    public BilibiliIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        g.blit(TEXTURE, getX(), getY(), getWidth(), getHeight(), 0.0F, 0.0F, TEX, TEX, TEX, TEX);
        if (isHoveredOrFocused()) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), HOVER_WASH);
        }
    }
}
