package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Session flag that toggles dev-mode write-through in the editor save paths.
 *
 * When enabled, {@link CarriageEditor#save} (and the parts/contents/track/tunnel
 * equivalents) also write the saved template into the on-disk source tree at
 * {@code src/main/resources/data/dungeontrain/...} so author-built templates
 * get committed alongside the rest of the mod.
 *
 * <p>Defaults each server start to {@link CarriageTemplateStore#sourceTreeAvailable()}:
 * <ul>
 *   <li><b>Packaged jar (player install)</b> — source tree is absent →
 *       defaults <b>off</b>. Source-tree writes are no-ops anyway in this
 *       environment; the gate just keeps the status display honest.</li>
 *   <li><b>Dev checkout ({@code ./gradlew runClient})</b> — source tree is
 *       writable → defaults <b>on</b>, so authored edits ship in the next
 *       build without the dev having to remember to type
 *       {@code /editor devmode on} every world load.</li>
 * </ul>
 *
 * <p>The manual {@code /editor devmode on|off} command (see
 * {@link games.brennan.dungeontrain.command.EditorCommand} {@code runDevMode})
 * still works as an explicit override — devs who want to pause auto-promotion
 * mid-session can flip the flag off and any subsequent saves stay in
 * {@code config/dungeontrain/user/...} only. The override is session-scoped:
 * the next world load re-derives the default.
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
        // Auto-on in dev checkouts (source tree writable), auto-off in packaged
        // jars (no source tree). Manual `/editor devmode on|off` still overrides
        // mid-session — the next server start re-derives this default.
        set(CarriageTemplateStore.sourceTreeAvailable());
    }
}
