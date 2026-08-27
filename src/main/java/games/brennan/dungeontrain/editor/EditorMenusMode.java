package games.brennan.dungeontrain.editor;

import java.util.Locale;

/**
 * Visibility mode for the editor's world-space menus — the tri-state behind the X-menu's
 * "Editor Menus" row and {@code /dungeontrain editor editormenus <mode>}.
 *
 * <ul>
 *   <li>{@link #ON} — every plot's floating panel is drawn, whether or not the player is
 *       standing in it. The behaviour the old boolean {@code true} had.</li>
 *   <li>{@link #AUTO} — default. Identical to {@code ON} while the player is between plots;
 *       the moment they step into one, the other plots' panels stop drawing so the plot being
 *       edited is the only one talking. The row-start nav menus, the help board and the
 *       package / Stages panels are not per-plot and stay up in every mode.</li>
 *   <li>{@link #OFF} — no world-space editor panel is drawn at all. The old boolean
 *       {@code false}.</li>
 * </ul>
 *
 * <p>Per-player and per-session — held in {@link PartPositionMenuController} on the server,
 * mirrored to the client by {@code EditorMenusModePacket}. Nothing about it is written to disk,
 * exactly like the boolean flag it replaces.</p>
 */
public enum EditorMenusMode {
    ON,
    AUTO,
    OFF;

    /** The default for a player who has never touched the setting. */
    public static final EditorMenusMode DEFAULT = AUTO;

    /** Wire / command token for this mode ({@code "on"}, {@code "auto"}, {@code "off"}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Mode for {@code token}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Lenient on purpose: this parses a value off the wire and out of a command tree, and an
     * unknown token should land the player on the default rather than crash the packet handler.</p>
     */
    public static EditorMenusMode parse(String token) {
        if (token == null) return DEFAULT;
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (EditorMenusMode m : values()) {
            if (m.id().equals(t)) return m;
        }
        return DEFAULT;
    }
}
