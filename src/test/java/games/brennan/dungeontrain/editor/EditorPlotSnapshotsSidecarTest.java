package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidecar-edit half of {@link EditorPlotSnapshots}: a variant-pool change reads as unsaved
 * until the plot's baseline is retaken, and the drilldown can name the cells.
 *
 * <p>{@code capture} needs a live level, so the reset is exercised through {@link
 * EditorPlotSnapshots#clear}, which drops the same entry.</p>
 */
final class EditorPlotSnapshotsSidecarTest {

    private static final String KEY = EditorPlotSnapshots.key("carriages", "test-sidecar");

    @AfterEach
    void reset() {
        EditorPlotSnapshots.clear(KEY);
    }

    @Test
    @DisplayName("an untouched plot has no sidecar edits")
    void cleanByDefault() {
        assertFalse(EditorPlotSnapshots.sidecarEdited(KEY));
        assertTrue(EditorPlotSnapshots.sidecarEdits(KEY).isEmpty());
    }

    @Test
    @DisplayName("marking a cell reads as edited and names the cell")
    void markedCellIsEdited() {
        BlockPos cell = new BlockPos(2, 1, 3);
        EditorPlotSnapshots.markSidecarEdit(KEY, cell);
        assertTrue(EditorPlotSnapshots.sidecarEdited(KEY));
        assertEquals(Set.of(cell), EditorPlotSnapshots.sidecarEdits(KEY));
    }

    @Test
    @DisplayName("a settings edit with no cell still reads as edited")
    void settingsEditIsEdited() {
        EditorPlotSnapshots.markSidecarEdit(KEY, null);
        assertTrue(EditorPlotSnapshots.sidecarEdited(KEY));
        assertTrue(EditorPlotSnapshots.sidecarEdits(KEY).isEmpty());
    }

    @Test
    @DisplayName("a plot with no scan row (null key) is ignored")
    void nullKeyIgnored() {
        EditorPlotSnapshots.markSidecarEdit(null, new BlockPos(1, 1, 1));
        assertFalse(EditorPlotSnapshots.sidecarEdited(KEY));
    }

    @Test
    @DisplayName("retaking the baseline clears the edit")
    void clearResets() {
        EditorPlotSnapshots.markSidecarEdit(KEY, new BlockPos(0, 0, 0));
        EditorPlotSnapshots.clear(KEY);
        assertFalse(EditorPlotSnapshots.sidecarEdited(KEY));
    }
}
