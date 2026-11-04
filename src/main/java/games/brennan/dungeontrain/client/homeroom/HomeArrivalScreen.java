package games.brennan.dungeontrain.client.homeroom;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The hand-off from the perks menu to the room: black rising over the perks frame, held while the
 * server stands the room up, then falling away on the first person view of the TV.
 *
 * <p>Being the open screen is what holds input: nothing moves the player while a screen is up, and the
 * mouse stays free, so the server's pose is the pose they wake with. {@link HomeTvVisit} drives the
 * phases; this only draws them.</p>
 */
public final class HomeArrivalScreen extends Screen {

    static final long FADE_OUT_MS = 400L;
    static final long FADE_IN_MS = 600L;

    @Nullable
    private final Screen behind;

    /** @param behind the perks menu, drawn under the fade out; null once the room is up and the fade is inward */
    HomeArrivalScreen(@Nullable Screen behind) {
        super(Component.empty());
        this.behind = behind;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float black = HomeTvVisit.blackAlpha(System.currentTimeMillis());
        if (behind != null && black < 1f) behind.render(g, -10_000, -10_000, partialTick);
        if (black > 0f) {
            int alpha = Mth.clamp(Math.round(black * 255f), 0, 255);
            g.fill(0, 0, width, height, alpha << 24);
        }
    }

    @Override
    protected void init() {
        if (behind != null) behind.init(minecraft, width, height);
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
