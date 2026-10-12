package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the 8-way compass rotation {@link RotationApplier} offers for
 * {@code ROTATION_16} blocks (floor heads, standing banners and signs): slot
 * {@code i} is {@code ROTATION_16 = 2i}, order S, SW, W, NW, N, NE, E, SE.
 */
final class RotationApplierCompassTest {

    private static final long WORLD_SEED = 0xC0FFEE_1234L;
    private static final int CARRIAGE_INDEX = 7;

    private static BlockState head(int rot) {
        return Blocks.PLAYER_HEAD.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rot);
    }

    private static int rotOf(BlockState s) {
        return s.getValue(BlockStateProperties.ROTATION_16);
    }

    @Test
    @DisplayName("ROTATION_16 blocks are compass-rotatable; wall heads and stairs are not compass")
    void compassDetection() {
        assertTrue(RotationApplier.canRotate(head(0)));
        assertTrue(RotationApplier.isCompass(head(0)));
        assertTrue(RotationApplier.isCompass(Blocks.WHITE_BANNER.defaultBlockState()));
        assertTrue(RotationApplier.isCompass(Blocks.OAK_SIGN.defaultBlockState()));
        assertEquals(8, RotationApplier.slotCount(head(0)));
        assertEquals(0xFF, RotationApplier.validDirMask(head(0)));

        assertFalse(RotationApplier.isCompass(Blocks.PLAYER_WALL_HEAD.defaultBlockState()));
        assertFalse(RotationApplier.isCompass(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertEquals(6, RotationApplier.slotCount(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
    }

    @Test
    @DisplayName("LOCK on each slot sets ROTATION_16 to 2*slot")
    void lockSetsRotation() {
        for (int slot = 0; slot < 8; slot++) {
            VariantRotation lock = new VariantRotation(VariantRotation.Mode.LOCK, 1 << slot);
            BlockState out = RotationApplier.apply(head(3), lock, BlockPos.ZERO, WORLD_SEED, CARRIAGE_INDEX, 0);
            assertEquals(slot * 2, rotOf(out), "slot " + slot);
        }
    }

    @Test
    @DisplayName("OPTIONS only yields chosen slots, reaches each, and is deterministic")
    void optionsPicksOnlyChosen() {
        int mask = (1 << 1) | (1 << 4) | (1 << 7); // SW, N, SE
        VariantRotation opts = VariantRotation.options(mask);
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 64; x++) {
            BlockPos pos = new BlockPos(x, 2, -x);
            BlockState out = RotationApplier.apply(head(0), opts, pos, WORLD_SEED, CARRIAGE_INDEX, 0);
            seen.add(rotOf(out));
            assertEquals(out, RotationApplier.apply(head(0), opts, pos, WORLD_SEED, CARRIAGE_INDEX, 0));
        }
        assertEquals(Set.of(2, 8, 14), seen);
    }

    @Test
    @DisplayName("Default RANDOM leaves a captured head's rotation untouched (shipped templates unchanged)")
    void defaultLeavesHeadAlone() {
        BlockState out = RotationApplier.apply(head(5), VariantRotation.NONE, BlockPos.ZERO,
            WORLD_SEED, CARRIAGE_INDEX, 0);
        assertEquals(5, rotOf(out));
    }

    @Test
    @DisplayName("lockToCurrent snaps a 16-step rotation to the nearest compass slot")
    void lockToCurrentSnaps() {
        assertEquals(1 << 4, RotationApplier.lockToCurrent(head(8)).dirMask());  // N
        assertEquals(1 << 3, RotationApplier.lockToCurrent(head(5)).dirMask());  // 2.5 → NW
        assertEquals(1, RotationApplier.lockToCurrent(head(15)).dirMask());      // 7.5 → wraps to S
        assertEquals(VariantRotation.Mode.LOCK, RotationApplier.lockToCurrent(head(8)).mode());
    }

    @Test
    @DisplayName("orientToPredecessors carries a compass slot between heads, never into a facing block")
    void orientCarriesOnlySameKind() {
        VariantState prevHead = new VariantState(head(12), null, 1, VariantRotation.NONE);
        RotationApplier.OrientedState o = RotationApplier.orientToPredecessors(
            Blocks.SKELETON_SKULL.defaultBlockState(), List.of(prevHead));
        assertEquals(12, rotOf(o.state()));
        assertEquals(1 << 6, o.rotation().dirMask());

        BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        RotationApplier.OrientedState s = RotationApplier.orientToPredecessors(stair, List.of(prevHead));
        assertEquals(Direction.WEST, s.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(VariantRotation.maskOf(Direction.WEST), s.rotation().dirMask());
    }

    @Test
    @DisplayName("Compass mask rotate / mirror match how the block state itself turns")
    void transformsMatchState() {
        for (int slot = 0; slot < 8; slot++) {
            BlockState h = head(slot * 2);
            for (Rotation r : Rotation.values()) {
                int mask = RotationApplier.rotateCompassMask(1 << slot, r);
                assertEquals(1 << (rotOf(h.rotate(r)) / 2), mask, "slot " + slot + " " + r);
            }
            VariantRotation lock = new VariantRotation(VariantRotation.Mode.LOCK, 1 << slot);
            assertEquals(1 << (rotOf(h.mirror(Mirror.FRONT_BACK)) / 2),
                EditorMirror.reflectRotation(lock, h, true, false, false).dirMask());
            assertEquals(1 << (rotOf(h.mirror(Mirror.LEFT_RIGHT)) / 2),
                EditorMirror.reflectRotation(lock, h, false, false, true).dirMask());
            assertEquals(1 << slot,
                EditorMirror.reflectRotation(lock, h, false, true, false).dirMask());
        }
    }

    @Test
    @DisplayName("Compass rotation round-trips through sidecar JSON as named points")
    void jsonRoundTrip() {
        VariantRotation opts = VariantRotation.options((1 << 0) | (1 << 5) | (1 << 7));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendRotationJson(sb, opts, true);
        assertEquals("{\"mode\": \"options\", \"compass\": [\"s\", \"ne\", \"se\"]}", sb.toString());
        VariantRotation back = CarriageVariantBlocks.parseRotation(
            JsonParser.parseString(sb.toString()), true, "test", BlockPos.ZERO);
        assertEquals(opts, back);

        // Facing blocks keep the historical "dirs" shape.
        StringBuilder dirs = new StringBuilder();
        CarriageVariantBlocks.appendRotationJson(dirs, VariantRotation.lock(Direction.EAST), false);
        assertEquals("{\"mode\": \"lock\", \"dirs\": [\"east\"]}", dirs.toString());
    }

    @Test
    @DisplayName("Legacy 6-way \"dirs\" on a head is ignored (it never turned heads); facing blocks still read it")
    void legacyDirsIgnoredOnCompass() {
        String legacy = "{\"mode\": \"lock\", \"dirs\": [\"north\"]}";
        assertEquals(VariantRotation.NONE, CarriageVariantBlocks.parseRotation(
            JsonParser.parseString(legacy), true, "test", BlockPos.ZERO));
        assertEquals(VariantRotation.lock(Direction.NORTH), CarriageVariantBlocks.parseRotation(
            JsonParser.parseString(legacy), false, "test", BlockPos.ZERO));
        String compass = "{\"mode\": \"lock\", \"compass\": [\"n\"]}";
        assertEquals(VariantRotation.NONE, CarriageVariantBlocks.parseRotation(
            JsonParser.parseString(compass), false, "test", BlockPos.ZERO));
    }
}
