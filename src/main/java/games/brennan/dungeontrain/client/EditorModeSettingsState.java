package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.editor.EditorModeSetting;
import games.brennan.dungeontrain.editor.EditorModeSettings;
import games.brennan.dungeontrain.editor.EditorSettingMode;

/**
 * Client-side mirror of the world's per-mode editor Settings, pushed by
 * {@link games.brennan.dungeontrain.net.EditorModeSettingsPacket}. Read by the Settings rows; the
 * rules themselves are server-side.
 */
public final class EditorModeSettingsState {

    /** Replaced on the main client thread from the packet handler; read from the render thread. */
    private static volatile EditorModeSettings settings = EditorModeSettings.DEFAULTS;

    private EditorModeSettingsState() {}

    public static void set(EditorModeSettings next) {
        settings = next;
    }

    /**
     * The mode the Settings rows edit: TEST while this player stands in a Test-the-Carriage copy
     * (carriage and portal tests both report it through {@link PortalTestSessionState}).
     */
    public static EditorSettingMode mode() {
        return PortalTestSessionState.active() ? EditorSettingMode.TEST : EditorSettingMode.EDITOR;
    }

    /** {@code setting}'s value in the mode the player is in now. */
    public static boolean get(EditorModeSetting setting) {
        return settings.get(mode(), setting);
    }
}
