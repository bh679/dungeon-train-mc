package games.brennan.dungeontrain.editor;

/**
 * Session flag that toggles dev-mode write-through in the editor save paths.
 *
 * When enabled, {@link CarriageEditor#save} (and the parts/contents/track/tunnel
 * equivalents, plus {@link StageStore} gate presets) also write the saved template
 * into the on-disk source tree at {@code src/main/resources/data/dungeontrain/...}
 * so author-built templates get committed alongside the rest of the mod. Edit via the
 * {@code /dt editor ...} slash commands (not a raw text editor) so the in-memory store
 * stays in sync — raw file edits only take effect on the next world load.
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
public final class EditorDevMode {

    private static volatile boolean enabled = false;
    private static volatile boolean forceOnNextStart = false;

    private EditorDevMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void set(boolean on) {
        enabled = on;
    }

    /**
     * Arm a one-shot override: the next {@link ServerStartedEvent} re-asserts
     * editor dev-mode on, subject to the same source-tree gate as the default
     * in {@link #onServerStarting}. In a packaged jar with no writable source
     * tree, the override is a no-op — the button still launches the editor
     * world, but dev-mode stays off so the status display and overlay
     * behavior remain honest. Used by the title-screen "Dungeon Train Editor"
     * button.
     */
    public static void queueOnForNextStart() {
        forceOnNextStart = true;
    }

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        // Auto-on in dev checkouts (source tree writable), auto-off in packaged
        // jars (no source tree). Manual `/editor devmode on|off` still overrides
        // mid-session — the next server start re-derives this default.
        set(CarriageTemplateStore.sourceTreeAvailable());
    }

        public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        if (forceOnNextStart) {
            forceOnNextStart = false;
            set(CarriageTemplateStore.sourceTreeAvailable());
        }
    }
}
