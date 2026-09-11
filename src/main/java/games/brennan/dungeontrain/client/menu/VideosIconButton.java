package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying a play mark — a red rounded tile with a white
 * triangle — drawn programmatically like {@link DiscordIconButton} so no texture asset needs
 * shipping. Opens the Videos page; sits in the icon column between Credits and Discord (see
 * {@code TitleScreenCreditsButton}).
 *
 * <p>Red because that is the colour a play button is on every platform that has one; it is not
 * any one platform's mark, since the page behind it lists four.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideosIconButton extends Button {

    private static final int RED       = 0xFFE53935;
    private static final int RED_HOVER = 0xFFEF5350;
    private static final int MARK      = 0xFFFFFFFF;

    public VideosIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int body = isHoveredOrFocused() ? RED_HOVER : RED;

        // Red tile, corners nipped so it reads as the rounded app icon rather than a square.
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);

        // The triangle — the same shape the Videos page's YouTube toggle draws.
        PlatformToggleButton.drawPlay(g, x, y, s, MARK);
    }
}
