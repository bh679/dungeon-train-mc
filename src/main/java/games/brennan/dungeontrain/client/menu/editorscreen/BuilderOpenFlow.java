package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.BuilderBoundsState;
import games.brennan.dungeontrain.client.builder.BuilderDirtyState;
import games.brennan.dungeontrain.client.builder.BuilderNewScreen;
import games.brennan.dungeontrain.client.builder.BuilderSwitchConfirmScreen;
import games.brennan.dungeontrain.net.BuilderOpenPacket;
import games.brennan.dungeontrain.net.BuilderSavePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The editor screen's way into the Train Builder's own verbs — open, save, and start new — which
 * the builder pause menu and Open grid already send and this screen sends the same way.
 *
 * <p>Kept off {@link EditorScreenActions} so that table stays pure: the entries there resolve to a
 * {@code ClientAction} that calls in here, and the packets and screens live on this side.</p>
 */
public final class BuilderOpenFlow {

    private BuilderOpenFlow() {}

    /**
     * Open a template on the platform, asking first when there is unsaved work on the track — the
     * same prompt the Open grid raises ({@code BuilderOpenScreen.activate}), for the same reason:
     * the open re-stamps the train. Once answered the request goes forced, because the question the
     * server would otherwise ask has just been answered by the player.
     */
    public static void open(Screen parent, BuilderOpenTarget target) {
        if (target == null) return;
        if (BuilderDirtyState.hasUnsavedChanges()) {
            Minecraft.getInstance().setScreen(new BuilderSwitchConfirmScreen(parent,
                Component.literal(target.id()), BuilderDirtyState.dirtyCount(),
                () -> DungeonTrainNet.sendToServer(packet(target, true))));
            return;
        }
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(packet(target, false));
    }

    /**
     * Re-open what is on the platform from disk, forced: the builder has no reset verb of its own,
     * and an open of the same template re-stamps the scene from the saved file, which is what a
     * reset means here.
     */
    public static void reopenStanding() {
        BuilderOpenTarget target = BuilderOpenTarget.of(standing());
        if (target == null) return;
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(packet(target, true));
    }

    /**
     * Save the build on the platform — position-independent, unlike the editor's own save. A draft
     * has nowhere to be written, so the first save asks for a name, the way the pause menu's does.
     */
    public static void save(Screen parent) {
        if (BuilderBoundsState.isDraft()) {
            Minecraft.getInstance().setScreen(BuilderNewScreen.saveAs(parent));
            return;
        }
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new BuilderSavePacket());
    }

    /** Start a new build: the builder's own New screen, over this one. */
    public static void startNew(Screen parent) {
        Minecraft.getInstance().setScreen(new BuilderNewScreen(parent));
    }

    /** The open build as a roster key, from the bounds the server last pushed; null for a draft. */
    public static VariantKey standing() {
        return BuilderStanding.key(BuilderBoundsState.modeId(), BuilderBoundsState.subTypeId(),
            BuilderBoundsState.partKindId(), BuilderBoundsState.trackKindId(),
            BuilderBoundsState.buildName(), BuilderBoundsState.parked());
    }

    static BuilderOpenPacket packet(BuilderOpenTarget target, boolean force) {
        return target.isTrack()
            ? BuilderOpenPacket.forTrack(target.modeId(), target.trackKind(), target.id(), force)
            : new BuilderOpenPacket(target.modeId(), target.kindId(), target.id(),
                target.partKindId(), force);
    }
}
