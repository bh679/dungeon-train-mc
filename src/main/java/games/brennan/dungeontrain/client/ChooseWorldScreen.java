package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated sub-screen shown when the player picks "Okay" on the buggy-respawn
 * confirmation. Offers a focused choice between starting a fresh world (random
 * seed) or rerolling the current world (same seed). The Back button returns to
 * the original {@link net.minecraft.client.gui.screens.DeathScreen} so the
 * cause-of-death line is preserved.
 *
 * <p>Esc also routes to the death screen rather than the pause menu, matching
 * the player's intent: they came from the death screen and that's where they
 * should land if they bail out.</p>
 */
public final class ChooseWorldScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int BLOCK_GAP = 16;

    private final Screen deathScreen;

    public ChooseWorldScreen(Screen deathScreen) {
        super(Component.translatable("gui.dungeontrain.death.choose_title"));
        this.deathScreen = deathScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int leftX = centerX - BUTTON_WIDTH / 2;
        int topY = this.height / 2 - (BUTTON_HEIGHT * 3 + ROW_GAP * 2 + BLOCK_GAP) / 2;

        Button newWorld = Button.builder(
                        Component.translatable("gui.dungeontrain.death.new_world"),
                        b -> DeathScreenLayoutHandler.launchWorld(this, false))
                .bounds(leftX, topY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(newWorld);

        Button sameWorld = Button.builder(
                        Component.translatable("gui.dungeontrain.death.same_world"),
                        b -> DeathScreenLayoutHandler.launchWorld(this, true))
                .bounds(leftX, topY + BUTTON_HEIGHT + ROW_GAP, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(sameWorld);

        Button back = Button.builder(
                        Component.translatable("gui.dungeontrain.death.back"),
                        b -> backToDeathScreen())
                .bounds(leftX, topY + (BUTTON_HEIGHT + ROW_GAP) * 2 + BLOCK_GAP, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(back);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int titleY = this.height / 2 - (BUTTON_HEIGHT * 3 + ROW_GAP * 2 + BLOCK_GAP) / 2 - 30;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        backToDeathScreen();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void backToDeathScreen() {
        Minecraft.getInstance().setScreen(deathScreen);
    }
}
