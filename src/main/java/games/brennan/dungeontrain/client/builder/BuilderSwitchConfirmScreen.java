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
 * "You have unsaved changes" — shown when the builder picks a different mode with edited
 * carriages on the track.
 *
 * <p>Switching re-stamps the train, so the edits are about to go. Three ways out, and the
 * destructive one is not the default: <b>Save and switch</b>, <b>Discard and switch</b>,
 * <b>Go back</b>.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSwitchConfirmScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final Screen lastScreen;
    private final BuilderMode target;
    private final int dirtyCount;

    public BuilderSwitchConfirmScreen(Screen lastScreen, BuilderMode target, int dirtyCount) {
        super(Component.translatable("gui.dungeontrain.builder.unsaved.title", dirtyCount));
        this.lastScreen = lastScreen;
        this.target = target;
        this.dirtyCount = dirtyCount;
    }

    @Override
    protected void init() {
        int x = (this.width - BUTTON_WIDTH) / 2;
        int y = this.height / 2 - BUTTON_HEIGHT;

        // Green, matching the editor's own save affordance: green while there's work to keep.
        this.addRenderableWidget(new ColorTintedButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.unsaved.save"),
                0.35F, 1.0F, 0.35F,
                b -> saveThenSwitch()));

        y += BUTTON_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.unsaved.discard"),
                        b -> switchNow())
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
        g.drawCenteredString(this.font, Component.translatable(target.labelKey()),
                this.width / 2, this.height / 2 - 46, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void saveThenSwitch() {
        Minecraft mc = Minecraft.getInstance();
        if (BuilderBoundsState.isDraft()) {
            // Nowhere to save to yet — name it first, and leave the switch for the builder to
            // repeat rather than firing it behind a screen they didn't expect.
            mc.setScreen(BuilderNewScreen.saveAs(lastScreen));
            return;
        }
        mc.setScreen(null);
        DungeonTrainNet.sendToServer(new games.brennan.dungeontrain.net.BuilderSavePacket());
        // The switch is forced: the save has already been asked for, and re-asking the server
        // whether anything is dirty would race the save it just dispatched.
        DungeonTrainNet.sendToServer(new BuilderSwitchPacket(target.id(), true));
    }

    private void switchNow() {
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new BuilderSwitchPacket(target.id(), true));
    }

    /** Exposed for the narration/tests: how many carriages the prompt is about. */
    public int dirtyCount() {
        return dirtyCount;
    }
}
