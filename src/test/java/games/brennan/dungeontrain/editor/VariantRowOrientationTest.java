package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Rot Free: a lock-group edit made in one cell reaches its siblings with each sibling's own row
 * rotation and flip kept ({@link VariantRowOrientation#keepOwnOrientation}), and a row new to the
 * cell takes the cell's own orientation ({@link VariantRowOrientation#orientToCell}).
 */
final class VariantRowOrientationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState stair(Direction facing) {
        return VariantState.of(Blocks.OAK_STAIRS.defaultBlockState()).withRotation(VariantRotation.lock(facing));
    }

    private static VariantState spruceStair(Direction facing) {
        return VariantState.of(Blocks.SPRUCE_STAIRS.defaultBlockState()).withRotation(VariantRotation.lock(facing));
    }

    private static VariantState log(Direction facing) {
        return VariantState.of(Blocks.OAK_LOG.defaultBlockState()).withRotation(VariantRotation.lock(facing));
    }

    private static VariantState planks() {
        return VariantState.of(Blocks.OAK_PLANKS.defaultBlockState());
    }

    @Test
    @DisplayName("same row count: a weight edit in cell A leaves cell B facing its own way")
    void sameCountMatchesByPosition() {
        List<VariantState> own = List.of(stair(Direction.EAST).withHalf(VariantHalf.top()), planks());
        List<VariantState> edited = List.of(stair(Direction.NORTH).withWeight(5), planks());

        List<VariantState> out = VariantRowOrientation.keepOwnOrientation(own, edited);

        assertEquals(VariantRotation.lock(Direction.EAST), out.get(0).rotation(), "B keeps its facing");
        assertEquals(VariantHalf.top(), out.get(0).half(), "B keeps its flip");
        assertEquals(5, out.get(0).weight(), "the edit itself still lands");
    }

    @Test
    @DisplayName("a row added in cell A takes B's own facing from B's matching block, B's rows untouched")
    void addedRowTakesOwnFacing() {
        List<VariantState> own = List.of(stair(Direction.EAST).withHalf(VariantHalf.top()), planks());
        List<VariantState> edited = List.of(stair(Direction.NORTH), planks(), spruceStair(Direction.SOUTH));

        List<VariantState> out = VariantRowOrientation.keepOwnOrientation(own, edited);

        assertEquals(3, out.size());
        assertEquals(VariantRotation.lock(Direction.EAST), out.get(0).rotation(), "B's own stair keeps its facing");
        assertEquals(VariantRotation.lock(Direction.EAST), out.get(2).rotation(), "the new stair faces B's way");
        assertEquals(VariantHalf.top(), out.get(2).half(), "and takes B's flip");
        assertEquals(Blocks.SPRUCE_STAIRS, out.get(2).state().getBlock(), "the block itself is A's");
    }

    @Test
    @DisplayName("the same block wins over a more recent block of the same type")
    void sameBlockFirst() {
        List<VariantState> rows = List.of(spruceStair(Direction.WEST), stair(Direction.EAST));
        VariantState out = VariantRowOrientation.orientToCell(spruceStair(Direction.NORTH), rows);
        assertEquals(VariantRotation.lock(Direction.WEST), out.rotation());
    }

    @Test
    @DisplayName("a stair takes a stair's facing and flip over a more recent slab and log")
    void sameTypeOverMostRecent() {
        List<VariantState> rows = List.of(
            stair(Direction.WEST).withHalf(VariantHalf.top()),
            VariantState.of(Blocks.OAK_SLAB.defaultBlockState()).withHalf(VariantHalf.bottom()),
            log(Direction.EAST));
        VariantState out = VariantRowOrientation.orientToCell(spruceStair(Direction.NORTH), rows);
        assertEquals(VariantRotation.lock(Direction.WEST), out.rotation());
        assertEquals(VariantHalf.top(), out.half());
    }

    @Test
    @DisplayName("a stair of another class (copper) still counts as the same type: same block-state properties")
    void sameTypeByProperties() {
        assertNotSame(Blocks.CUT_COPPER_STAIRS.getClass(), Blocks.SPRUCE_STAIRS.getClass(), "test needs a subclass");
        List<VariantState> rows = List.of(
            VariantState.of(Blocks.CUT_COPPER_STAIRS.defaultBlockState())
                .withRotation(VariantRotation.lock(Direction.WEST)).withHalf(VariantHalf.top()),
            log(Direction.EAST));
        VariantState out = VariantRowOrientation.orientToCell(spruceStair(Direction.NORTH), rows);
        assertEquals(VariantRotation.lock(Direction.WEST), out.rotation());
        assertEquals(VariantHalf.top(), out.half());
    }

    @Test
    @DisplayName("no block of the same type: the most recent facing the new block can take")
    void mostRecentFacingFallback() {
        List<VariantState> rows = List.of(
            VariantState.of(Blocks.FURNACE.defaultBlockState()).withRotation(VariantRotation.lock(Direction.WEST)),
            VariantState.of(Blocks.CHEST.defaultBlockState()).withRotation(VariantRotation.lock(Direction.SOUTH)),
            planks());
        VariantState out = VariantRowOrientation.orientToCell(spruceStair(Direction.NORTH), rows);
        assertEquals(VariantRotation.lock(Direction.SOUTH), out.rotation());
    }

    @Test
    @DisplayName("a facing the new block cannot take is skipped")
    void invalidFacingSkipped() {
        List<VariantState> rows = List.of(
            VariantState.of(Blocks.FURNACE.defaultBlockState()).withRotation(VariantRotation.lock(Direction.WEST)),
            VariantState.of(Blocks.BARREL.defaultBlockState()).withRotation(VariantRotation.lock(Direction.UP)));
        VariantState out = VariantRowOrientation.orientToCell(spruceStair(Direction.NORTH), rows);
        assertEquals(VariantRotation.lock(Direction.WEST), out.rotation(), "stairs cannot face up");
    }

    @Test
    @DisplayName("nothing in the cell has a facing: the row is left as A made it")
    void noDonorKeepsRow() {
        VariantState added = spruceStair(Direction.NORTH);
        assertSame(added, VariantRowOrientation.orientToCell(added, List.of(planks())));
    }

    @Test
    @DisplayName("a compass block never takes a facing block's slot")
    void compassIgnoresFacing() {
        VariantState sign = VariantState.of(Blocks.OAK_SIGN.defaultBlockState())
            .withRotation(new VariantRotation(VariantRotation.Mode.LOCK, 1 << 4));
        assertSame(sign, VariantRowOrientation.orientToCell(sign, List.of(stair(Direction.EAST))));
    }

    @Test
    @DisplayName("a row removed in cell A: B's remaining rows keep their own facing, matched by content")
    void removedRowMatchesByContent() {
        VariantState spruceEast = VariantState.of(Blocks.SPRUCE_STAIRS.defaultBlockState())
            .withRotation(VariantRotation.lock(Direction.EAST));
        List<VariantState> own = List.of(planks(), spruceEast, stair(Direction.WEST));
        List<VariantState> edited = List.of(
            VariantState.of(Blocks.SPRUCE_STAIRS.defaultBlockState()).withRotation(VariantRotation.lock(Direction.NORTH)),
            stair(Direction.NORTH));

        List<VariantState> out = VariantRowOrientation.keepOwnOrientation(own, edited);

        assertEquals(VariantRotation.lock(Direction.EAST), out.get(0).rotation());
        assertEquals(VariantRotation.lock(Direction.WEST), out.get(1).rotation());
    }

    @Test
    @DisplayName("a cell with no rows yet takes the edit as it is")
    void emptyOwnTakesEdit() {
        List<VariantState> edited = List.of(stair(Direction.NORTH), planks());
        assertSame(edited, VariantRowOrientation.keepOwnOrientation(null, edited));
    }
}
