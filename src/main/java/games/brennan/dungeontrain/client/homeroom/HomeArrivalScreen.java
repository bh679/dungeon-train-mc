package games.brennan.dungeontrain.client.homeroom;

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
 * showing the same picture at a field of view it fills ({@link HomeArrivalFov}). Being the open
 * screen is what holds input: nothing moves the player while a screen is up, and the mouse stays
 * free, so the server's pose is the pose they wake with. At the end, after a tube's far end, it is
 * the black the next world loads under. {@link HomeTvVisit} drives the phases; this only draws them.</p>
 */
public final class HomeArrivalScreen extends Screen {

    static final long FADE_IN_MS = 600L;

    HomeArrivalScreen() {
        super(Component.empty());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float overlay = HomeTvVisit.overlayAlpha(System.currentTimeMillis());
        ResourceLocation picture = HomeTvVisit.textureLocation();
        if (overlay > 0f && picture != null) {
            RenderSystem.enableBlend();
            g.setColor(1f, 1f, 1f, overlay);
            g.blit(picture, 0, 0, width, height, 0f, 0f, 1, 1, 1, 1);
            g.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
        float black = HomeTvVisit.tubeBlack();
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
