package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square icon button carrying a YouTube-style mark — red rounded tile, white play triangle —
 * drawn programmatically like {@link DiscordIconButton}. Opens Brennan's channel from the Videos
 * page's channel row.
 */
@OnlyIn(Dist.CLIENT)
public final class YouTubeIconButton extends Button {

    private static final int RED = 0xFFFF0000;
    private static final int RED_HOVER = 0xFFFF4040;
    private static final int MARK = 0xFFFFFFFF;

    public YouTubeIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int body = isHoveredOrFocused() ? RED_HOVER : RED;
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);
        PlatformToggleButton.drawPlay(g, x, y, s, MARK);
    }
}
