package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * The second half of the name-collision question: the player has said the downloaded build should
 * take the name, and this asks what happens to the one that had it.
 *
 * <p>Keeping a copy renames the existing build and loads the download in its place; overwriting
 * discards it. Cancel goes <i>back</i> to {@link BuilderProfileCollisionScreen} rather than out of
 * the flow, because "not this branch" is a far more likely reason to press it here than "not at
 * all".</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileReplaceScreen extends BuilderProfileChoiceScreen {

    private final BuilderProfileCollisionScreen previousStep;

    public BuilderProfileReplaceScreen(BuilderProfileCollisionScreen previousStep, Screen lastScreen,
                                       String buildName,
                                       BiConsumer<BuilderRelayInstall.Resolution, String> onChosen) {
        super(Component.translatable("gui.dungeontrain.builder.profile.collision.replace.title",
                BuilderLabels.pretty(buildName)), lastScreen, buildName, onChosen);
        this.previousStep = previousStep;
    }

    @Override
    protected Component hint() {
        return Component.translatable("gui.dungeontrain.builder.profile.collision.replace.hint");
    }

    @Override
    protected void addChoices() {
        addChoice(Component.translatable("gui.dungeontrain.builder.profile.collision.rename_existing",
                        prettyName()),
                () -> promptFor(BuilderRelayInstall.Resolution.RENAME_EXISTING,
                        "gui.dungeontrain.builder.profile.name.rename_existing"));

        addChoice(Component.translatable("gui.dungeontrain.builder.profile.collision.replace"),
                () -> choose(BuilderRelayInstall.Resolution.REPLACE));
    }

    @Override
    protected Component leaveLabel() {
        return CommonComponents.GUI_BACK;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(previousStep);
    }
}
