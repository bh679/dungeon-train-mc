package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.NarrativeLocaleWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Makes a book edit visible without leaving the world.
 *
 * <p>Book prose is loaded once into the server-side registries, so editing an override file
 * changes nothing until they reload. In single-player (and for a LAN host) the integrated server
 * is in this same process, so the edit can be pushed straight onto its thread. On a remote server
 * there is nothing to reload — the prose belongs to that server — and this is a no-op, which is
 * the honest behaviour: the edit is stored and submittable, and takes effect there only once it
 * is approved and that server picks it up.</p>
 */
public final class IntegratedProseReload {

    private static final Logger LOGGER = LogUtils.getLogger();

    private IntegratedProseReload() {}

    /** Reload the prose registries if this client is hosting. Never throws. */
    public static void requestIfHosting() {
        try {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            // Registry loads touch server state, so they must happen on the server thread — this
            // is called from the render thread when the player saves an edit.
            server.execute(() -> {
                try {
                    NarrativeLocaleWatcher.reloadProse(server.getResourceManager());
                } catch (Throwable t) {
                    LOGGER.warn("[DungeonTrain] Translations: prose reload failed — {}", t.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: could not request a prose reload — {}",
                t.toString());
        }
    }
}
