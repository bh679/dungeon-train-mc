package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorHistoryPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * What the editor's Undo and Redo would step through next, as last reported by the server.
 *
 * <p>Read by the X menu so its two history buttons can name the step they are about to apply
 * rather than leaving the author to find out by pressing one.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorHistoryState {

    private static volatile String undoLabel = "";
    private static volatile String redoLabel = "";

    private EditorHistoryState() {}

    /** What Undo would reverse, or {@code ""} when there is nothing to undo. */
    public static String undoLabel() {
        return undoLabel;
    }

    /** What Redo would re-apply, or {@code ""} when there is nothing to redo. */
    public static String redoLabel() {
        return redoLabel;
    }

    public static void accept(EditorHistoryPacket packet) {
        undoLabel = packet.undoLabel();
        redoLabel = packet.redoLabel();
    }

    /** The next world has its own history; this one's labels would be a lie. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        undoLabel = "";
        redoLabel = "";
    }
}
