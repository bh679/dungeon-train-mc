package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which blocks grow ({@link GrowthShapes}), which way, and what each block of the column is. */
final class GrowthShapesTest {

    private static final VariantGrowth.Dir UP = VariantGrowth.Dir.UP;
    private static final VariantGrowth.Dir DOWN = VariantGrowth.Dir.DOWN;

    @Test
    @DisplayName("column-forming blocks grow; cubes, ceiling-only vines and sideways chains don't")
    void canGrow() {
        for (BlockState s : List.of(
                Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.EAST, true),
                Blocks.CAVE_VINES.defaultBlockState(), Blocks.CAVE_VINES_PLANT.defaultBlockState(),
                Blocks.WEEPING_VINES.defaultBlockState(), Blocks.TWISTING_VINES.defaultBlockState(),
                Blocks.KELP.defaultBlockState(), Blocks.LADDER.defaultBlockState(),
                Blocks.CHAIN.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState(),
                Blocks.BAMBOO.defaultBlockState(), Blocks.SUGAR_CANE.defaultBlockState(),
                Blocks.CACTUS.defaultBlockState(), Blocks.BIG_DRIPLEAF.defaultBlockState(),
                Blocks.BIG_DRIPLEAF_STEM.defaultBlockState(), Blocks.POINTED_DRIPSTONE.defaultBlockState(),
                Blocks.CHORUS_PLANT.defaultBlockState(), Blocks.CHORUS_FLOWER.defaultBlockState())) {
            assertTrue(GrowthShapes.canGrow(s), s.toString());
        }
        assertFalse(GrowthShapes.canGrow(Blocks.STONE.defaultBlockState()));
        assertFalse(GrowthShapes.canGrow(Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.UP, true)));
        assertFalse(GrowthShapes.canGrow(Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.X)));
        assertFalse(GrowthShapes.canGrow(Blocks.SMALL_DRIPLEAF.defaultBlockState()));
        assertFalse(GrowthShapes.canGrow(null));
    }

    @Test
    @DisplayName("ladders, vines, chains, scaffolding and dripstone grow either way; the rest keep their natural way")
    void directions() {
        assertTrue(GrowthShapes.growsBothWays(Blocks.LADDER.defaultBlockState()));
        assertTrue(GrowthShapes.growsBothWays(Blocks.POINTED_DRIPSTONE.defaultBlockState()));
        assertFalse(GrowthShapes.growsBothWays(Blocks.CAVE_VINES.defaultBlockState()));
        assertEquals(DOWN, GrowthShapes.effectiveDir(Blocks.CAVE_VINES.defaultBlockState(), UP));
        assertEquals(UP, GrowthShapes.effectiveDir(Blocks.BAMBOO.defaultBlockState(), DOWN));
        assertEquals(DOWN, GrowthShapes.effectiveDir(Blocks.LADDER.defaultBlockState(), DOWN));
        assertTrue(GrowthShapes.hasTip(Blocks.CAVE_VINES.defaultBlockState()));
        assertFalse(GrowthShapes.hasTip(Blocks.LADDER.defaultBlockState()));
    }

    @Test
    @DisplayName("cave vines: plant body, aged head tip keeping berries; tip off ends in body")
    void caveVines() {
        BlockState entry = Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true);
        List<BlockState> col = GrowthShapes.column(entry, UP, 3, true);
        assertEquals(3, col.size());
        assertTrue(col.get(0).is(Blocks.CAVE_VINES_PLANT));
        assertTrue(col.get(1).is(Blocks.CAVE_VINES_PLANT));
        assertTrue(col.get(2).is(Blocks.CAVE_VINES));
        assertEquals(GrowingPlantHeadBlock.MAX_AGE, col.get(2).getValue(GrowingPlantHeadBlock.AGE));
        assertTrue(col.get(2).getValue(CaveVines.BERRIES));

        List<BlockState> noTip = GrowthShapes.column(entry, DOWN, 3, false);
        assertTrue(noTip.get(2).is(Blocks.CAVE_VINES_PLANT));
    }

    @Test
    @DisplayName("dripstone: base, middle, frustum, tip pointing the growth way; no tip ends at the frustum")
    void dripstone() {
        List<BlockState> col = GrowthShapes.column(Blocks.POINTED_DRIPSTONE.defaultBlockState(), DOWN, 4, true);
        assertEquals(List.of(DripstoneThickness.BASE, DripstoneThickness.MIDDLE,
                DripstoneThickness.FRUSTUM, DripstoneThickness.TIP),
            col.stream().map(s -> s.getValue(PointedDripstoneBlock.THICKNESS)).toList());
        assertTrue(col.stream().allMatch(s -> s.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN));

        List<BlockState> two = GrowthShapes.column(Blocks.POINTED_DRIPSTONE.defaultBlockState(), UP, 2, true);
        assertEquals(DripstoneThickness.FRUSTUM, two.get(0).getValue(PointedDripstoneBlock.THICKNESS));
        assertEquals(DripstoneThickness.TIP, two.get(1).getValue(PointedDripstoneBlock.THICKNESS));
        assertEquals(Direction.UP, two.get(1).getValue(PointedDripstoneBlock.TIP_DIRECTION));

        assertEquals(DripstoneThickness.FRUSTUM, GrowthShapes.dripstoneThickness(2, 3, false));
    }

    @Test
    @DisplayName("bamboo: mature stalk with leaves on the top two when tipped, bare when not")
    void bamboo() {
        List<BlockState> col = GrowthShapes.column(Blocks.BAMBOO.defaultBlockState(), UP, 4, true);
        assertEquals(List.of(BambooLeaves.NONE, BambooLeaves.NONE, BambooLeaves.SMALL, BambooLeaves.LARGE),
            col.stream().map(s -> s.getValue(BambooStalkBlock.LEAVES)).toList());
        assertTrue(col.stream().allMatch(s -> s.getValue(BambooStalkBlock.STAGE) == 1));
        assertTrue(GrowthShapes.column(Blocks.BAMBOO.defaultBlockState(), UP, 4, false).stream()
            .allMatch(s -> s.getValue(BambooStalkBlock.LEAVES) == BambooLeaves.NONE));
    }

    @Test
    @DisplayName("ladders repeat; a grown vine drops its ceiling face; length 1 is just the entry")
    void simpleColumns() {
        BlockState ladder = Blocks.LADDER.defaultBlockState();
        assertTrue(GrowthShapes.column(ladder, DOWN, 5, true).stream().allMatch(s -> s == ladder));

        BlockState vine = Blocks.VINE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.UP, true);
        List<BlockState> vines = GrowthShapes.column(vine, DOWN, 3, true);
        assertTrue(vines.stream().noneMatch(s -> s.getValue(BlockStateProperties.UP)));
        assertTrue(vines.stream().allMatch(s -> s.getValue(BlockStateProperties.NORTH)));

        assertEquals(List.of(vine), GrowthShapes.column(vine, DOWN, 1, true));
    }

    @Test
    @DisplayName("free space: air for most blocks, a water source for kelp")
    void isFree() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        assertTrue(GrowthShapes.isFree(Blocks.LADDER.defaultBlockState(), air));
        assertFalse(GrowthShapes.isFree(Blocks.LADDER.defaultBlockState(), water));
        assertTrue(GrowthShapes.isFree(Blocks.KELP.defaultBlockState(), water));
        assertFalse(GrowthShapes.isFree(Blocks.KELP.defaultBlockState(), air));
        assertFalse(GrowthShapes.isFree(Blocks.LADDER.defaultBlockState(), Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("reach counts the cell and stops at the first blocked space")
    void reach() {
        assertEquals(5, GrowthPass.reach(5, k -> true));
        assertEquals(3, GrowthPass.reach(5, k -> k < 3));
        assertEquals(1, GrowthPass.reach(5, k -> false));
        assertEquals(1, GrowthPass.reach(1, k -> true));
    }
}
