package games.brennan.dungeontrain.editor;

/**
 * Session-only flag that toggles dev-mode write-through in the carriage editor.
 *
 * When enabled, {@link CarriageEditor#save} also writes the saved template into
 * the on-disk source tree at {@code src/main/resources/data/dungeontrain/templates/}
 * so author-built carriages get committed alongside the rest of the mod.
 *
 * Resets to {@code false} on every server start. Per the editor design, all
 * editor state is in-memory only — see {@link CarriageEditor}.
 */
public final class EditorDevMode {

    private static volatile boolean enabled = false;

    private EditorDevMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void set(boolean on) {
        enabled = on;
    }
}
