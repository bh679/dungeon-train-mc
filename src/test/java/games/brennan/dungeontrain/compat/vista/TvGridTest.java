package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blocks of a screen are found from any one of them by following the edges each block says it is joined on. */
class TvGridTest {

    /** A screen facing north: its columns run along x, its rows up y. */
    private static final List<Direction> SIDES = List.of(Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST);

    /** Joined wherever both blocks lie inside the {@code w} x {@code h} rectangle from {@code origin}. */
    private static BiPredicate<BlockPos, Direction> rectangle(BlockPos origin, int w, int h) {
        return (from, side) -> inside(from, origin, w, h) && inside(from.relative(side), origin, w, h);
    }

    private static boolean inside(BlockPos p, BlockPos origin, int w, int h) {
        return p.getZ() == origin.getZ() && p.getX() >= origin.getX() && p.getX() < origin.getX() + w
            && p.getY() >= origin.getY() && p.getY() < origin.getY() + h;
    }

    @Test
    void aLoneTvIsItsOwnScreen() {
        BlockPos p = new BlockPos(4, 70, -3);
        assertEquals(Set.of(p), TvGrid.walk(p, SIDES, (from, side) -> false));
    }

    @Test
    void everyBlockOfAScreenIsFoundFromAnyOfThem() {
        BlockPos origin = new BlockPos(10, 64, 0);
        Set<BlockPos> fromCorner = TvGrid.walk(origin, SIDES, rectangle(origin, 3, 2));
        Set<BlockPos> fromMiddle = TvGrid.walk(origin.offset(1, 1, 0), SIDES, rectangle(origin, 3, 2));
        assertEquals(6, fromCorner.size());
        assertEquals(fromCorner, fromMiddle);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) assertTrue(fromCorner.contains(origin.offset(x, y, 0)));
    }

    @Test
    void aNeighbouringScreenIsNotWalkedInto() {
        BlockPos a = new BlockPos(0, 64, 0);
        // Two 2x2 screens side by side: the join predicate says no across the seam.
        BiPredicate<BlockPos, Direction> left = rectangle(a, 2, 2);
        assertEquals(4, TvGrid.walk(a, SIDES, left).size());
        assertEquals(4, TvGrid.walk(a.offset(1, 1, 0), SIDES, left).size());
    }

    @Test
    void aCorruptRingOfStatesCannotWalkForever() {
        Set<BlockPos> walked = TvGrid.walk(BlockPos.ZERO, SIDES, (from, side) -> true);
        assertEquals(TvGrid.MAX_TILES, walked.size());
    }
}
