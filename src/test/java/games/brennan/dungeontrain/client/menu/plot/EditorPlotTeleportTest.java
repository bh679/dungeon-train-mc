package games.brennan.dungeontrain.client.menu.plot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the exact slash commands {@link EditorPlotTeleport} builds.
 *
 * <p>These strings are dispatched verbatim through {@code CommandRunner}, so a
 * wrong switch arm is a silently wrong command rather than a crash — the
 * command strings, not just non-nullness, are what these tests hold still.
 * That matters because this class is about to be re-typed from {@code String}
 * category to an enum; the arguments change, the emitted commands must not.</p>
 *
 * <p>Also pins the case asymmetry that motivated the change:
 * {@link EditorPlotTeleport#stageApplyCommandFor} normalises case while its five
 * siblings do not, so the same lowercase category routes through one and falls
 * out of the others. {@code stageApplyCase_isTheOnlyForgivingBuilder} documents
 * that as it stands today.</p>
 */
final class EditorPlotTeleportTest {

    @Test
    @DisplayName("commandFor: one teleport shape per category, parts included")
    void commandFor_perCategory() {
        assertEquals("dungeontrain editor enter std",
            EditorPlotTeleport.commandFor("CARRIAGES", "std", "ignored"));
        assertEquals("dungeontrain editor contents enter crates",
            EditorPlotTeleport.commandFor("CONTENTS", "crates", "ignored"));
        // Portals address the room by name — modelId carries nothing the command needs.
        assertEquals("dungeontrain editor portals enter library",
            EditorPlotTeleport.commandFor("PORTALS", "portal_room", "library"));
        // Parts: modelId is the kind tag, modelName the variant.
        assertEquals("dungeontrain editor part enter floor checker",
            EditorPlotTeleport.commandFor("PARTS", "floor", "checker"));
    }

    @Test
    @DisplayName("weightCommandFor: parts have no weight pool, the other four do")
    void weightCommandFor_partsHaveNoPool() {
        assertEquals("dungeontrain editor weight std inc",
            EditorPlotTeleport.weightCommandFor("CARRIAGES", "std", "ignored", "inc"));
        assertEquals("dungeontrain editor contents weight crates dec",
            EditorPlotTeleport.weightCommandFor("CONTENTS", "crates", "ignored", "dec"));
        assertEquals("dungeontrain editor tracks weight pillar_top fancy inc",
            EditorPlotTeleport.weightCommandFor("TRACKS", "pillar_top", "fancy", "inc"));
        assertEquals("dungeontrain editor portals weight portal_room library inc",
            EditorPlotTeleport.weightCommandFor("PORTALS", "portal_room", "library", "inc"));
        assertNull(EditorPlotTeleport.weightCommandFor("PARTS", "floor", "checker", "inc"));
    }

    @Test
    @DisplayName("levelCommandFor and phaseCommandFor: gate edits, null for parts")
    void gateCommands_perCategory() {
        assertEquals("dungeontrain editor minlevel std inc",
            EditorPlotTeleport.levelCommandFor("CARRIAGES", "std", "ignored", "minlevel", "inc"));
        assertEquals("dungeontrain editor tracks maxlevel pillar_top fancy dec",
            EditorPlotTeleport.levelCommandFor("TRACKS", "pillar_top", "fancy", "maxlevel", "dec"));
        assertNull(EditorPlotTeleport.levelCommandFor("PARTS", "floor", "checker", "minlevel", "inc"));

        assertEquals("dungeontrain editor phase std nether on",
            EditorPlotTeleport.phaseCommandFor("CARRIAGES", "std", "ignored", "nether", "on"));
        assertNull(EditorPlotTeleport.phaseCommandFor("PARTS", "floor", "checker", "nether", "on"));
    }

    @Test
    @DisplayName("portal-only rows: every builder is null outside PORTALS")
    void portalOnlyCommands_areNullElsewhere() {
        assertEquals("dungeontrain editor portals length inc",
            EditorPlotTeleport.dimensionCommandFor("PORTALS", "length", "inc"));
        assertEquals("dungeontrain editor portals mode next",
            EditorPlotTeleport.modeCycleCommandFor("PORTALS"));

        for (String other : new String[]{"CARRIAGES", "CONTENTS", "TRACKS", "PARTS"}) {
            assertNull(EditorPlotTeleport.dimensionCommandFor(other, "length", "inc"), other);
            assertNull(EditorPlotTeleport.modeCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.exitsCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.roomSkyCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.doorWallCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.roomContentsCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.roomBooksCycleCommandFor(other), other);
            assertNull(EditorPlotTeleport.copiesCycleCommandFor(other), other);
        }
    }

    @Test
    @DisplayName("stageApplyCommandFor: rooms route through the tracks command, parts do not route")
    void stageApply_perCategory() {
        assertEquals("dungeontrain editor stage apply carriage std night",
            EditorPlotTeleport.stageApplyCommandFor("CARRIAGES", "std", "ignored", "night"));
        // A room is a TrackKind underneath, so its stage-apply route is the tracks one.
        assertEquals("dungeontrain editor stage apply tracks portal_room library night",
            EditorPlotTeleport.stageApplyCommandFor("PORTALS", "portal_room", "library", "night"));
        assertNull(EditorPlotTeleport.stageApplyCommandFor("PARTS", "floor", "checker", "night"));
    }

    @Test
    @DisplayName("today: stageApplyCommandFor forgives case, its siblings do not")
    void stageApplyCase_isTheOnlyForgivingBuilder() {
        // StagePickerScreen genuinely receives both cases from its two callers, so this
        // builder normalises — and is alone in doing so.
        assertEquals(
            EditorPlotTeleport.stageApplyCommandFor("CARRIAGES", "std", "n", "night"),
            EditorPlotTeleport.stageApplyCommandFor("carriages", "std", "n", "night"));
        // The siblings drop the same input on the floor.
        assertNull(EditorPlotTeleport.commandFor("carriages", "std", "n"));
        assertNull(EditorPlotTeleport.weightCommandFor("carriages", "std", "n", "inc"));
    }

    @Test
    @DisplayName("the stages sentinel is not a category and routes nowhere")
    void stagesSentinel_routesNowhere() {
        // EditorTypeMenus writes the lowercase sentinel "stages" into the otherwise-uppercase
        // Variant.category field. It must never resolve to a teleport target.
        assertNull(EditorPlotTeleport.commandFor("stages", "x", "y"));
        assertNull(EditorPlotTeleport.stageApplyCommandFor("stages", "x", "y", "night"));
        assertNull(EditorPlotTeleport.commandFor("", "x", "y"));
    }
}
