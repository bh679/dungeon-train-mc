package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Session-only flag that toggles dev-mode write-through in the carriage editor.
 *
 * When enabled, {@link CarriageEditor#save} also writes the saved template into
 * the on-disk source tree at {@code src/main/resources/data/dungeontrain/templates/}
 * so author-built carriages get committed alongside the rest of the mod.
 *
 * Resets to {@code false} on every server start (single-player world load and
 * dedicated-server boot alike) so dev-mode never leaks across worlds in a
 * single Minecraft session. Per the editor design, all editor state is
 * in-memory only — see {@link CarriageEditor}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorDevMode {

    private static volatile boolean enabled = false;

    private EditorDevMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void set(boolean on) {
        enabled = on;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        set(false);
    }
}
