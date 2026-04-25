package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the command strings produced by {@link EditorMenuScreen#newEntryFor}
 * and {@link EditorMenuScreen#removeEntryFor}.
 *
 * <p>Regression guard for the bug where the menu spliced the HUD-friendly
 * {@code displayName} (e.g. {@code "track / track2"}) into command strings
 * for track-side categories — the parser rejected the slashes/spaces with
 * "Incorrect argument for command". The fix routes the bare command-token
 * id ({@code "track"}, {@code "pillar_bottom"}, {@code "tunnel_section"})
 * through a separate {@code modelId} channel and only uses the friendly
 * {@code model} string for user-facing labels.</p>
 */
final class EditorMenuScreenTest {

    // ---- Remove (the originally reported bug) ----

    @Test
    @DisplayName("Remove for tracks uses kind id, not the friendly path string")
    void remove_tracks_usesModelIdNotPath() {
        String command = removeCommandFor("tracks", "track", "track / track2");
        assertEquals("dungeontrain editor tracks reset track", command);
    }

    @Test
    @DisplayName("Remove for pillars uses pillar_<section> id")
    void remove_pillars_usesKindId() {
        String command = removeCommandFor("tracks", "pillar_bottom", "pillar / bottom / stone");
        assertEquals("dungeontrain editor tracks reset pillar_bottom", command);
    }

    @Test
    @DisplayName("Remove for tunnels uses tunnel_<variant> id")
    void remove_tunnels_usesKindId() {
        String command = removeCommandFor("tracks", "tunnel_section", "tunnel / section / default");
        assertEquals("dungeontrain editor tracks reset tunnel_section", command);
    }

    @Test
    @DisplayName("Remove for stairs adjunct uses adjunct_stairs id")
    void remove_adjunctStairs_usesKindId() {
        String command = removeCommandFor("tracks", "adjunct_stairs", "stairs / default");
        assertEquals("dungeontrain editor tracks reset adjunct_stairs", command);
    }

    @Test
    @DisplayName("Remove for carriages still works (sanity)")
    void remove_carriages_unchanged() {
        String command = removeCommandFor("carriages", "standard", "standard");
        assertEquals("dungeontrain editor reset standard", command);
    }

    @Test
    @DisplayName("Remove for contents still works (sanity)")
    void remove_contents_unchanged() {
        String command = removeCommandFor("contents", "default", "default");
        assertEquals("dungeontrain editor contents reset default", command);
    }

    @Test
    @DisplayName("Remove confirm prompt shows the friendly path string for the user")
    void remove_tracks_confirmPromptUsesFriendlyName() {
        CommandMenuEntry.DrillIn entry = (CommandMenuEntry.DrillIn) EditorMenuScreen.removeEntryFor(
            "tracks", "track", "track / track2");
        assertNotNull(entry);
        // Title is what the player reads — should include the friendly path,
        // not the bare kind token.
        assertEquals("Remove the current variant for 'track / track2'?", entry.target().title());
    }

    @Test
    @DisplayName("Remove returns null for empty modelId (player not standing in a plot)")
    void remove_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.removeEntryFor("tracks", "", ""));
        assertNull(EditorMenuScreen.removeEntryFor("carriages", "", ""));
    }

    @Test
    @DisplayName("Remove returns null for unknown categories")
    void remove_architecture_returnsNull() {
        assertNull(EditorMenuScreen.removeEntryFor("architecture", "x", "x"));
    }

    // ---- New (latent same-bug, would have broken on first track-side click) ----

    @Test
    @DisplayName("New for tracks builds a TypeArg with kind id, not the friendly path")
    void new_tracks_usesModelIdNotPath() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            "tracks", "track", "track / track2");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new track", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks pillar uses pillar_<section> id")
    void new_pillars_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            "tracks", "pillar_top", "pillar / top / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new pillar_top", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks tunnel uses tunnel_<variant> id")
    void new_tunnels_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            "tracks", "tunnel_section", "tunnel / section / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new tunnel_section", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for stairs adjunct uses adjunct_stairs id")
    void new_adjunctStairs_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            "tracks", "adjunct_stairs", "stairs / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new adjunct_stairs", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks returns null when no model is active")
    void new_tracks_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.newEntryFor("tracks", "", ""));
    }

    // ---- helpers ----

    /** Drill into the Remove entry's confirm screen and pull the command the Yes button runs. */
    private static String removeCommandFor(String category, String modelId, String model) {
        CommandMenuEntry entry = EditorMenuScreen.removeEntryFor(category, modelId, model);
        assertNotNull(entry, "removeEntryFor returned null for " + category + "/" + modelId);
        CommandMenuEntry.DrillIn drill = assertInstanceOf(CommandMenuEntry.DrillIn.class, entry);
        List<CommandMenuEntry> confirmEntries = drill.target().entries();
        CommandMenuEntry.Run yesButton = assertInstanceOf(CommandMenuEntry.Run.class, confirmEntries.get(0));
        return yesButton.command();
    }
}
