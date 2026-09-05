package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- DevMode visibility (hidden on main, visible everywhere else) ----

    @Test
    @DisplayName("DevMode toggle is hidden on release builds (branch == main)")
    void devmode_hiddenOnMainBranch() {
        assertFalse(EditorMenuScreen.shouldShowDevModeToggle("main"));
    }

    @Test
    @DisplayName("DevMode toggle is visible on a feature branch")
    void devmode_visibleOnFeatureBranch() {
        assertTrue(EditorMenuScreen.shouldShowDevModeToggle("claude/focused-swartz-159fbc"));
    }

    @Test
    @DisplayName("DevMode toggle is visible when build-time git detection failed (branch == '?')")
    void devmode_visibleOnUnknownBranchFallback() {
        assertTrue(EditorMenuScreen.shouldShowDevModeToggle("?"));
    }

    @Test
    @DisplayName("DevMode toggle is visible when branch is null (defensive — should never happen in practice)")
    void devmode_visibleOnNullBranch() {
        assertTrue(EditorMenuScreen.shouldShowDevModeToggle(null));
    }

    // ---- Remove (the originally reported bug) ----

    @Test
    @DisplayName("Remove for tracks uses kind id, not the friendly path string")
    void remove_tracks_usesModelIdNotPath() {
        String command = removeCommandFor(PlotCategory.TRACKS, "track", "track / track2");
        assertEquals("dungeontrain editor tracks reset track", command);
    }

    @Test
    @DisplayName("Remove for pillars uses pillar_<section> id")
    void remove_pillars_usesKindId() {
        String command = removeCommandFor(PlotCategory.TRACKS, "pillar_bottom", "pillar / bottom / stone");
        assertEquals("dungeontrain editor tracks reset pillar_bottom", command);
    }

    @Test
    @DisplayName("Remove for tunnels uses tunnel_<variant> id")
    void remove_tunnels_usesKindId() {
        String command = removeCommandFor(PlotCategory.TRACKS, "tunnel_section", "tunnel / section / default");
        assertEquals("dungeontrain editor tracks reset tunnel_section", command);
    }

    @Test
    @DisplayName("Remove for stairs adjunct uses adjunct_stairs id")
    void remove_adjunctStairs_usesKindId() {
        String command = removeCommandFor(PlotCategory.TRACKS, "adjunct_stairs", "stairs / default");
        assertEquals("dungeontrain editor tracks reset adjunct_stairs", command);
    }

    @Test
    @DisplayName("Remove for carriages still works (sanity)")
    void remove_carriages_unchanged() {
        String command = removeCommandFor(PlotCategory.CARRIAGES, "standard", "standard");
        assertEquals("dungeontrain editor reset standard", command);
    }

    @Test
    @DisplayName("Remove for contents still works (sanity)")
    void remove_contents_unchanged() {
        String command = removeCommandFor(PlotCategory.CONTENTS, "default", "default");
        assertEquals("dungeontrain editor contents reset default", command);
    }

    @Test
    @DisplayName("Remove confirm prompt shows the friendly path string for the user")
    void remove_tracks_confirmPromptUsesFriendlyName() {
        CommandMenuEntry.DrillIn entry = (CommandMenuEntry.DrillIn) EditorMenuScreen.removeEntryFor(
            PlotCategory.TRACKS, "track", "track / track2");
        assertNotNull(entry);
        // Title is what the player reads — should include the friendly path,
        // not the bare kind token.
        assertEquals("Remove the current variant for 'track / track2'?", entry.target().title());
    }

    @Test
    @DisplayName("Remove returns null for empty modelId (player not standing in a plot)")
    void remove_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.removeEntryFor(PlotCategory.TRACKS, "", ""));
        assertNull(EditorMenuScreen.removeEntryFor(PlotCategory.CARRIAGES, "", ""));
    }

    @Test
    @DisplayName("Remove returns null for unknown categories")
    void remove_architecture_returnsNull() {
        assertNull(EditorMenuScreen.removeEntryFor(PlotCategory.ARCHITECTURE, "x", "x"));
    }

    // ---- Flip quad (contents-only per-template random flip) ----

    @Test
    @DisplayName("Flip quad dispatches the per-axis contents flip command and reflects current state")
    void flip_quad_commandsAndState() {
        List<CommandMenuEntry> rows = EditorMenuScreen.flipRows("maze", true, false, false, true);
        assertEquals(2, rows.size(), "a Label and the quad");
        assertInstanceOf(CommandMenuEntry.Label.class, rows.get(0));
        CommandMenuEntry.Quad quad = assertInstanceOf(CommandMenuEntry.Quad.class, rows.get(1));

        CommandMenuEntry.Toggle x = (CommandMenuEntry.Toggle) quad.e1();
        assertEquals("X", x.label());
        assertTrue(x.state(), "X is on by default for every contents template");
        assertEquals("dungeontrain editor contents flip maze x on", x.cmdToTurnOn());
        assertEquals("dungeontrain editor contents flip maze x off", x.cmdToTurnOff());

        assertFalse(((CommandMenuEntry.Toggle) quad.e2()).state(), "Y off");
        assertFalse(((CommandMenuEntry.Toggle) quad.e3()).state(), "Z off");

        CommandMenuEntry.Toggle rooms = (CommandMenuEntry.Toggle) quad.e4();
        assertEquals("Rooms", rooms.label());
        assertTrue(rooms.state());
        assertEquals("dungeontrain editor contents flip maze rooms on", rooms.cmdToTurnOn());
    }

    // ---- New (latent same-bug, would have broken on first track-side click) ----

    @Test
    @DisplayName("New for tracks builds a TypeArg with kind id, not the friendly path")
    void new_tracks_usesModelIdNotPath() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            PlotCategory.TRACKS, "track", "track / track2");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new track", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks pillar uses pillar_<section> id")
    void new_pillars_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            PlotCategory.TRACKS, "pillar_top", "pillar / top / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new pillar_top", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks tunnel uses tunnel_<variant> id")
    void new_tunnels_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            PlotCategory.TRACKS, "tunnel_section", "tunnel / section / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new tunnel_section", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for stairs adjunct uses adjunct_stairs id")
    void new_adjunctStairs_usesKindId() {
        CommandMenuEntry.TypeArg entry = (CommandMenuEntry.TypeArg) EditorMenuScreen.newEntryFor(
            PlotCategory.TRACKS, "adjunct_stairs", "stairs / default");
        assertNotNull(entry);
        assertEquals("dungeontrain editor tracks new adjunct_stairs", entry.commandPrefix());
    }

    @Test
    @DisplayName("New for tracks returns null when no model is active")
    void new_tracks_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.newEntryFor(PlotCategory.TRACKS, "", ""));
    }

    // ---- Weight (Triple row) — regression for the modelId fix + new tracks/contents categories ----

    @Test
    @DisplayName("Weight for carriages uses modelId, not the friendly path string")
    void weight_carriages_usesModelId() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.CARRIAGES, "standard", "standard", 10);
        assertEquals("dungeontrain editor weight standard dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor weight standard", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor weight standard inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for tracks splices kind + name into the tracks weight subcommand")
    void weight_tracks_track() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.TRACKS, "track", "default", 1);
        assertEquals("dungeontrain editor tracks weight track default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight track default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight track default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for pillar uses pillar_<section> + variant name")
    void weight_tracks_pillar() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.TRACKS, "pillar_bottom", "stone", 2);
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight pillar_bottom stone inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for tunnel uses tunnel_<variant> + variant name")
    void weight_tracks_tunnel() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.TRACKS, "tunnel_section", "default", 1);
        assertEquals("dungeontrain editor tracks weight tunnel_section default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor tracks weight tunnel_section default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor tracks weight tunnel_section default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight for contents uses contents id")
    void weight_contents() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.CONTENTS, "default", "default", 1);
        assertEquals("dungeontrain editor contents weight default dec", commandFor(triple.leftEntry()));
        assertEquals("dungeontrain editor contents weight default", typePrefixFor(triple.middleEntry()));
        assertEquals("dungeontrain editor contents weight default inc", commandFor(triple.rightEntry()));
    }

    @Test
    @DisplayName("Weight label reflects current weight when >= 0")
    void weight_label_includesCurrentWeight() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.CARRIAGES, "standard", "standard", 42);
        CommandMenuEntry.TypeArg middle = (CommandMenuEntry.TypeArg) triple.middleEntry();
        assertEquals("Weight (42)", middle.label());
    }

    @Test
    @DisplayName("Weight label is bare 'Weight' when current weight is the NO_WEIGHT sentinel")
    void weight_label_handlesNoWeightSentinel() {
        CommandMenuEntry.Triple triple = weightTripleAssertingPresent(PlotCategory.CARRIAGES, "standard", "standard", -1);
        CommandMenuEntry.TypeArg middle = (CommandMenuEntry.TypeArg) triple.middleEntry();
        assertEquals("Weight", middle.label());
    }

    @Test
    @DisplayName("Weight returns null for empty modelId (player not in a plot)")
    void weight_emptyModelId_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.CARRIAGES, "", "", 1));
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.TRACKS, "", "", 1));
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.CONTENTS, "", "", 1));
    }

    @Test
    @DisplayName("Weight returns null for tracks when modelName is empty")
    void weight_tracks_emptyModelName_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.TRACKS, "track", "", 1));
    }

    @Test
    @DisplayName("Weight returns null for unknown / weight-less categories")
    void weight_unknownCategory_returnsNull() {
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.ARCHITECTURE, "x", "x", 1));
        assertNull(EditorMenuScreen.weightTripleFor(PlotCategory.PARTS, "floor:x", "x", 1));
    }

    // ---- helpers ----

    /** Drill into the Remove entry's confirm screen and pull the command the Yes button runs. */
    private static String removeCommandFor(PlotCategory category, String modelId, String model) {
        CommandMenuEntry entry = EditorMenuScreen.removeEntryFor(category, modelId, model);
        assertNotNull(entry, "removeEntryFor returned null for " + category + "/" + modelId);
        CommandMenuEntry.DrillIn drill = assertInstanceOf(CommandMenuEntry.DrillIn.class, entry);
        List<CommandMenuEntry> confirmEntries = drill.target().entries();
        CommandMenuEntry.Run yesButton = assertInstanceOf(CommandMenuEntry.Run.class, confirmEntries.get(0));
        return yesButton.command();
    }

    @Test
    @DisplayName("portals: weight and the three size steppers all route through the portals prefix")
    void portals_weightAndSizeCommands() {
        CommandMenuEntry.Triple weight =
            weightTripleAssertingPresent(PlotCategory.PORTALS, "portal_room", "default", 3);
        assertEquals("Weight (3)", weight.middleEntry().label());
        assertEquals("dungeontrain editor portals weight portal_room default dec", commandFor(weight.leftEntry()));
        assertEquals("dungeontrain editor portals weight portal_room default inc", commandFor(weight.rightEntry()));

        // A portal room is the only plot whose box the author chooses, so it is the only category
        // with size steppers. Position-resolved, so no model id is spliced in.
        CommandMenuEntry.Triple length = (CommandMenuEntry.Triple)
            EditorMenuPortalRows.sizeTripleFor("length", "Length", 11);
        assertEquals("Length (11)", length.middleEntry().label());
        assertEquals("dungeontrain editor portals length dec", commandFor(length.leftEntry()));
        assertEquals("dungeontrain editor portals length inc", commandFor(length.rightEntry()));
        assertEquals("dungeontrain editor portals length", typePrefixFor(length.middleEntry()));

        CommandMenuEntry.Triple width = (CommandMenuEntry.Triple)
            EditorMenuPortalRows.sizeTripleFor("width", "Width", 13);
        assertEquals("Width (13)", width.middleEntry().label());
        assertEquals("dungeontrain editor portals width inc", commandFor(width.rightEntry()));

        CommandMenuEntry.Triple height = (CommandMenuEntry.Triple)
            EditorMenuPortalRows.sizeTripleFor("height", "Height", 7);
        assertEquals("Height (7)", height.middleEntry().label());
        assertEquals("dungeontrain editor portals height dec", commandFor(height.leftEntry()));
    }

    @Test
    @DisplayName("No reported size means no stepper — every category but portals")
    void sizeTriple_absentWithoutASize() {
        assertNull(EditorMenuPortalRows.sizeTripleFor("length", "Length",
            games.brennan.dungeontrain.net.EditorStatusPacket.NO_SIZE));
    }

    @Test
    @DisplayName("portals: the Exits row and its spacing stepper route through the portals prefix")
    void portals_exitsCommands() {
        CommandMenuEntry exits = EditorMenuPortalRows.exitsRowFor("endless_repetition");
        assertEquals("Exits: On", assertInstanceOf(CommandMenuEntry.Stay.class, exits).label());
        assertEquals("dungeontrain editor portals exits next", commandFor(exits));

        CommandMenuEntry.Triple every = (CommandMenuEntry.Triple)
            EditorMenuPortalRows.exitEveryTripleFor("endless_repetition");
        assertEquals("Every 8", every.middleEntry().label());
        assertEquals("dungeontrain editor portals exitevery dec", commandFor(every.leftEntry()));
        assertEquals("dungeontrain editor portals exitevery inc", commandFor(every.rightEntry()));
        assertEquals("dungeontrain editor portals exitevery", typePrefixFor(every.middleEntry()));

        // Random reads the same number the other way round, so the row cannot be misread.
        assertEquals("1 in 5", ((CommandMenuEntry.Triple)
            EditorMenuPortalRows.exitEveryTripleFor("endless_repetition/exact/off/random:5"))
            .middleEntry().label());
    }

    @Test
    @DisplayName("portals: the moved-exit stepper shows under Random alone")
    void portals_exitMoveCommands() {
        CommandMenuEntry.Triple move = (CommandMenuEntry.Triple)
            EditorMenuPortalRows.exitMoveTripleFor("endless_repetition/exact/off/random:8:7");
        assertEquals("Moved exit: 7/10", move.middleEntry().label());
        assertEquals("dungeontrain editor portals exitmove dec", commandFor(move.leftEntry()));
        assertEquals("dungeontrain editor portals exitmove inc", commandFor(move.rightEntry()));
        assertEquals("dungeontrain editor portals exitmove", typePrefixFor(move.middleEntry()));

        // It still shows at zero — that is the dial's own "never", not an absent control.
        assertEquals("Moved exit: 0/10", ((CommandMenuEntry.Triple)
            EditorMenuPortalRows.exitMoveTripleFor("endless_repetition/exact/off/random")).
            middleEntry().label());

        // …but never under the lattice, under Off, or on a room with no Exits control at all.
        assertNull(EditorMenuPortalRows.exitMoveTripleFor("endless_repetition"));
        assertNull(EditorMenuPortalRows.exitMoveTripleFor("endless_repetition/exact/off/off"));
        assertNull(EditorMenuPortalRows.exitMoveTripleFor("bedrock_lock"));
        assertNull(EditorMenuPortalRows.exitMoveTripleFor(
            games.brennan.dungeontrain.net.EditorStatusPacket.NO_MODE));
    }

    @Test
    @DisplayName("Exits is absent for a sealed room, and its spacing is absent when nothing is laid")
    void portals_exitsRowsAbsentWhereTheyMeanNothing() {
        // Only an endless room has anywhere to put an extra way back to the train.
        assertNull(EditorMenuPortalRows.exitsRowFor("bedrock_lock"));
        assertNull(EditorMenuPortalRows.exitsRowFor("bedrockless"));
        assertNull(EditorMenuPortalRows.exitsRowFor(
            games.brennan.dungeontrain.net.EditorStatusPacket.NO_MODE));

        // Endless Open is asked the question and answers Off, which takes the spacing with it.
        assertEquals("Exits: Off",
            assertInstanceOf(CommandMenuEntry.Stay.class,
                EditorMenuPortalRows.exitsRowFor("endless_open")).label());
        assertNull(EditorMenuPortalRows.exitEveryTripleFor("endless_open"));
        assertNull(EditorMenuPortalRows.exitEveryTripleFor("endless_repetition/exact/off/off"));
    }

    @Test
    @DisplayName("portals: New and Remove use the portals prefix, not the tracks one")
    void portals_newAndRemove() {
        CommandMenuEntry newEntry = EditorMenuScreen.newEntryFor(PlotCategory.PORTALS, "portal_room", "portal room / default");
        assertEquals("dungeontrain editor portals new portal_room", typePrefixFor(newEntry));
        assertNotNull(EditorMenuScreen.removeEntryFor(PlotCategory.PORTALS, "portal_room", "portal room / default"));
    }

    private static CommandMenuEntry.Triple weightTripleAssertingPresent(
        PlotCategory category, String modelId, String modelName, int currentWeight
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

    // ---- Tabs: which rows land where, and what happens when a tab is empty ----
    //
    // rowsByTab takes the context explicitly so these run without the client HUD
    // state (EditorStatusHudOverlay) being stood up. The portal-room rows read the
    // HUD for the room mode, so a portals context yields only its non-portal rows
    // here — enough to pin placement, which is what these guard.

    private static Map<EditorMenuTab, List<CommandMenuEntry>> tabsFor(PlotCategory category, String model) {
        return EditorMenuScreen.rowsByTab(category, model, model, model, 10);
    }

    /** Flattened labels of every cell in a tab, so a cell can be found regardless of row nesting. */
    private static List<String> labelsIn(List<CommandMenuEntry> rows) {
        List<String> out = new ArrayList<>();
        for (CommandMenuEntry row : rows) collectLabels(row, out);
        return out;
    }

    private static void collectLabels(CommandMenuEntry e, List<String> out) {
        if (e instanceof CommandMenuEntry.Split s) {
            collectLabels(s.leftEntry(), out);
            collectLabels(s.rightEntry(), out);
        } else if (e instanceof CommandMenuEntry.Triple t) {
            collectLabels(t.leftEntry(), out);
            collectLabels(t.middleEntry(), out);
            collectLabels(t.rightEntry(), out);
        } else if (e instanceof CommandMenuEntry.Quad q) {
            collectLabels(q.e1(), out);
            collectLabels(q.e2(), out);
            collectLabels(q.e3(), out);
            collectLabels(q.e4(), out);
        } else {
            out.add(e.label());
        }
    }

    private static void collectRunCommands(CommandMenuEntry e, List<String> out) {
        if (e instanceof CommandMenuEntry.Split s) {
            collectRunCommands(s.leftEntry(), out);
            collectRunCommands(s.rightEntry(), out);
        } else if (e instanceof CommandMenuEntry.Run r) {
            out.add(r.command());
        }
    }

    @Test
    @DisplayName("File carries the template lifecycle rows, and New comes first")
    void file_tab_holdsLifecycleRows() {
        List<CommandMenuEntry> file = tabsFor(PlotCategory.CARRIAGES, "brass_dining").get(EditorMenuTab.FILE);
        List<String> labels = labelsIn(file);
        assertTrue(labels.containsAll(List.of("New", "Remove", "Save", "All", "Undo", "Redo",
            "Reset", "Clear", "Rename", "Package")), "File tab labels were " + labels);
        assertEquals("New", labels.get(0), "New should lead the File tab");
        // Nav and Settings rows must not leak into File.
        assertFalse(labels.contains("Exit"));
        assertFalse(labels.contains("Enter"));
        assertFalse(labels.contains("Rebuild"));
    }

    @Test
    @DisplayName("Nav carries Enter and Exit; Test the Carriage only for portals")
    void nav_tab_holdsNavigationRows() {
        List<String> carriages = labelsIn(tabsFor(PlotCategory.CARRIAGES, "brass_dining").get(EditorMenuTab.NAV));
        assertEquals(List.of("Enter", "Exit"), carriages);

        List<CommandMenuEntry> portalNav = tabsFor(PlotCategory.PORTALS, "crypt_hall").get(EditorMenuTab.NAV);
        assertEquals(List.of("Enter", "Test the Carriage", "Exit"), labelsIn(portalNav));

        // Test drills into the save prompt rather than dispatching the command — a dirty room has
        // to be offered a save before it is stamped from its last one.
        assertInstanceOf(PortalTestSaveCheckScreen.class,
            assertInstanceOf(CommandMenuEntry.DrillIn.class, portalNav.get(1)).target());
    }

    @Test
    @DisplayName("Settings carries Editor Menus, the mirror group and Stages — never Save")
    void settings_tab_holdsEditorPreferences() {
        List<String> labels = labelsIn(tabsFor(PlotCategory.CARRIAGES, "brass_dining").get(EditorMenuTab.SETTINGS));
        assertTrue(labels.contains("Editor Menus"), "Settings labels were " + labels);
        assertTrue(labels.containsAll(List.of("Mirror", "X", "Y", "Z", "V", "Rebuild")));
        assertTrue(labels.contains("Stages"));
        assertFalse(labels.contains("Save"));
    }

    @Test
    @DisplayName("architecture hides the Current tab — it has no per-model properties")
    void architecture_hidesCurrentTab() {
        Map<EditorMenuTab, List<CommandMenuEntry>> tabs = tabsFor(PlotCategory.ARCHITECTURE, "arch_span");
        assertTrue(tabs.get(EditorMenuTab.CURRENT).isEmpty());
        assertEquals(
            List.of(EditorMenuTab.FILE, EditorMenuTab.SETTINGS, EditorMenuTab.NAV),
            EditorMenuScreen.visibleTabs(tabs));
    }

    @Test
    @DisplayName("architecture keeps Reset as a solo row — it has no Clear and no New")
    void architecture_resetIsSolo() {
        List<String> labels = labelsIn(tabsFor(PlotCategory.ARCHITECTURE, "arch_span").get(EditorMenuTab.FILE));
        assertTrue(labels.contains("Reset"));
        assertFalse(labels.contains("Clear"));
        assertFalse(labels.contains("New"), "architecture has no author-authored models");
    }

    @Test
    @DisplayName("parts Save routes through the part-aware subcommand, not the generic one")
    void parts_saveIsPartAware() {
        List<CommandMenuEntry> file = tabsFor(PlotCategory.PARTS, "wheelset:heavy").get(EditorMenuTab.FILE);
        List<String> commands = new ArrayList<>();
        for (CommandMenuEntry row : file) collectRunCommands(row, commands);
        assertTrue(commands.contains("dungeontrain editor part save"),
            "parts must not fall through to the generic save; commands were " + commands);
        assertTrue(commands.contains("dungeontrain editor part save all"));
        assertFalse(commands.contains("dungeontrain save"));
        assertFalse(commands.contains("dungeontrain save all"));
    }

    @Test
    @DisplayName("parts has no Reset row, no Current tab and no Stages")
    void parts_hasNoResetAndNoCurrentTab() {
        Map<EditorMenuTab, List<CommandMenuEntry>> tabs = tabsFor(PlotCategory.PARTS, "wheelset:heavy");
        List<String> file = labelsIn(tabs.get(EditorMenuTab.FILE));
        assertTrue(file.contains("Clear"));
        assertFalse(file.contains("Reset"), "parts plots have no on-disk template to reset");
        assertTrue(tabs.get(EditorMenuTab.CURRENT).isEmpty());
        // Stages is a template-registry concept and stays out of the parts menu.
        assertFalse(labelsIn(tabs.get(EditorMenuTab.SETTINGS)).contains("Stages"));
    }

    // ---- Tab selection survives a category that cannot show it ----

    @Test
    @DisplayName("resolve keeps the chosen tab when visible, and falls back when it is not")
    void resolve_fallsBackToFirstVisibleTab() {
        EditorMenuTab.select(EditorMenuTab.CURRENT);
        assertEquals(EditorMenuTab.CURRENT, EditorMenuTab.resolve(
            List.of(EditorMenuTab.FILE, EditorMenuTab.CURRENT, EditorMenuTab.NAV)));

        // Walking into an architecture plot while on Current must not render an empty panel.
        assertEquals(EditorMenuTab.FILE, EditorMenuTab.resolve(
            List.of(EditorMenuTab.FILE, EditorMenuTab.SETTINGS, EditorMenuTab.NAV)));

        // ...and the remembered choice is untouched, so stepping back restores it.
        assertEquals(EditorMenuTab.CURRENT, EditorMenuTab.active());
    }

    // ---- Header Save icon + panel width ----

    @Test
    @DisplayName("Header Save mirrors the File-tab Save command for ordinary categories")
    void headerSave_ordinaryCategoryUsesSave() {
        MenuHeaderAction a = EditorMenuScreen.saveHeaderAction(PlotCategory.CARRIAGES, false, 0L);
        assertEquals("Save", a.label());
        assertEquals(EditorSaveStatus.CLEAN_TINT, a.tint());
        assertEquals(EditorMenuScreen.SAVE_COMMAND, a.command());
        assertEquals("dungeontrain save", a.command());
        assertEquals("dungeontrain", a.icon().getNamespace());
        assertEquals("icon/save", a.icon().getPath());
    }

    @Test
    @DisplayName("Header Save routes parts through the part-aware subcommand")
    void headerSave_partsUsesPartSave() {
        MenuHeaderAction a = EditorMenuScreen.saveHeaderAction(PlotCategory.PARTS, false, 0L);
        assertEquals("dungeontrain editor part save", a.command());
    }

    @Test
    @DisplayName("Header Save names the unsaved state in its tooltip and goes green")
    void headerSave_dirtyIsGreenAndSaysSo() {
        MenuHeaderAction a = EditorMenuScreen.saveHeaderAction(PlotCategory.CARRIAGES, true, 250L);
        assertTrue(a.label().contains("unsaved"));
        assertEquals(EditorSaveStatus.DIRTY_TINT, a.tint(), "at the pulse peak the tint is the full green");
    }

    @Test
    @DisplayName("Editor panel keeps the shared default width on every tab (no Current-tab widening)")
    void panelWidth_isSharedDefault() {
        assertEquals(CommandMenuLayout.PANEL_WIDTH, new EditorMenuScreen().panelWidth(), 0.0);
    }
}
