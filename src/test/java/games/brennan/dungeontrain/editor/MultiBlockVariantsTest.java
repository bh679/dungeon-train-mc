package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-space blocks (doors, beds, tall plants) in a variant cell: the partner half is placed, and
 * single blocks sharing the cell fill the two spaces per their {@link VariantSpan}.
 */
class MultiBlockVariantsTest {

    private static final BlockPos CELL = new BlockPos(3, 1, 2);
    private static final BlockPos ABOVE = CELL.above();
    private static final long SEED = 12345L;
    /** Identity rotator — the tests exercise footprint logic, not rotation rolls. */
    private static final MultiBlockVariants.Rotator AS_AUTHORED = VariantState::state;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState door() {
        return VariantState.of(Blocks.OAK_DOOR.defaultBlockState());
    }

    private static VariantState single(BlockState s, VariantSpan.Mode mode) {
        return VariantState.of(s).withSpan(new VariantSpan(mode));
    }

    @Test
    @DisplayName("Partner offsets: door / tall plant vertical, bed toward its facing")
    void partnerOffsets() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        assertEquals(new BlockPos(0, 1, 0), MultiBlockFootprint.partnerOffset(lower));
        assertEquals(new BlockPos(0, -1, 0), MultiBlockFootprint.partnerOffset(
            lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
        assertEquals(new BlockPos(0, 1, 0), MultiBlockFootprint.partnerOffset(Blocks.TALL_GRASS.defaultBlockState()));

        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        assertEquals(new BlockPos(1, 0, 0), MultiBlockFootprint.partnerOffset(foot));
        assertEquals(new BlockPos(-1, 0, 0), MultiBlockFootprint.partnerOffset(
            foot.setValue(BedBlock.PART, BedPart.HEAD)));

        assertNull(MultiBlockFootprint.partnerOffset(Blocks.STONE.defaultBlockState()));
        assertEquals(DoubleBlockHalf.UPPER,
            MultiBlockFootprint.partnerState(lower).getValue(DoorBlock.HALF));
        assertEquals(BedPart.HEAD, MultiBlockFootprint.partnerState(foot).getValue(BedBlock.PART));
    }

    @Test
    @DisplayName("A cell with no multi-space entry writes exactly the one block it always did")
    void noMultiUnchanged() {
        VariantState stone = VariantState.of(Blocks.STONE.defaultBlockState());
        List<VariantState> cell = List.of(stone, VariantState.of(Blocks.DIRT.defaultBlockState()));
        List<MultiBlockVariants.Write> writes = MultiBlockVariants.expand(cell, stone, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(1, writes.size());
        assertEquals(CELL, writes.get(0).localPos());
        assertEquals(stone.state(), writes.get(0).state());
    }

    @Test
    @DisplayName("A picked door places its upper half above")
    void doorPlacesBothHalves() {
        VariantState door = door();
        List<MultiBlockVariants.Write> writes = MultiBlockVariants.expand(
            List.of(door, VariantState.of(Blocks.STONE.defaultBlockState())), door, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(2, writes.size());
        assertEquals(DoubleBlockHalf.LOWER, writes.get(0).state().getValue(DoorBlock.HALF));
        assertEquals(ABOVE, writes.get(1).localPos());
        assertEquals(DoubleBlockHalf.UPPER, writes.get(1).state().getValue(DoorBlock.HALF));
    }

    @Test
    @DisplayName("A rotated bed puts its head where the ROLLED facing points, and clears the footprint space")
    void rotatedBedFollowsRolledFacing() {
        VariantState bed = VariantState.of(Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH));
        MultiBlockVariants.Rotator turnEast = v -> v.state().setValue(BedBlock.FACING, Direction.EAST);
        List<MultiBlockVariants.Write> writes = MultiBlockVariants.expand(
            List.of(bed), bed, CELL, SEED, 0, turnEast);
        assertEquals(3, writes.size());
        assertTrue(writes.get(1).isAir());
        assertEquals(CELL.north(), writes.get(1).localPos());
        assertEquals(CELL.east(), writes.get(2).localPos());
        assertEquals(BedPart.HEAD, writes.get(2).state().getValue(BedBlock.PART));
    }

    @Test
    @DisplayName("Singles: 1/1 air above, 1/2 air in the cell, 2/Same both spaces")
    void explicitSpanModes() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        VariantState first = single(stone, VariantSpan.Mode.ONE_FIRST);
        VariantState second = single(stone, VariantSpan.Mode.ONE_SECOND);
        VariantState both = single(stone, VariantSpan.Mode.TWO_SAME);

        List<MultiBlockVariants.Write> w1 = MultiBlockVariants.expand(List.of(door(), first), first, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(stone, w1.get(0).state());
        assertTrue(w1.get(1).isAir());
        assertEquals(ABOVE, w1.get(1).localPos());

        List<MultiBlockVariants.Write> w2 = MultiBlockVariants.expand(List.of(door(), second), second, CELL, SEED, 0, AS_AUTHORED);
        assertTrue(w2.get(0).isAir());
        assertEquals(CELL, w2.get(0).localPos());
        assertEquals(stone, w2.get(1).state());
        assertEquals(ABOVE, w2.get(1).localPos());

        List<MultiBlockVariants.Write> w3 = MultiBlockVariants.expand(List.of(door(), both), both, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(stone, w3.get(0).state());
        assertEquals(stone, w3.get(1).state());
    }

    @Test
    @DisplayName("AUTO follows the first entry: door first → 2/Same; single first → 1/1")
    void autoFollowsFirstEntry() {
        VariantState stone = VariantState.of(Blocks.STONE.defaultBlockState());

        List<MultiBlockVariants.Write> doorFirst = MultiBlockVariants.expand(
            List.of(door(), stone), stone, CELL, SEED, 0, AS_AUTHORED);
        assertFalse(doorFirst.get(1).isAir(), "door first → second space filled");

        List<MultiBlockVariants.Write> stoneFirst = MultiBlockVariants.expand(
            List.of(stone, door()), stone, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(stone.state(), stoneFirst.get(0).state());
        assertTrue(stoneFirst.get(1).isAir(), "single first → only the cell itself");
    }

    @Test
    @DisplayName("1/R lands on either space, deterministically per seed")
    void randomPositionIsDeterministicAndVaries() {
        VariantState stone = single(Blocks.STONE.defaultBlockState(), VariantSpan.Mode.ONE_RANDOM);
        List<VariantState> cell = List.of(door(), stone);
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            List<MultiBlockVariants.Write> a = MultiBlockVariants.expand(cell, stone, CELL, SEED, i, AS_AUTHORED);
            List<MultiBlockVariants.Write> b = MultiBlockVariants.expand(cell, stone, CELL, SEED, i, AS_AUTHORED);
            assertEquals(a, b);
            for (MultiBlockVariants.Write w : a) if (!w.isAir()) seen.add(w.localPos());
        }
        assertEquals(Set.of(CELL, ABOVE), seen);
    }

    @Test
    @DisplayName("2/Random re-rolls the second space among singles only — never the door")
    void randomSecondRerollsAmongSingles() {
        VariantState stone = single(Blocks.STONE.defaultBlockState(), VariantSpan.Mode.TWO_RANDOM);
        VariantState dirt = VariantState.of(Blocks.DIRT.defaultBlockState());
        List<VariantState> cell = List.of(door(), stone, dirt);
        Set<BlockState> seconds = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            List<MultiBlockVariants.Write> w = MultiBlockVariants.expand(cell, stone, CELL, SEED, i, AS_AUTHORED);
            assertEquals(stone.state(), w.get(0).state());
            seconds.add(w.get(1).state());
        }
        assertEquals(Set.of(Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState()), seconds);
    }

    @Test
    @DisplayName("An empty pick in a door cell clears both spaces")
    void emptyClearsBoth() {
        VariantState empty = VariantState.of(CarriageVariantBlocks.emptyPlaceholder());
        List<MultiBlockVariants.Write> w = MultiBlockVariants.expand(
            List.of(door(), empty), empty, CELL, SEED, 0, AS_AUTHORED);
        assertEquals(2, w.size());
        assertTrue(w.get(0).isAir() && w.get(1).isAir());
    }

    @Test
    @DisplayName("span round-trips through the sidecar JSON; AUTO is omitted")
    void spanJsonRoundTrip() {
        var blocks = BuiltInRegistries.BLOCK.asLookup();
        for (VariantSpan.Mode mode : VariantSpan.Mode.values()) {
            VariantState v = single(Blocks.STONE.defaultBlockState(), mode);
            StringBuilder sb = new StringBuilder();
            CarriageVariantBlocks.appendVariantJson(sb, v);
            assertEquals(mode == VariantSpan.Mode.AUTO, !sb.toString().contains("span"), sb.toString());
            VariantState back = CarriageVariantBlocks.parseVariantElement(
                JsonParser.parseString(sb.toString()), blocks, "test", BlockPos.ZERO);
            assertEquals(mode, back.span().mode(), sb.toString());
        }
    }

    @Test
    @DisplayName("Menu cycles: How many toggles 1↔2, sub-option cycles within its set")
    void menuCycles() {
        assertEquals(VariantSpan.Mode.TWO_SAME, VariantSpan.nextCount(VariantSpan.Mode.ONE_RANDOM));
        assertEquals(VariantSpan.Mode.ONE_FIRST, VariantSpan.nextCount(VariantSpan.Mode.TWO_RANDOM));
        assertEquals(VariantSpan.Mode.ONE_SECOND, VariantSpan.nextSub(VariantSpan.Mode.ONE_FIRST));
        assertEquals(VariantSpan.Mode.ONE_RANDOM, VariantSpan.nextSub(VariantSpan.Mode.ONE_SECOND));
        assertEquals(VariantSpan.Mode.ONE_FIRST, VariantSpan.nextSub(VariantSpan.Mode.ONE_RANDOM));
        assertEquals(VariantSpan.Mode.TWO_RANDOM, VariantSpan.nextSub(VariantSpan.Mode.TWO_SAME));
        assertEquals(VariantSpan.Mode.TWO_SAME, VariantSpan.nextSub(VariantSpan.Mode.TWO_RANDOM));
    }
}
