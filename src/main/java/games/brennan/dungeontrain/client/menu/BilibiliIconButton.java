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
 * A title-screen icon button carrying the Bilibili mark, blitted from
 * {@code textures/gui/bilibili.png} and scaled into the button's size. The artwork's corners are
 * rounded in its own alpha channel, so it reads as a rounded app tile alongside the Discord
 * logomark below it rather than a hard square. Unlike its neighbours
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

    /**
     * Corner nip for the hover wash, as a fraction of the button size — the same 8% {@link
     * DiscordIconButton} uses. The artwork's own corners are rounded in its alpha channel, so a
     * plain full-rect wash would paint a square halo into the transparency and undo the rounding
     * the moment the mouse arrives. Two overlapping rects leave the corners alone.
     */
    private static final float WASH_INSET = 0.08F;

    public BilibiliIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        g.blit(TEXTURE, getX(), getY(), getWidth(), getHeight(), 0.0F, 0.0F, TEX, TEX, TEX, TEX);
        if (isHoveredOrFocused()) {
            int inset = Math.max(1, Math.round(Math.min(getWidth(), getHeight()) * WASH_INSET));
            g.fill(getX() + inset, getY(), getX() + getWidth() - inset, getY() + getHeight(), HOVER_WASH);
            g.fill(getX(), getY() + inset, getX() + getWidth(), getY() + getHeight() - inset, HOVER_WASH);
        }
    }
}
