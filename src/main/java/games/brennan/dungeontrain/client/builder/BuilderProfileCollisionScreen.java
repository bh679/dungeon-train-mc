package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * What to do when a downloaded build's name is one this install already uses.
 *
 * <p>Two answers here, not three: <b>New</b> loads the download under a fresh name and leaves the
 * build already here untouched, and <b>Replace Existing</b> opens
 * {@link BuilderProfileReplaceScreen} for the part that actually needs a second thought — whether
 * the build being replaced is kept under another name or thrown away. Asking both questions at once
 * put three near-identical sentences on screen and made the player weigh outcomes they had not
 * chosen between yet.</p>
 *
 * <p>The difference that is easiest to get wrong is not which file moves — it is which copy keeps
 * the <b>relay name</b>. Both replace answers leave the downloaded build holding it, so this world
 * goes on saving to that relay row. New gives the downloaded copy a name the relay has never heard
 * of, so it becomes a separate build from then on.</p>
 *
 * <p>Its own screen rather than a chat line with commands: the player has just pressed a button and
 * is owed an answer they can act on there and then, and two of the answers need a name typed.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileCollisionScreen extends BuilderProfileChoiceScreen {

    public BuilderProfileCollisionScreen(Screen lastScreen, String buildName,
                                         BiConsumer<BuilderRelayInstall.Resolution, String> onChosen) {
        super(Component.translatable("gui.dungeontrain.builder.profile.collision.title",
                BuilderLabels.pretty(buildName)), lastScreen, buildName, onChosen);
    }

    @Override
    protected Component hint() {
        return Component.translatable("gui.dungeontrain.builder.profile.collision.hint");
    }

    @Override
    protected void addChoices() {
        // One row: these are the two halves of a single either/or, and stacking them read as a list
        // of three things to weigh instead of one fork with a second question behind it.
        addChoiceRow(
                Component.translatable("gui.dungeontrain.builder.profile.collision.load_as_new"),
                () -> promptFor(BuilderRelayInstall.Resolution.LOAD_AS_NEW,
                        "gui.dungeontrain.builder.profile.name.load_as_new"),
                Component.translatable("gui.dungeontrain.builder.profile.collision.replace_existing"),
                () -> openReplaceStep());
    }

    /** The second question, carrying what this one was told is already in use. */
    private void openReplaceStep() {
        BuilderProfileReplaceScreen next =
                new BuilderProfileReplaceScreen(this, lastScreen, buildName, onChosen);
        next.setTakenNames(takenNames);
        this.minecraft.setScreen(next);
    }
}
