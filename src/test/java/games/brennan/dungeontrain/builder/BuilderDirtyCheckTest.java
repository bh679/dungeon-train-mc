package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unsaved-changes comparison.
 *
 * <p>Two rules carry real risk. A missing baseline must read <b>clean</b> — snapshots are lost on
 * a server restart, and reporting a reopened world as entirely unsaved would train the builder to
 * ignore the warning. And the snapshot omits air to stay sparse, so "absent" means "was air", not
 * "unknown" — get that backwards and a block placed in empty space goes unnoticed.</p>
 */
final class BuilderDirtyCheckTest {

    private static final CarriageDims DIMS = CarriageDims.clamp(4, 3, 3);
    /**
     * The volume walked by the comparison. Was {@link #DIMS} until portal rooms arrived — a room's
     * box is the author's size, so the extent now comes from the box rather than from carriage dims.
     */
    private static final Vec3i BOX = new Vec3i(DIMS.length(), DIMS.height(), DIMS.width());
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState OAK = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Test
    @DisplayName("No baseline reads clean — a restart must not flag everything as unsaved")
    void missingBaselineIsClean() {
        assertFalse(BuilderDirtyCheck.isDirty(null, BOX, pos -> STONE));
    }

    @Test
    @DisplayName("A world identical to its baseline is clean")
    void identicalIsClean() {
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        baseline.put(new BlockPos(1, 1, 1), STONE);
        assertFalse(BuilderDirtyCheck.isDirty(baseline, BOX,
                BuilderDirtyCheck.liveFrom(Map.copyOf(baseline))));
    }

    @Test
    @DisplayName("A changed block is dirty")
    void changedBlockIsDirty() {
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        baseline.put(new BlockPos(1, 1, 1), STONE);
        Map<BlockPos, BlockState> live = new HashMap<>();
        live.put(new BlockPos(1, 1, 1), OAK);
        assertTrue(BuilderDirtyCheck.isDirty(baseline, BOX, BuilderDirtyCheck.liveFrom(live)));
    }

    @Test
    @DisplayName("A block placed where the baseline had air is dirty — the sparse-map trap")
    void blockAddedInEmptySpaceIsDirty() {
        Map<BlockPos, BlockState> baseline = new HashMap<>();   // nothing recorded: all air
        Map<BlockPos, BlockState> live = new HashMap<>();
        live.put(new BlockPos(2, 0, 1), OAK);
        assertTrue(BuilderDirtyCheck.isDirty(baseline, BOX, BuilderDirtyCheck.liveFrom(live)));
    }

    @Test
    @DisplayName("A block broken out of the baseline is dirty")
    void blockRemovedIsDirty() {
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        baseline.put(new BlockPos(0, 0, 0), STONE);
        assertTrue(BuilderDirtyCheck.isDirty(baseline, BOX, pos -> AIR));
    }

    @Test
    @DisplayName("Changes outside the carriage volume are not this check's business")
    void changesOutsideTheVolumeAreIgnored() {
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        // Live block sits one past the volume on every axis, so the scan never visits it.
        Map<BlockPos, BlockState> live = new HashMap<>();
        live.put(new BlockPos(DIMS.length(), DIMS.height(), DIMS.width()), OAK);
        assertFalse(BuilderDirtyCheck.isDirty(baseline, BOX, BuilderDirtyCheck.liveFrom(live)));
    }

    @Test
    @DisplayName("Snapshot keys are distinct per carriage slot")
    void snapshotKeysAreDistinct() {
        assertFalse(BuilderDirtyCheck.snapshotKey(0).equals(BuilderDirtyCheck.snapshotKey(1)));
        assertTrue(BuilderDirtyCheck.snapshotKey(0).startsWith("builder:"));
    }
}
