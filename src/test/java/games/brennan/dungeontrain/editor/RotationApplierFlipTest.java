package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RotationApplier}'s post-rotation flip pass that randomises
 * {@link BlockStateProperties#HALF} (stairs, trapdoors) and
 * {@link BlockStateProperties#SLAB_TYPE} (slabs) when the variant is in
 * {@link VariantRotation.Mode#RANDOM}. Covers the original user complaint
 * that stairs with "R" never flipped upside-down.
 *
 * <p>Requires {@code unitTest.enable()} in build.gradle so the NeoForge
 * moddev runtime bootstraps {@code Blocks.*} singletons before the class
 * loads — same setup as {@code FallingBlockAnchorTest}.</p>
 */
final class RotationApplierFlipTest {

    private static final long WORLD_SEED = 0xC0FFEE_1234L;
    private static final int CARRIAGE_INDEX = 7;
    private static final int LOCK_ID = 0;

    // ---------- stair + RANDOM mode: HALF flips both ways ----------

    @Test
    @DisplayName("RANDOM stair: HALF rolls both TOP and BOTTOM across position seeds")
    void random_stair_halfFlipsBothWays() {
        BlockState bottomStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);

        EnumSet<Half> seen = EnumSet.noneOf(Half.class);
        int topCount = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            BlockPos pos = new BlockPos(i, 0, i * 3);
            BlockState out = RotationApplier.apply(bottomStair, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            Half h = out.getValue(BlockStateProperties.HALF);
            seen.add(h);
            if (h == Half.TOP) topCount++;
        }
        assertEquals(EnumSet.of(Half.TOP, Half.BOTTOM), seen,
            "RANDOM should produce both TOP and BOTTOM across many positions");
        // Loose distribution check: each side should land in ~30-70% range
        // (binomial 95% CI for n=1000 is ~47-53%, ±0.2 is very lenient).
        assertTrue(topCount > total * 0.3 && topCount < total * 0.7,
            "TOP/BOTTOM split looks too skewed: " + topCount + "/" + total);
    }

    // ---------- stair + LOCK / OPTIONS: HALF preserved ----------

    @Test
    @DisplayName("LOCK stair: captured HALF=BOTTOM is preserved")
    void lock_stair_preservesBottomHalf() {
        BlockState bottomStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        VariantRotation lockNorth = VariantRotation.lock(Direction.NORTH);

        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos(i, 0, 0);
            BlockState out = RotationApplier.apply(bottomStair, lockNorth,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(Half.BOTTOM, out.getValue(BlockStateProperties.HALF),
                "LOCK must not flip HALF (pos " + pos + ")");
        }
    }

    @Test
    @DisplayName("LOCK stair: captured HALF=TOP is preserved")
    void lock_stair_preservesTopHalf() {
        BlockState topStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP);
        VariantRotation lockEast = VariantRotation.lock(Direction.EAST);

        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos(i, 0, 0);
            BlockState out = RotationApplier.apply(topStair, lockEast,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(Half.TOP, out.getValue(BlockStateProperties.HALF),
                "LOCK must not flip HALF (pos " + pos + ")");
        }
    }

    @Test
    @DisplayName("OPTIONS stair: captured HALF=TOP is preserved across all rolls")
    void options_stair_preservesTopHalf() {
        BlockState topStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP);
        int northSouth = VariantRotation.maskOf(Direction.NORTH)
            | VariantRotation.maskOf(Direction.SOUTH);
        VariantRotation opts = VariantRotation.options(northSouth);

        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos(i, 0, i);
            BlockState out = RotationApplier.apply(topStair, opts,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(Half.TOP, out.getValue(BlockStateProperties.HALF),
                "OPTIONS must not flip HALF (pos " + pos + ")");
        }
    }

    // ---------- slab + RANDOM mode: SLAB_TYPE flips, never DOUBLE ----------

    @Test
    @DisplayName("RANDOM slab: SLAB_TYPE rolls TOP and BOTTOM, never DOUBLE")
    void random_slab_flipsBothWaysNeverDouble() {
        BlockState bottomSlab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);

        EnumSet<SlabType> seen = EnumSet.noneOf(SlabType.class);
        for (int i = 0; i < 1000; i++) {
            BlockPos pos = new BlockPos(i, 0, i * 5);
            BlockState out = RotationApplier.apply(bottomSlab, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            seen.add(out.getValue(BlockStateProperties.SLAB_TYPE));
        }
        assertEquals(EnumSet.of(SlabType.TOP, SlabType.BOTTOM), seen,
            "RANDOM slab must produce both TOP and BOTTOM and never DOUBLE");
    }

    @Test
    @DisplayName("RANDOM DOUBLE slab demotes to TOP or BOTTOM (never stays DOUBLE)")
    void random_doubleSlab_demotes() {
        BlockState doubleSlab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);

        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos(i, 0, 0);
            BlockState out = RotationApplier.apply(doubleSlab, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            SlabType t = out.getValue(BlockStateProperties.SLAB_TYPE);
            assertNotEquals(SlabType.DOUBLE, t,
                "DOUBLE must not survive RANDOM (pos " + pos + ")");
            assertTrue(t == SlabType.TOP || t == SlabType.BOTTOM,
                "Demoted slab must be TOP or BOTTOM (got " + t + ")");
        }
    }

    // ---------- determinism ----------

    @Test
    @DisplayName("Same inputs yield the same flip across repeated calls (stair)")
    void determinism_stair() {
        BlockState bottomStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        BlockPos pos = new BlockPos(13, 7, -42);

        BlockState first = RotationApplier.apply(bottomStair, VariantRotation.NONE,
            pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
        for (int i = 0; i < 100; i++) {
            BlockState repeat = RotationApplier.apply(bottomStair, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(first, repeat, "same inputs must yield same flip (iteration " + i + ")");
        }
    }

    @Test
    @DisplayName("Same inputs yield the same flip across repeated calls (slab)")
    void determinism_slab() {
        BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockPos pos = new BlockPos(-1, 64, 1);

        BlockState first = RotationApplier.apply(slab, VariantRotation.NONE,
            pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
        for (int i = 0; i < 100; i++) {
            BlockState repeat = RotationApplier.apply(slab, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(first, repeat, "same inputs must yield same flip (iteration " + i + ")");
        }
    }

    // ---------- captured facing is preserved by the flip pass ----------

    @Test
    @DisplayName("RANDOM mode flip pass preserves captured FACING (only HALF changes)")
    void random_stair_capturedFacingPreserved() {
        // RANDOM mode is a no-op for the directional picker (it short-circuits
        // on isDefault), so the flip pass must not alter the captured facing
        // either — same stair captured as EAST should always come out EAST,
        // just with HALF flipped.
        BlockState eastBottom = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);

        Map<String, Integer> seen = new HashMap<>();
        for (int x = 0; x < 30; x++) {
            for (int z = 0; z < 30; z++) {
                BlockPos pos = new BlockPos(x, 0, z);
                BlockState out = RotationApplier.apply(eastBottom, VariantRotation.NONE,
                    pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
                Direction f = out.getValue(BlockStateProperties.HORIZONTAL_FACING);
                Half h = out.getValue(BlockStateProperties.HALF);
                assertEquals(Direction.EAST, f,
                    "captured EAST facing must survive the flip pass (pos " + pos + ")");
                seen.merge(h.name(), 1, Integer::sum);
            }
        }
        // HALF rolled both ways — 2 keys in the map.
        assertEquals(2, seen.size(),
            "HALF should still roll both ways for EAST-captured stair, got " + seen);
    }

    // ---------- non-flippable block is untouched ----------

    @Test
    @DisplayName("Non-HALF / non-SLAB_TYPE block in RANDOM mode is unchanged by flip pass")
    void random_plainBlock_noFlip() {
        BlockState stone = Blocks.STONE.defaultBlockState();

        for (int i = 0; i < 50; i++) {
            BlockPos pos = new BlockPos(i, 0, i);
            BlockState out = RotationApplier.apply(stone, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            assertEquals(stone, out, "plain stone must not be altered by flip pass");
        }
    }

    // ---------- trapdoor (also has HALF) ----------

    @Test
    @DisplayName("RANDOM trapdoor: HALF rolls both ways")
    void random_trapdoor_flipsBothWays() {
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);

        EnumSet<Half> seen = EnumSet.noneOf(Half.class);
        for (int i = 0; i < 500; i++) {
            BlockPos pos = new BlockPos(i, 0, i * 2);
            BlockState out = RotationApplier.apply(trapdoor, VariantRotation.NONE,
                pos, WORLD_SEED, CARRIAGE_INDEX, LOCK_ID);
            seen.add(out.getValue(BlockStateProperties.HALF));
        }
        assertEquals(EnumSet.of(Half.TOP, Half.BOTTOM), seen,
            "RANDOM trapdoor should produce both TOP and BOTTOM");
    }

    // ============================================================
    // Editor preview cycle — mirrors the spawn-time behavior so the
    // author sees flipped variants in the editor world too.
    // ============================================================

    private static VariantState variant(BlockState state, VariantRotation rotation) {
        return new VariantState(state, null, 1, rotation, null, null);
    }

    @Test
    @DisplayName("Preview RANDOM stair: cycles all 8 (facing × half) combinations over 8 ticks")
    void preview_random_stair_cyclesAllCombinations() {
        BlockState bottomStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        VariantState entry = variant(bottomStair, VariantRotation.NONE);

        Map<String, Integer> seen = new HashMap<>();
        Half halfAt0 = null;
        Half halfAt4 = null;
        for (long tick = 0; tick < 8; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            Direction f = out.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Half h = out.getValue(BlockStateProperties.HALF);
            seen.merge(f.name() + "|" + h.name(), 1, Integer::sum);
            if (tick == 0) halfAt0 = h;
            if (tick == 4) halfAt4 = h;
        }
        assertEquals(8, seen.size(),
            "expected all 8 (facing × half) pairs over 8 ticks, got " + seen);
        // HALF cycle period = numDirs (4) → flips after the first full facing sweep.
        assertNotEquals(halfAt0, halfAt4,
            "HALF should toggle after one full facing sweep (tick 0 vs tick 4)");
    }

    @Test
    @DisplayName("Preview RANDOM stair: ticks 0..3 share one HALF, ticks 4..7 share the other")
    void preview_random_stair_halfHoldsForFullSweep() {
        BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        VariantState entry = variant(stair, VariantRotation.NONE);

        Half firstSweep = VariantEditorPreviewTicker.computePreviewState(entry, 0)
            .getValue(BlockStateProperties.HALF);
        for (long tick = 0; tick < 4; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            assertEquals(firstSweep, out.getValue(BlockStateProperties.HALF),
                "HALF should hold across the first 4-tick facing sweep (tick " + tick + ")");
        }
        Half secondSweep = VariantEditorPreviewTicker.computePreviewState(entry, 4)
            .getValue(BlockStateProperties.HALF);
        for (long tick = 4; tick < 8; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            assertEquals(secondSweep, out.getValue(BlockStateProperties.HALF),
                "HALF should hold across the second 4-tick sweep (tick " + tick + ")");
        }
        assertNotEquals(firstSweep, secondSweep,
            "first and second 4-tick sweeps must show different halves");
    }

    @Test
    @DisplayName("Preview RANDOM slab: SLAB_TYPE alternates every tick, never DOUBLE")
    void preview_random_slab_alternatesEveryTick() {
        BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        VariantState entry = variant(slab, VariantRotation.NONE);

        EnumSet<SlabType> seen = EnumSet.noneOf(SlabType.class);
        SlabType prev = null;
        for (long tick = 0; tick < 10; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            SlabType t = out.getValue(BlockStateProperties.SLAB_TYPE);
            assertNotEquals(SlabType.DOUBLE, t,
                "preview slab must never be DOUBLE (tick " + tick + ")");
            seen.add(t);
            if (prev != null) {
                assertNotEquals(prev, t,
                    "slab preview should flip every tick (tick " + tick + ")");
            }
            prev = t;
        }
        assertEquals(EnumSet.of(SlabType.TOP, SlabType.BOTTOM), seen,
            "preview slab in RANDOM mode should produce both TOP and BOTTOM");
    }

    @Test
    @DisplayName("Preview LOCK stair: captured HALF preserved across all ticks")
    void preview_lock_stair_preservesHalf() {
        BlockState topStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP);
        VariantState entry = variant(topStair, VariantRotation.lock(Direction.NORTH));

        for (long tick = 0; tick < 20; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            assertEquals(Half.TOP, out.getValue(BlockStateProperties.HALF),
                "LOCK preview must preserve captured HALF=TOP (tick " + tick + ")");
        }
    }

    @Test
    @DisplayName("Preview OPTIONS stair: captured HALF preserved across all ticks")
    void preview_options_stair_preservesHalf() {
        BlockState bottomStair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        int northSouth = VariantRotation.maskOf(Direction.NORTH)
            | VariantRotation.maskOf(Direction.SOUTH);
        VariantState entry = variant(bottomStair, VariantRotation.options(northSouth));

        for (long tick = 0; tick < 20; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            assertEquals(Half.BOTTOM, out.getValue(BlockStateProperties.HALF),
                "OPTIONS preview must preserve captured HALF=BOTTOM (tick " + tick + ")");
        }
    }

    @Test
    @DisplayName("Preview plain stone: unchanged across ticks (no flip property)")
    void preview_plainStone_noFlip() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        VariantState entry = variant(stone, VariantRotation.NONE);

        for (long tick = 0; tick < 20; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            assertEquals(stone, out, "plain stone preview must not change (tick " + tick + ")");
        }
    }

    @Test
    @DisplayName("Preview RANDOM DOUBLE slab: cycles TOP/BOTTOM, never stays DOUBLE")
    void preview_random_doubleSlab_demotes() {
        BlockState doubleSlab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        VariantState entry = variant(doubleSlab, VariantRotation.NONE);

        for (long tick = 0; tick < 10; tick++) {
            BlockState out = VariantEditorPreviewTicker.computePreviewState(entry, tick);
            SlabType t = out.getValue(BlockStateProperties.SLAB_TYPE);
            assertNotEquals(SlabType.DOUBLE, t,
                "DOUBLE slab preview must demote in RANDOM (tick " + tick + ")");
            assertTrue(t == SlabType.TOP || t == SlabType.BOTTOM,
                "demoted slab must be TOP or BOTTOM (got " + t + " at tick " + tick + ")");
        }
    }
}
