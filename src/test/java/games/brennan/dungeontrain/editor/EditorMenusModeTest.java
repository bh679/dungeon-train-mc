package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.client.EditorMenusModeState;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EditorMenusMode} parsing and for the per-plot visibility rule
 * {@link EditorMenusModeState#isPanelVisible(List, int, EditorMenusMode)} that AUTO is built on.
 *
 * <p>The rule is a pure function of the label snapshot on purpose — the rendering it drives needs
 * a live client, but "which panels should be drawn" does not, and that is the part with the edge
 * cases (nothing in a plot, exactly one in a plot, an out-of-range index).</p>
 */
final class EditorMenusModeTest {

    private static EditorPlotLabelsPacket.Entry entry(String name, boolean inPlot) {
        return new EditorPlotLabelsPacket.Entry(
            BlockPos.ZERO, name, EditorPlotLabelsPacket.NO_WEIGHT,
            "CARRIAGES", name, name, inPlot, false, false);
    }

    @Test
    @DisplayName("parse: round-trips every id, case-insensitively")
    void parse_roundTrips() {
        for (EditorMenusMode m : EditorMenusMode.values()) {
            assertEquals(m, EditorMenusMode.parse(m.id()));
            assertEquals(m, EditorMenusMode.parse(m.id().toUpperCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    @DisplayName("parse: unknown, blank and null tokens fall back to the default")
    void parse_fallsBackToDefault() {
        assertEquals(EditorMenusMode.AUTO, EditorMenusMode.DEFAULT);
        assertEquals(EditorMenusMode.DEFAULT, EditorMenusMode.parse(null));
        assertEquals(EditorMenusMode.DEFAULT, EditorMenusMode.parse(""));
        assertEquals(EditorMenusMode.DEFAULT, EditorMenusMode.parse("sometimes"));
    }

    @Test
    @DisplayName("OFF hides every panel; ON shows every panel")
    void offHidesAll_onShowsAll() {
        List<EditorPlotLabelsPacket.Entry> snapshot =
            List.of(entry("a", false), entry("b", true), entry("c", false));
        for (int i = 0; i < snapshot.size(); i++) {
            assertFalse(EditorMenusModeState.isPanelVisible(snapshot, i, EditorMenusMode.OFF));
            assertTrue(EditorMenusModeState.isPanelVisible(snapshot, i, EditorMenusMode.ON));
        }
    }

    @Test
    @DisplayName("AUTO inside a plot: only that plot's panel draws")
    void autoInPlot_showsOnlyThatPlot() {
        List<EditorPlotLabelsPacket.Entry> snapshot =
            List.of(entry("a", false), entry("b", true), entry("c", false));
        assertFalse(EditorMenusModeState.isPanelVisible(snapshot, 0, EditorMenusMode.AUTO));
        assertTrue(EditorMenusModeState.isPanelVisible(snapshot, 1, EditorMenusMode.AUTO));
        assertFalse(EditorMenusModeState.isPanelVisible(snapshot, 2, EditorMenusMode.AUTO));
    }

    @Test
    @DisplayName("AUTO between plots: behaves like ON")
    void autoOutsidePlot_showsAll() {
        List<EditorPlotLabelsPacket.Entry> snapshot =
            List.of(entry("a", false), entry("b", false));
        for (int i = 0; i < snapshot.size(); i++) {
            assertTrue(EditorMenusModeState.isPanelVisible(snapshot, i, EditorMenusMode.AUTO));
        }
    }

    @Test
    @DisplayName("out-of-range indices and empty snapshots are not visible")
    void guardsAgainstBadIndices() {
        List<EditorPlotLabelsPacket.Entry> snapshot = List.of(entry("a", true));
        assertFalse(EditorMenusModeState.isPanelVisible(snapshot, -1, EditorMenusMode.ON));
        assertFalse(EditorMenusModeState.isPanelVisible(snapshot, 1, EditorMenusMode.ON));
        assertFalse(EditorMenusModeState.isPanelVisible(List.of(), 0, EditorMenusMode.AUTO));
        assertFalse(EditorMenusModeState.isPanelVisible(null, 0, EditorMenusMode.AUTO));
    }

    /** A generous cap, so these cases isolate AUTO's own in-template rule. */
    private static final int WIDE_CAP = 256;

    @Test
    @DisplayName("AUTO inside a template culls past 15 blocks, inclusive of the boundary")
    void autoCullsByDistance() {
        Vec3 cam = new Vec3(0, 64, 0);
        double max = EditorMenusModeState.AUTO_TEMPLATE_DISTANCE;
        assertTrue(EditorMenusModeState.withinRange(
            new Vec3(max - 0.5, 64, 0), cam, EditorMenusMode.AUTO, true, WIDE_CAP));
        // Exactly at the limit still draws — the cull is "further than", not "at least".
        assertTrue(EditorMenusModeState.withinRange(
            new Vec3(max, 64, 0), cam, EditorMenusMode.AUTO, true, WIDE_CAP));
        assertFalse(EditorMenusModeState.withinRange(
            new Vec3(max + 0.5, 64, 0), cam, EditorMenusMode.AUTO, true, WIDE_CAP));
        // Distance is 3D, not horizontal — a panel straight up is just as far away.
        assertFalse(EditorMenusModeState.withinRange(
            new Vec3(0, 64 + max + 1, 0), cam, EditorMenusMode.AUTO, true, WIDE_CAP));
        assertFalse(EditorMenusModeState.withinRange(
            new Vec3(12, 64 + 12, 0), cam, EditorMenusMode.AUTO, true, WIDE_CAP));
    }

    @Test
    @DisplayName("AUTO between plots: only the player's cap applies, not the in-template 15")
    void autoOutsideTemplate_usesOnlyTheCap() {
        Vec3 cam = new Vec3(0, 64, 0);
        // Between plots the whole board is how you find the next one — 15 does not apply...
        assertTrue(EditorMenusModeState.withinRange(
            new Vec3(EditorMenusModeState.AUTO_TEMPLATE_DISTANCE + 0.5, 64, 0),
            cam, EditorMenusMode.AUTO, false, WIDE_CAP));
        // ...but the player's own cap still does.
        assertTrue(EditorMenusModeState.withinRange(
            new Vec3(60, 64, 0), cam, EditorMenusMode.AUTO, false, 64));
        assertFalse(EditorMenusModeState.withinRange(
            new Vec3(70, 64, 0), cam, EditorMenusMode.AUTO, false, 64));
    }

    @Test
    @DisplayName("the smaller of the two distances wins inside a template")
    void smallerLimitWins() {
        Vec3 cam = new Vec3(0, 64, 0);
        Vec3 at12 = new Vec3(12, 64, 0);
        // Cap wider than 15 → 15 governs, so 12 blocks draws.
        assertTrue(EditorMenusModeState.withinRange(at12, cam, EditorMenusMode.AUTO, true, 64));
        // Cap tighter than 15 → the cap governs, so the same panel goes.
        assertFalse(EditorMenusModeState.withinRange(at12, cam, EditorMenusMode.AUTO, true, 8));
    }

    @Test
    @DisplayName("ON is capped by the setting too, in a template or not; OFF never gets asked")
    void onIsCappedButKnowsNothingOfTheTemplateRule() {
        Vec3 cam = new Vec3(0, 64, 0);
        Vec3 at20 = new Vec3(20, 64, 0);
        // Past AUTO's 15 but inside the cap — ON draws it whether or not you are in a template.
        assertTrue(EditorMenusModeState.withinRange(at20, cam, EditorMenusMode.ON, true, 64));
        assertTrue(EditorMenusModeState.withinRange(at20, cam, EditorMenusMode.ON, false, 64));
        // ...and the cap still bites in ON.
        assertFalse(EditorMenusModeState.withinRange(
            new Vec3(500, 64, 500), cam, EditorMenusMode.ON, false, 64));
        // OFF has drawn nothing by the time anything asks.
        assertTrue(EditorMenusModeState.withinRange(
            new Vec3(500, 64, 500), cam, EditorMenusMode.OFF, true, 8));
    }

    @Test
    @DisplayName("the Menu Distance stepper snaps to multiples of 8 and clamps at both ends")
    void stepperSnapsAndClamps() {
        int min = ClientDisplayConfig.MIN_MENU_RENDER_DISTANCE;
        int max = ClientDisplayConfig.MAX_MENU_RENDER_DISTANCE;
        // Up from the floor lands on the first multiple of the step, not floor+step.
        assertEquals(8, ClientDisplayConfig.stepMenuRenderDistanceUp(min));
        assertEquals(16, ClientDisplayConfig.stepMenuRenderDistanceUp(8));
        assertEquals(72, ClientDisplayConfig.stepMenuRenderDistanceUp(64));
        assertEquals(max, ClientDisplayConfig.stepMenuRenderDistanceUp(max));
        assertEquals(max, ClientDisplayConfig.stepMenuRenderDistanceUp(max - 1));
        // Down snaps a hand-edited off-step value onto the ladder rather than stepping past it.
        assertEquals(56, ClientDisplayConfig.stepMenuRenderDistanceDown(64));
        assertEquals(64, ClientDisplayConfig.stepMenuRenderDistanceDown(70));
        assertEquals(min, ClientDisplayConfig.stepMenuRenderDistanceDown(8));
        assertEquals(min, ClientDisplayConfig.stepMenuRenderDistanceDown(min));
    }

    @Test
    @DisplayName("the live-mode overload defaults to AUTO before any packet arrives")
    void liveModeDefaultsToAuto() {
        EditorMenusModeState.reset();
        assertEquals(EditorMenusMode.AUTO, EditorMenusModeState.mode());
        assertTrue(EditorMenusModeState.menusVisible());
        List<EditorPlotLabelsPacket.Entry> snapshot = List.of(entry("a", false), entry("b", true));
        assertFalse(EditorMenusModeState.isPanelVisible(snapshot, 0));
        assertTrue(EditorMenusModeState.isPanelVisible(snapshot, 1));
    }
}
