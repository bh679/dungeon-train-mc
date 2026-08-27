package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the exact slash commands {@link EditorPlotTeleport} builds.
 *
 * <p>These strings are dispatched verbatim through {@code CommandRunner}, so a wrong
 * switch arm is a silently wrong command rather than a crash. The command strings are
 * what these tests hold still — they are byte-identical to the ones the builders emitted
 * when they took a {@code String} category, which is the evidence that re-typing them to
 * {@link PlotCategory} changed no behaviour.</p>
 *
 * <p>What did change: the builders no longer have a {@code default -> null} arm, so a
 * category they do not handle is a compile error rather than a silently dropped click,
 * and case has stopped deciding anything —
 * {@code stageApplyCommandFor} used to be the only builder that normalised it.</p>
 */
final class EditorPlotTeleportTest {

    @Test
    @DisplayName("commandFor: one teleport shape per category, parts included")
    void commandFor_perCategory() {
        assertEquals("dungeontrain editor enter std",
            EditorPlotTeleport.commandFor(PlotCategory.CARRIAGES, "std", "ignored"));
        assertEquals("dungeontrain editor contents enter crates",
            EditorPlotTeleport.commandFor(PlotCategory.CONTENTS, "crates", "ignored"));
        // Portals address the room by name — modelId carries nothing the command needs.
        assertEquals("dungeontrain editor portals enter library",
            EditorPlotTeleport.commandFor(PlotCategory.PORTALS, "portal_room", "library"));
        // Parts: modelId is the kind tag, modelName the variant.
        assertEquals("dungeontrain editor part enter floor checker",
            EditorPlotTeleport.commandFor(PlotCategory.PARTS, "floor", "checker"));
        // Nothing authored yet, so no plot to stand in.
        assertNull(EditorPlotTeleport.commandFor(PlotCategory.ARCHITECTURE, "x", "y"));
    }

    @Test
    @DisplayName("weightCommandFor: parts have no weight pool, the other four do")
    void weightCommandFor_partsHaveNoPool() {
        assertEquals("dungeontrain editor weight std inc",
            EditorPlotTeleport.weightCommandFor(PlotCategory.CARRIAGES, "std", "ignored", "inc"));
        assertEquals("dungeontrain editor contents weight crates dec",
            EditorPlotTeleport.weightCommandFor(PlotCategory.CONTENTS, "crates", "ignored", "dec"));
        assertEquals("dungeontrain editor tracks weight pillar_top fancy inc",
            EditorPlotTeleport.weightCommandFor(PlotCategory.TRACKS, "pillar_top", "fancy", "inc"));
        assertEquals("dungeontrain editor portals weight portal_room library inc",
            EditorPlotTeleport.weightCommandFor(PlotCategory.PORTALS, "portal_room", "library", "inc"));
        assertNull(EditorPlotTeleport.weightCommandFor(PlotCategory.PARTS, "floor", "checker", "inc"));
    }

    @Test
    @DisplayName("levelCommandFor and phaseCommandFor: gate edits, null for parts")
    void gateCommands_perCategory() {
        assertEquals("dungeontrain editor minlevel std inc",
            EditorPlotTeleport.levelCommandFor(PlotCategory.CARRIAGES, "std", "ignored", "minlevel", "inc"));
        assertEquals("dungeontrain editor tracks maxlevel pillar_top fancy dec",
            EditorPlotTeleport.levelCommandFor(PlotCategory.TRACKS, "pillar_top", "fancy", "maxlevel", "dec"));
        assertNull(EditorPlotTeleport.levelCommandFor(PlotCategory.PARTS, "floor", "checker", "minlevel", "inc"));

        assertEquals("dungeontrain editor phase std nether on",
            EditorPlotTeleport.phaseCommandFor(PlotCategory.CARRIAGES, "std", "ignored", "nether", "on"));
        assertNull(EditorPlotTeleport.phaseCommandFor(PlotCategory.PARTS, "floor", "checker", "nether", "on"));
    }

    @Test
    @DisplayName("portal-only rows: every builder is null outside PORTALS")
    void portalOnlyCommands_areNullElsewhere() {
        assertEquals("dungeontrain editor portals length inc",
            EditorPlotTeleport.dimensionCommandFor(PlotCategory.PORTALS, "length", "inc"));
        assertEquals("dungeontrain editor portals mode next",
            EditorPlotTeleport.modeCycleCommandFor(PlotCategory.PORTALS));

        for (PlotCategory other : PlotCategory.values()) {
            if (other == PlotCategory.PORTALS) continue;
            assertNull(EditorPlotTeleport.dimensionCommandFor(other, "length", "inc"), other.name());
            assertNull(EditorPlotTeleport.modeCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.exitsCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.roomSkyCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.doorWallCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.roomContentsCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.roomBooksCycleCommandFor(other), other.name());
            assertNull(EditorPlotTeleport.copiesCycleCommandFor(other), other.name());
        }
    }

    @Test
    @DisplayName("stageApplyCommandFor: rooms route through the tracks command, parts do not route")
    void stageApply_perCategory() {
        assertEquals("dungeontrain editor stage apply carriage std night",
            EditorPlotTeleport.stageApplyCommandFor(PlotCategory.CARRIAGES, "std", "ignored", "night"));
        // A room is a TrackKind underneath, so its stage-apply route is the tracks one.
        assertEquals("dungeontrain editor stage apply tracks portal_room library night",
            EditorPlotTeleport.stageApplyCommandFor(PlotCategory.PORTALS, "portal_room", "library", "night"));
        assertNull(EditorPlotTeleport.stageApplyCommandFor(PlotCategory.PARTS, "floor", "checker", "night"));
    }

    @Test
    @DisplayName("case no longer decides whether a builder routes")
    void everyBuilder_takesTheSameTypeNow() {
        // StagePickerScreen is reached from two callers that disagreed about case: the keyboard
        // menu passed "carriages", the world-space variant row "CARRIAGES". stageApplyCommandFor
        // normalised and its siblings did not, so the same click routed through one and fell out
        // of the others. Both spellings now parse to one value before they reach any builder.
        PlotCategory fromKeyboardMenu = PlotCategory.fromId("carriages").orElseThrow();
        PlotCategory fromWorldSpaceRow = PlotCategory.fromId("CARRIAGES").orElseThrow();
        assertEquals(fromKeyboardMenu, fromWorldSpaceRow);

        assertEquals(
            EditorPlotTeleport.commandFor(fromWorldSpaceRow, "std", "n"),
            EditorPlotTeleport.commandFor(fromKeyboardMenu, "std", "n"));
        assertEquals(
            EditorPlotTeleport.stageApplyCommandFor(fromWorldSpaceRow, "std", "n", "night"),
            EditorPlotTeleport.stageApplyCommandFor(fromKeyboardMenu, "std", "n", "night"));
    }

    @Test
    @DisplayName("a row with no category routes nowhere rather than throwing")
    void noCategory_routesNowhere() {
        // What "stages", "" and any unrecognised value resolve to before reaching a builder.
        PlotCategory none = PlotCategory.fromId("stages").orElse(null);
        assertNull(none);
        assertNull(EditorPlotTeleport.commandFor(none, "x", "y"));
        assertNull(EditorPlotTeleport.stageApplyCommandFor(none, "x", "y", "night"));
        assertNull(EditorPlotTeleport.weightCommandFor(none, "x", "y", "inc"));
        assertNull(EditorPlotTeleport.modeCycleCommandFor(none));
    }
}
