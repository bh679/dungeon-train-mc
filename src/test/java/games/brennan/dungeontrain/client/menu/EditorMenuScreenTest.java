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
    @DisplayName("New for tracks returns null when no model is active")
    void new_tracks_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.newEntryFor("tracks", "", ""));
    }

    // ---- Weight (Triple row) — regression for the modelId fix + new tracks/contents categories ----

    @Test
    @DisplayName("Weight for carriages uses modelId, not the friendly path string")
    void weight_carriages_usesModelId() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("carriages", "standard", "standard", 10);
        assertEquals("dungeontrain editor weight standard dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor weight standard", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor weight standard inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for tracks splices kind + name into the tracks weight subcommand")
    void weight_tracks_track() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("tracks", "track", "default", 1);
        assertEquals("dungeontrain editor tracks weight track default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight track default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight track default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for pillar uses pillar_<section> + variant name")
    void weight_tracks_pillar() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("tracks", "pillar_bottom", "stone", 2);
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for tunnel uses tunnel_<variant> + variant name")
    void weight_tracks_tunnel() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("tracks", "tunnel_section", "default", 1);
        assertEquals("dungeontrain editor tracks weight tunnel_section default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight tunnel_section default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight tunnel_section default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for contents uses contents id")
    void weight_contents() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("contents", "default", "default", 1);
        assertEquals("dungeontrain editor contents weight default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor contents weight default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor contents weight default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight label reflects current weight when >= 0")
    void weight_label_includesCurrentWeight() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("carriages", "standard", "standard", 42);
        CommandMenuEntry.TypeArg middle = (CommandMenuEntry.TypeArg) triple.middleEntry();
        assertEquals("Weight (42)", middle.label());
    }

    @Test
    @DisplayName("Weight label is bare 'Weight' when current weight is the NO_WEIGHT sentinel")
    void weight_label_handlesNoWeightSentinel() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent("carriages", "standard", "standard", -1);
        CommandMenuEntry.TypeArg middle = (CommandMenuEntry.TypeArg) triple.middleEntry();
        assertEquals("Weight", middle.label());
    }

    @Test
    @DisplayName("Weight returns null for empty modelId (player not in a plot)")
    void weight_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor("carriages", "", "", 1));
        assertNull(EditorMenuScreen.weightTripleFor("tracks", "", "", 1));
        assertNull(EditorMenuScreen.weightTripleFor("contents", "", "", 1));
    }

    @Test
    @DisplayName("Weight returns null for tracks when modelName is empty")
    void weight_tracks_emptyModelName_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor("tracks", "track", "", 1));
    }

    @Test
    @DisplayName("Weight returns null for unknown / weight-less categories")
    void weight_unknownCategory_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor("architecture", "x", "x", 1));
        assertNull(EditorMenuScreen.weightTripleFor("parts", "floor:x", "x", 1));
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

    private static CommandMenuEntry.Triple weightTripleAssertingPresent(
        String category, String modelId, String modelName, int currentWeight
    ) {
        CommandMenuEntry entry = EditorMenuScreen.weightTripleFor(category, modelId, modelName, currentWeight);
        assertNotNull(entry, "weightTripleFor returned null for " + category + "/" + modelId + "/" + modelName);
        return assertInstanceOf(CommandMenuEntry.Triple.class, entry);
    }

    private static String commandFor(CommandMenuEntry e) {
        return assertInstanceOf(CommandMenuEntry.Stay.class, e).command();
    }

    private static String typePrefixFor(CommandMenuEntry e) {
        return assertInstanceOf(CommandMenuEntry.TypeArg.class, e).commandPrefix();
    }
}
