package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCitySwapProcessor.Swap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCitySwapProcessor}: state carry-over, the chance roll, the pad layer. Needs the unit-test bootstrap. */
final class LostCitySwapProcessorTest {

    private static final BlockPos ORIGIN = new BlockPos(100, 64, -200);

    @Test
    @DisplayName("a recoloured stair keeps its facing and half; a pane keeps waterlogged")
    void carriesState() {
        BlockState stair = Blocks.CRIMSON_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.TOP);
        BlockState out = LostCitySwapProcessor.carry(stair, Blocks.SPRUCE_STAIRS);
        assertSame(Blocks.SPRUCE_STAIRS, out.getBlock());
        assertEquals(Direction.WEST, out.getValue(StairBlock.FACING));
        assertEquals(Half.TOP, out.getValue(StairBlock.HALF));
        BlockState pane = Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)
                .setValue(BlockStateProperties.NORTH, true);
        BlockState grey = LostCitySwapProcessor.carry(pane, Blocks.GRAY_STAINED_GLASS_PANE);
        assertTrue(grey.getValue(BlockStateProperties.WATERLOGGED));
        assertTrue(grey.getValue(BlockStateProperties.NORTH));
        // a full block gains nothing and loses nothing
        assertEquals(Blocks.CALCITE.defaultBlockState(), LostCitySwapProcessor.carry(stair, Blocks.CALCITE));
    }

    @Test
    @DisplayName("chance 1 always swaps, chance 0 never, and the roll is deterministic per position")
    void chance() {
        LostCitySwapProcessor always = new LostCitySwapProcessor(List.of(new Swap(Blocks.STONE, Blocks.DEEPSLATE, 1.0F)));
        LostCitySwapProcessor never = new LostCitySwapProcessor(List.of(new Swap(Blocks.STONE, Blocks.DEEPSLATE, 0.0F)));
        LostCitySwapProcessor half = new LostCitySwapProcessor(List.of(new Swap(Blocks.STONE, Blocks.DEEPSLATE, 0.5F)));
        BlockState stone = Blocks.STONE.defaultBlockState();
        int swapped = 0;
        for (int i = 0; i < 400; i++) {
            BlockPos pos = ORIGIN.offset(i % 20, 1 + i / 20, i % 7);
            assertNotNull(always.pick(stone, ORIGIN, pos));
            assertNull(never.pick(stone, ORIGIN, pos));
            assertNull(always.pick(Blocks.DIRT.defaultBlockState(), ORIGIN, pos));
            Swap a = half.pick(stone, ORIGIN, pos);
            assertEquals(a, half.pick(stone, ORIGIN, pos));
            if (a != null) swapped++;
        }
        assertTrue(swapped > 150 && swapped < 250, "about half: " + swapped);
    }

    @Test
    @DisplayName("two rules on one block split the roll: 0.6 weathered, 0.4 oxidised, never neither")
    void splitRoll() {
        LostCitySwapProcessor rust = new LostCitySwapProcessor(List.of(
                new Swap(Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_WEATHERED_COPPER, 0.6F),
                new Swap(Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_OXIDIZED_COPPER, 0.4F)));
        int weathered = 0;
        for (int i = 0; i < 500; i++) {
            Swap s = rust.pick(Blocks.WAXED_EXPOSED_COPPER.defaultBlockState(), ORIGIN, ORIGIN.offset(i, 3, -i));
            assertNotNull(s);
            if (s.to() == Blocks.WAXED_WEATHERED_COPPER) weathered++;
        }
        assertTrue(weathered > 250 && weathered < 350, "about 60%: " + weathered);
    }

    @Test
    @DisplayName("processBlock: swaps above the pad, removes on air, leaves the pad layer alone")
    void processBlock() {
        LostCitySwapProcessor p = new LostCitySwapProcessor(List.of(
                new Swap(Blocks.STONE, Blocks.DEEPSLATE, 1.0F), new Swap(Blocks.VINE, Blocks.AIR, 1.0F)));
        BlockPos at = ORIGIN.offset(3, 5, 3);
        StructureBlockInfo stone = new StructureBlockInfo(at, Blocks.STONE.defaultBlockState(), null);
        StructureBlockInfo upper = new StructureBlockInfo(new BlockPos(3, 5, 3), Blocks.STONE.defaultBlockState(), null);
        StructureBlockInfo pad = new StructureBlockInfo(new BlockPos(3, 0, 3), Blocks.STONE.defaultBlockState(), null);
        assertSame(Blocks.DEEPSLATE, p.processBlock(null, ORIGIN, ORIGIN, upper, stone, null).state().getBlock());
        assertSame(stone, p.processBlock(null, ORIGIN, ORIGIN, pad, stone, null));
        StructureBlockInfo vine = new StructureBlockInfo(at, Blocks.VINE.defaultBlockState(), null);
        assertNull(p.processBlock(null, ORIGIN, ORIGIN, upper, vine, null));
        StructureBlockInfo dirt = new StructureBlockInfo(at, Blocks.DIRT.defaultBlockState(), null);
        assertSame(dirt, p.processBlock(null, ORIGIN, ORIGIN, upper, dirt, null));
    }
}
