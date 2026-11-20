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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Rot Free: a lock-group edit made in one cell reaches its siblings with each sibling's own row
 * rotation and flip kept ({@link VariantRowOrientation#keepOwnOrientation}).
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
    @DisplayName("a row added in cell A arrives in B with A's settings, B's existing rows untouched")
    void addedRowKeepsOthers() {
        List<VariantState> own = List.of(stair(Direction.EAST), planks());
        VariantState added = VariantState.of(Blocks.SPRUCE_STAIRS.defaultBlockState())
            .withRotation(VariantRotation.lock(Direction.SOUTH));
        List<VariantState> edited = List.of(stair(Direction.NORTH), planks(), added);

        List<VariantState> out = VariantRowOrientation.keepOwnOrientation(own, edited);

        assertEquals(3, out.size());
        assertEquals(VariantRotation.lock(Direction.EAST), out.get(0).rotation());
        assertEquals(added, out.get(2), "a new row has nothing of B's to keep");
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
