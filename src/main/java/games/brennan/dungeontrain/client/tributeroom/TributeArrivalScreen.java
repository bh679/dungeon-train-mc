package games.brennan.dungeontrain.client.tributeroom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The hand-off from the perks menu to the room, and the black the visit ends under.
 *
 * <p>While the room is stood up the menu is drawn whole over the window, from the very texture the TV
 * will show, so nothing on screen changes until the room is in; then it dissolves, the TV behind it
 * showing the same picture at a field of view it fills ({@link TributeArrivalFov}). Being the open
 * screen is what holds input: nothing moves the player while a screen is up, and the mouse stays
 * free, so the server's pose is the pose they wake with. At the end, after a tube's far end, it is
 * the black the next world loads under. {@link TributeTvVisit} drives the phases; this only draws them.</p>
 */
public final class TributeArrivalScreen extends Screen {

    static final long FADE_IN_MS = 600L;

    TributeArrivalScreen() {
        super(Component.empty());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float overlay = TributeTvVisit.overlayAlpha(System.currentTimeMillis());
        ResourceLocation picture = TributeTvVisit.textureLocation();
        if (overlay > 0f && picture != null) {
            // The menu's picture is not opaque everywhere (the sky's lens is clear where the world
            // shows through it), so a solid sky-coloured sheet goes under it at the same alpha: the
            // whole frame holds solid while the room loads behind, then fades as one.
            int alpha = Mth.clamp(Math.round(overlay * 255f), 0, 255);
            g.fill(0, 0, width, height, (alpha << 24) | (games.brennan.dungeontrain.client.perks.PerksScreen.SKY_BG & 0xFFFFFF));
            RenderSystem.enableBlend();
            g.setColor(1f, 1f, 1f, overlay);
            g.blit(picture, 0, 0, width, height, 0f, 0f, 1, 1, 1, 1);
            g.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
        float black = TributeTvVisit.tubeBlack();
        if (black > 0f) {
            int alpha = Mth.clamp(Math.round(black * 255f), 0, 255);
            g.fill(0, 0, width, height, alpha << 24);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;
    }
}
