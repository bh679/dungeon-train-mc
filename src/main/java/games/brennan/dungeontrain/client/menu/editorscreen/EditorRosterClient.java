package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorWorldLayout;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorRosterRequestPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The client's copy of the template roster, and when to ask for a fresh one.
 *
 * <p>Asked for when the inventory screen opens, and again a few ticks after any command the
 * screen sends — the delay gives the server a moment to run the command before it is asked what
 * changed, the same rhythm the panels use for the dirty list. The world-space snapshot arriving is
 * also a change signal: the server only re-sends it when something about the stamped category's
 * templates moved, so it is a cheap cue that this cache is stale too.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorRosterClient {

    /** Ticks between a command and the roster re-request, so the server has run it first. */
    public static final int REFRESH_DELAY_TICKS = 10;

    private static volatile EditorRosterIndex index = EditorRosterIndex.EMPTY;
    private static int refreshTicks;
    private static boolean everRequested;

    private EditorRosterClient() {}

    /** The latest roster, empty until the first reply lands. */
    public static EditorRosterIndex index() {
        return index;
    }

    /** Ask the server now. */
    public static void request() {
        everRequested = true;
        DungeonTrainNet.sendToServer(new EditorRosterRequestPacket());
    }

    /**
     * Ask once, ahead of the screen being opened.
     *
     * <p>Called when the player lands in the editor world or in an editor plot, so the roster is
     * already here by the time X is pressed and the screen opens on its rows rather than on
     * "Loading templates…". A no-op once anything has asked; after that the refresh rhythm keeps
     * it fresh.</p>
     */
    public static void prefetch() {
        if (!everRequested) request();
    }

    /** Ask again in {@code ticks} ticks; a pending request is simply moved later. */
    public static void scheduleRefresh(int ticks) {
        refreshTicks = Math.max(1, ticks);
    }

    /** The world-space snapshot changed; if the roster has ever been wanted, refresh it too. */
    public static void onTypeMenusChanged() {
        if (everRequested) scheduleRefresh(REFRESH_DELAY_TICKS);
    }

    public static void apply(EditorRosterPacket packet) {
        index = new EditorRosterIndex(packet.groups(), packet.stampedCategoryId(), packet.trainSize());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (refreshTicks > 0 && --refreshTicks == 0) {
            request();
        }
        // The editor world itself is the cue outside any plot, and a builder world likewise: one
        // boolean a tick until the first ask.
        if (!everRequested) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null && mc.getConnection() != null
                    && (EditorWorldLayout.isEditorWorld(mc.level)
                        || games.brennan.dungeontrain.client.builder.BuilderBoundsState.isInBuilderWorld())) {
                request();
            }
        }
    }

    /** The next world may be a different install behind the same names. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        index = EditorRosterIndex.EMPTY;
        refreshTicks = 0;
        everRequested = false;
        // A parent chosen against this install's roster means nothing on the next one.
        CreatorLoadParent.reset();
    }
}
