package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * "Abandon this run?" — the step between the pause menu's red Abandon button and the kill it sends.
 *
 * <p>The button says <i>abandon</i>, which reads like leaving; what actually happens is that the
 * character dies and the run is over. One misclick used to be enough. So the screen spells out the
 * part the label doesn't: <b>you will die</b>, it counts as a death, and there is no undo.</p>
 *
 * <p>Shaped like {@code BuilderSwitchConfirmScreen} — centred button column, destructive option
 * first but plainly worded, Escape falling back to the way out that changes nothing. What
 * "continue" means is the caller's: {@code proceed} runs only once the player has said yes.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AbandonConfirmScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private static final Component LINE_1 = Component.translatable("gui.dungeontrain.abandon_confirm.line1");
    private static final Component LINE_2 = Component.translatable("gui.dungeontrain.abandon_confirm.line2");

    private final Screen lastScreen;
    private final Runnable proceed;

    /**
     * @param lastScreen the pause menu to fall back to — cancelling must leave the run exactly as it was
     * @param proceed    the abandon itself, run once the player has confirmed the death
     */
    public AbandonConfirmScreen(Screen lastScreen, Runnable proceed) {
        super(Component.translatable("gui.dungeontrain.abandon_confirm.title"));
        this.lastScreen = lastScreen;
        this.proceed = proceed;
    }

    @Override
    protected void init() {
        int x = (this.width - BUTTON_WIDTH) / 2;
        int y = this.height / 2 - BUTTON_HEIGHT;

        // The same red the Abandon button carries on the pause menu — the colour is the warning.
        this.addRenderableWidget(new ColorTintedButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("gui.dungeontrain.abandon_confirm.yes"),
                1.0F, 0.30F, 0.30F,
                b -> proceedNow()));

        y += BUTTON_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.abandon_confirm.back"),
                        b -> this.onClose())
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFF);
        g.drawCenteredString(this.font, LINE_1, this.width / 2, this.height / 2 - 52, 0xFF6060);
        g.drawCenteredString(this.font, LINE_2, this.width / 2, this.height / 2 - 40, 0xA0A0A0);
    }

    /** Escape and Go back land in the same place: the pause menu, run untouched. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void proceedNow() {
        Minecraft.getInstance().setScreen(null);
        proceed.run();
    }
}
