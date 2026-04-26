package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link VariantEditorPreviewState}'s pin storage. The state
 * is a process-wide singleton so each test cleans up its own keys to keep
 * test order independent.
 */
final class VariantEditorPreviewStateTest {

    private static final String PLOT_A = "carriage:test_a";
    private static final String PLOT_B = "carriage:test_b";

    @AfterEach
    void clearAll() {
        // Defensive cleanup — clearPinned tolerates missing keys.
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    VariantEditorPreviewState.clearPinned(PLOT_A, p);
                    VariantEditorPreviewState.clearPinned(PLOT_B, p);
                }
            }
        }
    }

    @Test
    @DisplayName("pinnedEntry returns null when nothing was set")
    void pin_unsetReturnsNull() {
        assertNull(VariantEditorPreviewState.pinnedEntry(PLOT_A, new BlockPos(1, 2, 3)));
    }

    @Test
    @DisplayName("setPinned then pinnedEntry returns the stored index")
    void pin_roundTrip() {
        VariantEditorPreviewState.setPinned(PLOT_A, new BlockPos(1, 2, 3), 5);
        assertEquals(Integer.valueOf(5),
            VariantEditorPreviewState.pinnedEntry(PLOT_A, new BlockPos(1, 2, 3)));
    }

    @Test
    @DisplayName("clearPinned removes the stored value")
    void pin_clear() {
        BlockPos p = new BlockPos(1, 2, 3);
        VariantEditorPreviewState.setPinned(PLOT_A, p, 2);
        VariantEditorPreviewState.clearPinned(PLOT_A, p);
        assertNull(VariantEditorPreviewState.pinnedEntry(PLOT_A, p));
    }

    @Test
    @DisplayName("setPinned overwrites the previous index for the same cell")
    void pin_overwrite() {
        BlockPos p = new BlockPos(0, 0, 0);
        VariantEditorPreviewState.setPinned(PLOT_A, p, 3);
        VariantEditorPreviewState.setPinned(PLOT_A, p, 7);
        assertEquals(Integer.valueOf(7),
            VariantEditorPreviewState.pinnedEntry(PLOT_A, p));
    }

    @Test
    @DisplayName("Pins are scoped per plot key")
    void pin_perPlotIsolated() {
        BlockPos p = new BlockPos(1, 1, 1);
        VariantEditorPreviewState.setPinned(PLOT_A, p, 4);
        assertNull(VariantEditorPreviewState.pinnedEntry(PLOT_B, p));
    }

    @Test
    @DisplayName("Negative entry indices are silently ignored")
    void pin_rejectsNegative() {
        BlockPos p = new BlockPos(0, 0, 0);
        VariantEditorPreviewState.setPinned(PLOT_A, p, -1);
        assertNull(VariantEditorPreviewState.pinnedEntry(PLOT_A, p));
    }

    @Test
    @DisplayName("Null plotKey or localPos is silently ignored on set/clear/get")
    void pin_nullSafe() {
        VariantEditorPreviewState.setPinned(null, new BlockPos(0, 0, 0), 1);
        VariantEditorPreviewState.setPinned(PLOT_A, null, 1);
        VariantEditorPreviewState.clearPinned(null, new BlockPos(0, 0, 0));
        VariantEditorPreviewState.clearPinned(PLOT_A, null);
        assertNull(VariantEditorPreviewState.pinnedEntry(null, new BlockPos(0, 0, 0)));
        assertNull(VariantEditorPreviewState.pinnedEntry(PLOT_A, null));
    }
}
