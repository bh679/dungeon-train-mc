package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The strip bookkeeping of a pour. What the strips write needs a world and is tested in game. */
class PortalChunkPourTest {

    private static final int HEIGHT = 32;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** An empty slice {@code width} across — the pour only reads its width. */
    private static PortalChunkSlice slice(int width) {
        return new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width, HEIGHT,
            new BlockState[width * width * HEIGHT], Map.of(), List.of());
    }

    /** Runs a pour to the end; returns its ticks, asserting every strip is written once, in order. */
    private static int pourAll(PortalChunkPour pour, int height) {
        int expectedFrom = 0;
        int ticks = 0;
        while (!pour.done(height)) {
            assertEquals(expectedFrom, pour.nextStrip());
            expectedFrom = pour.stripsEnd(height);
            assertTrue((expectedFrom - pour.nextStrip()) * pour.slice().width() <= PortalChunkPour.CELLS_PER_TICK
                || expectedFrom - pour.nextStrip() == 1, "a tick stays inside the cell budget");
            pour = pour.advanced(height, 1L);
            ticks++;
        }
        assertEquals(pour.totalStrips(height), pour.nextStrip());
        assertEquals(ticks, pour.ticks());
        return ticks;
    }

    @Test
    @DisplayName("A one-chunk room still pours in eight ticks: four rows a tick, as before")
    void oneChunkEightTicks() {
        assertEquals(64, PortalChunkPour.stripsPerTick(16), "64 strips of 16 = four rows of 16x16");
        assertEquals(8, pourAll(PortalChunkPour.of(slice(16)), HEIGHT));
    }

    @Test
    @DisplayName("A 3x3-chunk room keeps the same per-tick cost and takes longer instead")
    void threeByThreeSameBudget() {
        assertEquals(21, PortalChunkPour.stripsPerTick(48));
        // 32 rows x 48 strips = 1536 strips at 21 a tick.
        assertEquals(74, pourAll(PortalChunkPour.of(slice(48)), HEIGHT));
    }

    @Test
    @DisplayName("A room wider than the budget still writes a strip a tick")
    void hugeWidthOneStrip() {
        assertEquals(1, PortalChunkPour.stripsPerTick(4096));
    }

    @Test
    @DisplayName("A box that does not divide evenly stops at its last strip rather than past it")
    void shortLastStep() {
        PortalChunkPour pour = new PortalChunkPour(slice(16), 150, 2, 0L, 0L);
        assertEquals(160, pour.stripsEnd(10));
        assertTrue(pour.advanced(10, 1L).done(10));
        assertFalse(pour.done(10));
    }

    @Test
    @DisplayName("A newer sample starts again from the bottom and keeps the timings")
    void restartKeepsTimings() {
        PortalChunkPour pour = PortalChunkPour.of(slice(16)).advanced(HEIGHT, 5L).advanced(HEIGHT, 3L);
        PortalChunkPour restarted = pour.restartedWith(slice(16));

        assertEquals(0, restarted.nextStrip());
        assertEquals(2, restarted.ticks());
        assertEquals(8L, restarted.totalNanos());
        assertEquals(5L, restarted.maxNanos());
    }

    @Test
    @DisplayName("A rushed pour keeps its place and its hurry, and a second step in the same tick is not a new tick")
    void rushedPour() {
        PortalChunkPour pour = PortalChunkPour.of(slice(48)).advanced(HEIGHT, 1L).rushed();
        assertTrue(pour.rush());
        assertEquals(21, pour.nextStrip());
        assertTrue(pour.restartedWith(slice(48)).rush());
        PortalChunkPour more = pour.continued(HEIGHT, 7L);
        assertEquals(42, more.nextStrip());
        assertEquals(pour.ticks(), more.ticks());
        assertEquals(7L, more.maxNanos());
        assertFalse(PortalChunkPour.of(slice(48)).rush());
    }
}
