package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The carriage an author rerolled a contents plot into, by contents id — the X menu's Reroll
 * carriage icon. Held for the session only: the plot is a view of the template, not part of it, so
 * nothing is saved and a fresh world shows each contents in its stable pick again.
 *
 * <p>Cleared when the server stops, for the reason {@link EditorPlotSnapshots#onServerStopped}
 * gives: in single player the process outlives the world, and every world shares contents ids.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ContentsShellOverrides {

    private static final Map<String, String> SHELL_BY_CONTENTS = new HashMap<>();

    private ContentsShellOverrides() {}

    public static synchronized Optional<String> get(String contentsId) {
        return Optional.ofNullable(SHELL_BY_CONTENTS.get(contentsId));
    }

    public static synchronized void set(String contentsId, String carriageId) {
        SHELL_BY_CONTENTS.put(contentsId, carriageId);
    }

    public static synchronized void clearAll() {
        SHELL_BY_CONTENTS.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }
}
