package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square icon button carrying an Instagram-style mark — magenta rounded tile, white camera
 * outline with a lens and a dot — drawn programmatically like {@link DiscordIconButton}. Opens
 * Brennan's profile from the Videos page's channel row.
 */
@OnlyIn(Dist.CLIENT)
public final class InstagramIconButton extends Button {

    private static final int MAGENTA = 0xFFD62976;
    private static final int MAGENTA_HOVER = 0xFFE64B8F;
    private static final int MARK = 0xFFFFFFFF;

    public InstagramIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int body = isHoveredOrFocused() ? MAGENTA_HOVER : MAGENTA;
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);
        PlatformToggleButton.drawCamera(g, x, y, s, MARK);
    }
}
