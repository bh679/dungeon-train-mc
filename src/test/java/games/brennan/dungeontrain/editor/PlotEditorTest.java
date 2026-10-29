package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-free halves of {@link PlotEditor}: the containment walk and the enter decision. */
final class PlotEditorTest {

    private static final Vec3i BOX = new Vec3i(4, 2, 7);
    /** Two plots on one row, GAP apart — "a" at z=0, "b" at z=12. */
    private static final Map<String, BlockPos> ORIGINS = Map.of(
        "a", new BlockPos(0, 230, 0),
        "b", new BlockPos(0, 230, 12));

    private static Optional<String> locate(BlockPos pos) {
        return PlotEditor.locate(pos, List.of("a", "b"), ORIGINS::get, t -> BOX);
    }

    @Test
    @DisplayName("locate: inside the footprint, on the cage, and in the roof headroom all count")
    void locate_footprintCageAndHeadroom() {
        assertEquals(Optional.of("a"), locate(new BlockPos(2, 231, 3)));
        assertEquals(Optional.of("a"), locate(new BlockPos(-1, 229, -1)), "cage corner");
        assertEquals(Optional.of("a"), locate(new BlockPos(2, 230 + 2 + EditorPlotBox.HEADROOM, 3)), "roof landing");
    }

    @Test
    @DisplayName("locate: one past the cage or headroom is outside")
    void locate_justOutside() {
        assertTrue(locate(new BlockPos(5, 231, 3)).isEmpty());
        assertTrue(locate(new BlockPos(2, 230 + 2 + EditorPlotBox.HEADROOM + 1, 3)).isEmpty());
        assertTrue(locate(new BlockPos(2, 231, 8)).isEmpty(), "the gap between a and b");
    }

    @Test
    @DisplayName("locate: the second plot on the row resolves to its own model")
    void locate_secondPlot() {
        assertEquals(Optional.of("b"), locate(new BlockPos(2, 231, 14)));
    }

    @Test
    @DisplayName("locate: a model with no origin is skipped, an empty row finds nothing")
    void locate_nullOriginAndEmpty() {
        assertEquals(Optional.of("b"),
            PlotEditor.locate(new BlockPos(2, 231, 14), List.of("x", "b"), t -> ORIGINS.get(t), t -> BOX));
        assertTrue(PlotEditor.locate(new BlockPos(2, 231, 3), List.of(), ORIGINS::get, t -> BOX).isEmpty());
    }

    @Test
    @DisplayName("enter decision: a reload always restamps")
    void entry_reloadStamps() {
        assertTrue(PlotEditor.PlotEntry.reload(true).stamp());
        assertTrue(PlotEditor.PlotEntry.reload(false).stamp());
    }

    @Test
    @DisplayName("enter decision: a walk restamps only when the player is not already in the plot")
    void entry_walkStampsUnlessStandingIn() {
        assertTrue(PlotEditor.PlotEntry.walk(true, false).stamp());
        assertFalse(PlotEditor.PlotEntry.walk(true, true).stamp());
        assertTrue(PlotEditor.PlotEntry.walk(false, false).onTop() == false);
    }

    @Test
    @DisplayName("enter decision: the panel's Enter lands inside at the asked cell, restamping unless standing in")
    void entry_insideKeepsAimAndNeverOnTop() {
        PlotEditor.PlotEntry centre = PlotEditor.PlotEntry.inside(EditorPlotArrival.Inside.CENTRE, true);
        assertFalse(centre.onTop());
        assertFalse(centre.stamp());
        assertEquals(EditorPlotArrival.Inside.CENTRE, centre.inside());
        assertTrue(PlotEditor.PlotEntry.inside(EditorPlotArrival.Inside.FRONT_DOOR, false).stamp());
    }
}
