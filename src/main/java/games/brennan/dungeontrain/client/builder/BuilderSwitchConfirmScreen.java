package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.net.BuilderSwitchPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * "You have unsaved changes" — shown before anything that would re-stamp the train over edited
 * carriages.
 *
 * <p>Two things do that: picking a different mode, and opening a different template. Both are about
 * to throw the edits away, and both deserve the same three ways out, with the destructive one not
 * the default: <b>Save and continue</b>, <b>Discard and continue</b>, <b>Go back</b>.</p>
 *
 * <p>What "continue" means is the caller's — {@code proceed} runs once the work is either kept or
 * knowingly abandoned. The screen's own job is the same either way, which is why it takes an action
 * rather than a second copy of it existing for Open.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSwitchConfirmScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final Screen lastScreen;
    private final Component subtitle;
    private final Runnable proceed;
    private final int dirtyCount;

    /** The mode-switch prompt: continuing re-stamps this world into {@code target}. */
    public BuilderSwitchConfirmScreen(Screen lastScreen, BuilderMode target, int dirtyCount) {
        this(lastScreen, Component.translatable(target.labelKey()), dirtyCount,
                () -> DungeonTrainNet.sendToServer(new BuilderSwitchPacket(target.id(), true)));
    }

    /**
     * The general form.
     *
     * @param subtitle what continuing will land on — the mode name, or the template being opened
     * @param proceed  sent once the builder has chosen to keep or discard; always the forced form of
     *                 whatever request it is, since by then the question has been answered
     */
    public BuilderSwitchConfirmScreen(Screen lastScreen, Component subtitle, int dirtyCount,
                                      Runnable proceed) {
        super(Component.translatable("gui.dungeontrain.builder.unsaved.title", dirtyCount));
        this.lastScreen = lastScreen;
        this.subtitle = subtitle;
        this.dirtyCount = dirtyCount;
        this.proceed = proceed;
    }

    @Override
    protected void init() {
        int x = (this.width - BUTTON_WIDTH) / 2;
        int y = this.height / 2 - BUTTON_HEIGHT;

        // Green, matching the editor's own save affordance: green while there's work to keep.
        this.addRenderableWidget(new ColorTintedButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.unsaved.save"),
                0.35F, 1.0F, 0.35F,
                b -> saveThenProceed()));

        y += BUTTON_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.unsaved.discard"),
                        b -> proceedNow())
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        y += BUTTON_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.unsaved.back"),
                        b -> this.onClose())
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        g.drawCenteredString(this.font, subtitle, this.width / 2, this.height / 2 - 46, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void saveThenProceed() {
        Minecraft mc = Minecraft.getInstance();
        if (BuilderBoundsState.isDraft()) {
            // Nowhere to save to yet — name it first, and leave the follow-up for the builder to
            // repeat rather than firing it behind a screen they didn't expect.
            mc.setScreen(BuilderNewScreen.saveAs(lastScreen));
            return;
        }
        mc.setScreen(null);
        DungeonTrainNet.sendToServer(new games.brennan.dungeontrain.net.BuilderSavePacket());
        // Whatever follows is forced: the save has already been dispatched, and re-asking the
        // server whether anything is dirty would race it.
        proceed.run();
    }

    private void proceedNow() {
        Minecraft.getInstance().setScreen(null);
        proceed.run();
    }

    /** Exposed for the narration/tests: how many carriages the prompt is about. */
    public int dirtyCount() {
        return dirtyCount;
    }
}
