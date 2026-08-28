package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * What to do when a downloaded build's name is one this install already uses.
 *
 * <p>Three answers, because they are genuinely different things to want, and the difference that is
 * easiest to get wrong is not which file moves — it is which copy keeps the <b>relay name</b>.
 * Replace and Rename Existing both leave the downloaded build holding it, so this world goes on
 * saving to that relay row. Load as New gives the downloaded copy a fresh name, which the relay has
 * never heard of, so it becomes a separate build from then on.</p>
 *
 * <p>Its own screen rather than a chat line with commands: the player has just pressed a button and
 * is owed an answer they can act on there and then, and two of the three answers need a name typed.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileCollisionScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int NOTE_COLOUR = 0xA0A0A0;

    private final Screen lastScreen;
    private final String buildName;

    /** Given a resolution and the name the player chose (empty when none was needed), do the download. */
    private final BiConsumer<BuilderRelayInstall.Resolution, String> onChosen;

    public BuilderProfileCollisionScreen(Screen lastScreen, String buildName,
                                         BiConsumer<BuilderRelayInstall.Resolution, String> onChosen) {
        super(Component.translatable("gui.dungeontrain.builder.profile.collision.title",
                BuilderLabels.pretty(buildName)));
        this.lastScreen = lastScreen;
        this.buildName = buildName;
        this.onChosen = onChosen;
    }

    @Override
    protected void init() {
        int y = this.height / 2 - 30;
        int x = this.width / 2 - BUTTON_WIDTH / 2;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.collision.replace"),
                        b -> choose(BuilderRelayInstall.Resolution.REPLACE, ""))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP;

        // Both naming answers go through the same prompt; only the question differs, and it matters
        // which build the player thinks they are naming.
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.collision.rename_existing"),
                        b -> promptFor(BuilderRelayInstall.Resolution.RENAME_EXISTING,
                                "gui.dungeontrain.builder.profile.name.rename_existing"))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.collision.load_as_new"),
                        b -> promptFor(BuilderRelayInstall.Resolution.LOAD_AS_NEW,
                                "gui.dungeontrain.builder.profile.name.load_as_new"))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP * 3;

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void promptFor(BuilderRelayInstall.Resolution resolution, String promptKey) {
        this.minecraft.setScreen(new BuilderProfileNameScreen(lastScreen,
                Component.translatable(promptKey, BuilderLabels.pretty(buildName)),
                suggestionFor(resolution),
                chosen -> onChosen.accept(resolution, chosen)));
    }

    /**
     * What the name box starts with: the existing name with a {@code _2} on it, which is what a
     * player almost always wants and can edit in one keystroke.
     */
    private String suggestionFor(BuilderRelayInstall.Resolution resolution) {
        return buildName.isEmpty() ? "" : buildName + "_2";
    }

    private void choose(BuilderRelayInstall.Resolution resolution, String name) {
        this.minecraft.setScreen(lastScreen);
        onChosen.accept(resolution, name);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.collision.hint"),
                this.width / 2, this.height / 2 - 54, NOTE_COLOUR);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
