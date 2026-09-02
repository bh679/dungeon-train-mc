package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * The template this download would land on has edits in the editor that nobody has saved.
 *
 * <p>Asked before anything is written, which is the whole point of it: the load replaces that
 * template's file and the next stamp restamps the plot from it, so the blocks standing in the world
 * right now are what would be lost. Cancelling leaves both the file and the plot exactly as they
 * are — the fetch that raised this question was a read.</p>
 *
 * <p>Narrower than the editor's own "save before switch" list, deliberately. That screen names every
 * dirty plot in the session, which is the right question when a category switch is about to restamp
 * all of them and the wrong one here: the player pressed Load on one build, and this asks about that
 * build.</p>
 *
 * <p>The answer replays the download unchanged — same resolution, same name — with the overwrite
 * confirmed, so a question that arrives on the second press of a collision flow does not throw away
 * the answer given on the first.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileUnsavedScreen extends BuilderProfileChoiceScreen {

    private final BuilderRelayInstall.Resolution resolution;
    private final String chosenName;

    public BuilderProfileUnsavedScreen(Screen lastScreen, String buildName,
                                       BuilderRelayInstall.Resolution resolution, String chosenName,
                                       BiConsumer<BuilderRelayInstall.Resolution, String> onChosen) {
        super(Component.translatable("gui.dungeontrain.builder.profile.unsaved.title",
                        BuilderLabels.pretty(buildName)),
                lastScreen, buildName, onChosen);
        this.resolution = resolution == null ? BuilderRelayInstall.Resolution.AS_IS : resolution;
        this.chosenName = chosenName == null ? "" : chosenName;
    }

    @Override
    protected Component hint() {
        return Component.translatable("gui.dungeontrain.builder.profile.unsaved.hint");
    }

    @Override
    protected void addChoices() {
        addChoice(Component.translatable("gui.dungeontrain.builder.profile.unsaved.load_anyway"),
                () -> {
                    this.minecraft.setScreen(lastScreen);
                    onChosen.accept(resolution, chosenName);
                });
    }
}
