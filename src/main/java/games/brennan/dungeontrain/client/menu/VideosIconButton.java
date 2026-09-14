package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import games.brennan.dungeontrain.client.videos.VideoCatalog;
import games.brennan.dungeontrain.client.videos.VideoList;
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
 *
 * <p>A pulsing green dot rides the top-right corner while a streamer is live
 * ({@link VideoCatalog#anyLive()}) — the same dot the page draws on the live row — so the menu
 * says "someone is on right now" without the page being opened.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideosIconButton extends Button {

    private static final int RED       = 0xFFE53935;
    private static final int RED_HOVER = 0xFFEF5350;
    private static final int MARK      = 0xFFFFFFFF;
    private static final float LIVE_DOT_SCALE = 0.3F;
    private static final int MIN_LIVE_DOT = 4;


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
        if (VideoCatalog.anyLive()) {
            // Badge: overhangs the corner by a pixel so it reads as a notification, not artwork.
            int dot = Math.max(MIN_LIVE_DOT, Math.round(s * LIVE_DOT_SCALE));
            VideoList.drawLiveDot(g, x + getWidth() - dot + 1, y - 1, dot);
        }
    }
}
