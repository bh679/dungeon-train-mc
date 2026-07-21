package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A small square title-screen icon button carrying the current Patreon
 * logomark — a single solid circle (Patreon's post-2023 mark) rendered in the
 * Patreon brand orange — over a vanilla button face, so it sits consistently in
 * the bottom-left icon stack next to the menu-chat envelope. Drawn
 * programmatically, so no texture asset needs shipping.
 */
@OnlyIn(Dist.CLIENT)
public final class PatreonIconButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    private static final int PATREON_ORANGE = 0xFFF96854;

    public PatreonIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        // Vanilla button face, matching the accessibility / chat icon buttons.
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                getX(), getY(), getWidth(), getHeight());
        // Patreon logomark (current): one solid orange circle, centred.
        int r = Math.max(3, Math.min(getWidth(), getHeight()) / 2 - 4);
        fillCircle(g, getX() + getWidth() / 2, getY() + getHeight() / 2, r, PATREON_ORANGE);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }
}
