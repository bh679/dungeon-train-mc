package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The title-screen icon that opens the Videos page; sits in the icon column between Credits and
 * Discord (see {@code TitleScreenCreditsButton}).
 *
 * <p>A red rounded tile with a white play triangle — the shape every platform uses for "video",
 * drawn programmatically like {@link DiscordIconButton} so no texture ships. One face for every
 * client: the Bilibili mark ({@link BilibiliIconButton}) appears only on the Videos page itself,
 * as the channel link at the bottom, rather than standing in for this icon on a Chinese-language
 * client as it used to.</p>
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
        int inset = Math.max(1, Math.round(s * 0.08F));

        // Red tile, corners nipped so it reads as the rounded app icon rather than a square.
        int body = isHoveredOrFocused() ? RED_HOVER : RED;
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);
        PlatformToggleButton.drawPlay(g, x, y, s, MARK);
    }
}
