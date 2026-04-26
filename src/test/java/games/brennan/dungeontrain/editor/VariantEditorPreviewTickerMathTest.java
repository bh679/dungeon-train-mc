package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the entry-cycle math used by
 * {@link VariantEditorPreviewTicker#pickEntryIndex}. The tick handler
 * itself reads from {@link net.minecraft.server.level.ServerLevel} so it
 * can't run without a Forge bootstrap, but the cycle math is a pure
 * function of {@code (plotKey, localPos, entryCount, previewTick)} and is
 * exercised in isolation here.
 *
 * <p>Preview tick contract: 1 Hz (so {@code previewTick = gameTime / 20})
 * and entry slot length is 3 preview ticks (3 seconds).</p>
 */
final class VariantEditorPreviewTickerMathTest {

    private static final String PLOT = "carriage:test";

    @AfterEach
    void clearPins() {
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    VariantEditorPreviewState.clearPinned(PLOT, new BlockPos(x, y, z));
                }
            }
        }
    }

    @Test
    @DisplayName("pickEntryIndex with no pin advances every 3 preview ticks")
    void cycle_advancesEvery3Ticks() {
        BlockPos p = new BlockPos(1, 0, 0);
        // 3 entries, no pin: should land on 0,0,0,1,1,1,2,2,2,0,0,0,...
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 0));
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 1));
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 2));
        assertEquals(1, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 3));
        assertEquals(1, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 5));
        assertEquals(2, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 6));
        assertEquals(2, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 8));
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 9));
    }

    @Test
    @DisplayName("Single-entry cell always returns 0 regardless of tick")
    void cycle_singleEntryFixed() {
        BlockPos p = new BlockPos(0, 0, 0);
        for (long t = 0; t < 100; t++) {
            assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 1, t));
        }
    }

    @Test
    @DisplayName("Pinned entry overrides cycle math")
    void pin_overridesCycle() {
        BlockPos p = new BlockPos(2, 0, 0);
        VariantEditorPreviewState.setPinned(PLOT, p, 1);
        for (long t = 0; t < 60; t++) {
            assertEquals(1, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, t),
                "tick " + t + " should be pinned to 1");
        }
    }

    @Test
    @DisplayName("Pin to out-of-range index falls back to cycle")
    void pin_outOfRangeFallsBack() {
        BlockPos p = new BlockPos(0, 1, 0);
        VariantEditorPreviewState.setPinned(PLOT, p, 99);
        // Cycle for 3 entries at tick 4 (slot 1) should yield 1.
        assertEquals(1, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, 3, 4));
    }

    @Test
    @DisplayName("Cycle wraps cleanly across many entries and ticks")
    void cycle_wrapsCleanly() {
        BlockPos p = new BlockPos(0, 0, 1);
        int entries = 4;
        // 4 entries × 3 tick slot = 12 tick period. Verify both ends.
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, entries, 0));
        assertEquals(3, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, entries, 11));
        assertEquals(0, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, entries, 12));
        assertEquals(3, VariantEditorPreviewTicker.pickEntryIndex(PLOT, p, entries, 23));
    }
}
