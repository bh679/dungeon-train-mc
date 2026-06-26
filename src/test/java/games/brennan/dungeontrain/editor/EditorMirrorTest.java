package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the generalised mirror maths and block-state reflection in
 * {@link EditorMirror} (the tunnel-dimension regression coverage lives in
 * {@link TunnelMirrorMapTest}).
 *
 * <p>The {@link #verticalFlip} / {@link #reflect} tests construct {@code Blocks.*}
 * singletons, so they rely on the NeoForge moddev bootstrap enabled by
 * {@code unitTest.enable()} in build.gradle — same setup as
 * {@code RotationApplierFlipTest}.</p>
 */
final class EditorMirrorTest {

    // ─── per-axis maths: parity ─────────────────────────────────────────

    @Test
    @DisplayName("Even axis: no centre column, master is the low half")
    void evenAxis() {
        int n = 4; // 0..3
        assertEquals(1, EditorMirror.lastMaster(n), "master 0..1, far 2..3");
        assertEquals(3, EditorMirror.target(0, n));
        assertEquals(2, EditorMirror.target(1, n));
        // far cells reflect into the master half when enabled
        assertEquals(1, EditorMirror.source(2, n, true));
        assertEquals(0, EditorMirror.source(3, n, true));
        // master cells map to themselves
        assertEquals(0, EditorMirror.source(0, n, true));
        assertEquals(1, EditorMirror.source(1, n, true));
    }

    @Test
    @DisplayName("Odd axis: shared centre column maps to itself")
    void oddAxis() {
        int n = 5; // 0..4, centre 2
        assertEquals(2, EditorMirror.lastMaster(n));
        assertEquals(2, EditorMirror.target(2, n), "centre maps to itself");
        assertEquals(2, EditorMirror.source(2, n, true), "centre is its own master");
        assertEquals(1, EditorMirror.source(3, n, true));
        assertEquals(0, EditorMirror.source(4, n, true));
    }

    @Test
    @DisplayName("target is an involution for both parities")
    void targetInvolution() {
        for (int n : new int[]{1, 2, 3, 4, 13, 14}) {
            for (int c = 0; c < n; c++) {
                assertEquals(c, EditorMirror.target(EditorMirror.target(c, n), n), "n=" + n + " c=" + c);
            }
        }
    }

    // ─── image enumeration ──────────────────────────────────────────────

    private static final Vec3i F = new Vec3i(4, 4, 5); // even X, even Y, odd Z

    @Test
    @DisplayName("One enabled axis → one image")
    void imagesSingleAxis() {
        List<EditorMirror.Image> imgs = EditorMirror.imagesOf(new BlockPos(0, 0, 0), F, true, false, false);
        assertEquals(1, imgs.size());
        EditorMirror.Image img = imgs.get(0);
        assertEquals(new BlockPos(3, 0, 0), img.local());
        assertTrue(img.flipX() && !img.flipY() && !img.flipZ());
    }

    @Test
    @DisplayName("Two / three enabled axes → 3 / 7 distinct images")
    void imagesMultiAxis() {
        assertEquals(3, EditorMirror.imagesOf(new BlockPos(0, 0, 0), F, true, true, false).size());
        assertEquals(7, EditorMirror.imagesOf(new BlockPos(0, 0, 0), F, true, true, true).size());
    }

    @Test
    @DisplayName("Edit on a mirror plane dedups its own reflection (no self image)")
    void imagesOnPlaneDedup() {
        // z=2 is the centre column of the odd Z axis → its Z reflection is itself.
        assertEquals(0, EditorMirror.imagesOf(new BlockPos(0, 0, 2), F, false, false, true).size());
        // X reflection still produces an image even when the cell is on the Z plane.
        assertEquals(1, EditorMirror.imagesOf(new BlockPos(0, 0, 2), F, true, false, true).size());
    }

    @Test
    @DisplayName("No enabled axis → no images")
    void imagesNoAxis() {
        assertEquals(0, EditorMirror.imagesOf(new BlockPos(1, 1, 1), F, false, false, false).size());
    }

    // ─── vertical flip ──────────────────────────────────────────────────

    @Test
    @DisplayName("verticalFlip toggles slab bottom↔top, leaves DOUBLE alone")
    void verticalFlipSlab() {
        BlockState bottom = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockState top = EditorMirror.verticalFlip(bottom);
        assertEquals(SlabType.TOP, top.getValue(BlockStateProperties.SLAB_TYPE));
        assertEquals(bottom, EditorMirror.verticalFlip(top), "involution");

        BlockState dbl = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        assertEquals(dbl, EditorMirror.verticalFlip(dbl), "DOUBLE unchanged");
    }

    @Test
    @DisplayName("verticalFlip toggles stair / trapdoor HALF")
    void verticalFlipHalf() {
        BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        assertEquals(Half.TOP, EditorMirror.verticalFlip(stair).getValue(BlockStateProperties.HALF));

        BlockState trap = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP);
        assertEquals(Half.BOTTOM, EditorMirror.verticalFlip(trap).getValue(BlockStateProperties.HALF));
    }

    @Test
    @DisplayName("verticalFlip toggles UP/DOWN FACING (dropper)")
    void verticalFlipFacing() {
        BlockState up = Blocks.DROPPER.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP);
        assertEquals(Direction.DOWN, EditorMirror.verticalFlip(up).getValue(BlockStateProperties.FACING));
    }

    @Test
    @DisplayName("verticalFlip leaves a plain / no-vertical-property block unchanged")
    void verticalFlipPlain() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, EditorMirror.verticalFlip(stone));
    }

    // ─── reflect composition ────────────────────────────────────────────

    @Test
    @DisplayName("reflect with no axes is a no-op; flipY routes through verticalFlip")
    void reflectBasics() {
        BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        assertSame(slab, EditorMirror.reflect(slab, false, false, false));
        assertEquals(SlabType.TOP,
            EditorMirror.reflect(slab, false, true, false).getValue(BlockStateProperties.SLAB_TYPE));
    }

    @Test
    @DisplayName("reflect applied twice with the same axes is the identity (involution)")
    void reflectInvolution() {
        BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        BlockState once = EditorMirror.reflect(stair, true, true, true);
        assertEquals(stair, EditorMirror.reflect(once, true, true, true));
    }

    // ─── variant-pool reflection (the "V" toggle) ───────────────────────

    @Test
    @DisplayName("mirrorDirection flips only the matching axis")
    void mirrorDirectionAxis() {
        assertEquals(Direction.WEST, EditorMirror.mirrorDirection(Direction.EAST, true, false, false));
        assertEquals(Direction.SOUTH, EditorMirror.mirrorDirection(Direction.NORTH, false, false, true));
        assertEquals(Direction.DOWN, EditorMirror.mirrorDirection(Direction.UP, false, true, false));
        // X face is unaffected by a Z flip.
        assertEquals(Direction.EAST, EditorMirror.mirrorDirection(Direction.EAST, false, false, true));
    }

    @Test
    @DisplayName("reflectRotation remaps LOCK/OPTIONS dirs; default RANDOM passes through")
    void reflectRotation() {
        VariantRotation lockEast = VariantRotation.lock(Direction.EAST);
        VariantRotation flipped = EditorMirror.reflectRotation(lockEast, true, false, false);
        assertEquals(VariantRotation.Mode.LOCK, flipped.mode());
        assertEquals(java.util.Set.of(Direction.WEST), flipped.directions());

        VariantRotation opts = VariantRotation.options(
            VariantRotation.maskOf(Direction.NORTH) | VariantRotation.maskOf(Direction.EAST));
        VariantRotation optsFlipped = EditorMirror.reflectRotation(opts, true, false, false);
        assertEquals(java.util.Set.of(Direction.NORTH, Direction.WEST), optsFlipped.directions());

        // RANDOM/default (dirMask 0) is returned unchanged.
        assertSame(VariantRotation.NONE, EditorMirror.reflectRotation(VariantRotation.NONE, true, true, true));
    }

    @Test
    @DisplayName("reflectHalf swaps TOP/BOTTOM only on a Y flip")
    void reflectHalf() {
        VariantHalf top = new VariantHalf(VariantHalf.Mode.TOP);
        assertEquals(VariantHalf.Mode.BOTTOM, EditorMirror.reflectHalf(top, true).mode());
        assertSame(top, EditorMirror.reflectHalf(top, false));
        VariantHalf rnd = VariantHalf.NONE;
        assertSame(rnd, EditorMirror.reflectHalf(rnd, true));
    }

    @Test
    @DisplayName("reflectVariant mirrors a block entry's state + preserves weight; mobs pass through")
    void reflectVariant() {
        BlockState stairEast = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        VariantState block = VariantState.of(stairEast).withWeight(3);
        VariantState reflected = EditorMirror.reflectVariant(block, true, false, false);
        assertEquals(Direction.WEST,
            reflected.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(3, reflected.weight());

        VariantState mob = VariantState.ofMob(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("zombie"),
            null, 2, VariantRotation.NONE);
        assertSame(mob, EditorMirror.reflectVariant(mob, true, true, true), "mob entries pass through unchanged");
    }

    @Test
    @DisplayName("reflectStates reflects every entry and preserves pool size")
    void reflectStates() {
        BlockState stairEast = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        List<VariantState> pool = List.of(VariantState.of(Blocks.STONE.defaultBlockState()),
            VariantState.of(stairEast));
        List<VariantState> out = EditorMirror.reflectStates(pool, true, false, false);
        assertEquals(2, out.size());
        assertEquals(Direction.WEST, out.get(1).state().getValue(BlockStateProperties.HORIZONTAL_FACING));
    }
}
