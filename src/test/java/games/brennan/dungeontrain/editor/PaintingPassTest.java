package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.compat.PaintingCatalog.Picture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaintingPass} geometry and picture choice. Ground truth for the offsets is Fast Paintings'
 * own {@code getMasterPos}: {@code master = cell.above(y_offset).relative(facing.getClockWise(), x_offset)};
 * for the centring, vanilla's {@code Painting#calculateBoundingBox} (an even side shifts half a block
 * along {@code facing.getCounterClockWise()} / up).
 */
final class PaintingPassTest {

    private static final BlockPos ANCHOR = new BlockPos(10, 64, -3);

    private static Picture pic(String name, int w, int h) {
        return new Picture(ResourceLocation.withDefaultNamespace(name), w, h);
    }

    @Test
    @DisplayName("every cell points at the one master, for every size and facing")
    void offsetsResolveToMaster() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int[] size : new int[][] {{1, 1}, {2, 1}, {1, 2}, {2, 2}, {3, 3}, {4, 2}, {4, 3}, {4, 4}}) {
                List<PaintingPass.Cell> cells = PaintingPass.layout(ANCHOR, facing, size[0], size[1]);
                assertEquals(size[0] * size[1], cells.size());
                long masters = cells.stream().filter(PaintingPass.Cell::master).count();
                assertEquals(1, masters, "one master for " + facing + " " + size[0] + "x" + size[1]);
                BlockPos master = cells.stream().filter(PaintingPass.Cell::master).findFirst().orElseThrow().pos();
                Set<BlockPos> seen = new HashSet<>();
                for (PaintingPass.Cell c : cells) {
                    assertTrue(seen.add(c.pos()), "no duplicate cells");
                    assertEquals(master, c.pos().above(c.yOffset()).relative(facing.getClockWise(), c.xOffset()));
                    assertTrue(c.xOffset() >= 0 && c.xOffset() < size[0]);
                    assertTrue(c.yOffset() >= 0 && c.yOffset() < size[1]);
                }
            }
        }
    }

    @Test
    @DisplayName("centred like vanilla: odd sides straddle the anchor, even sides grow along the run / up")
    void vanillaCentring() {
        Direction facing = Direction.NORTH;
        Direction run = facing.getCounterClockWise();
        assertEquals(Set.of(ANCHOR), positions(PaintingPass.layout(ANCHOR, facing, 1, 1)));
        assertEquals(Set.of(ANCHOR, ANCHOR.relative(run)), positions(PaintingPass.layout(ANCHOR, facing, 2, 1)));
        assertEquals(Set.of(ANCHOR, ANCHOR.above()), positions(PaintingPass.layout(ANCHOR, facing, 1, 2)));
        // 4×3: along the run -1..2, up -1..1.
        Set<BlockPos> four = positions(PaintingPass.layout(ANCHOR, facing, 4, 3));
        assertTrue(four.contains(ANCHOR.relative(run, -1).below()));
        assertTrue(four.contains(ANCHOR.relative(run, 2).above()));
        assertEquals(12, four.size());
        // The master is the top block at the run's start.
        PaintingPass.Cell master = PaintingPass.layout(ANCHOR, facing, 4, 3).stream()
            .filter(PaintingPass.Cell::master).findFirst().orElseThrow();
        assertEquals(ANCHOR.relative(run, -1).above(), master.pos());
    }

    @Test
    @DisplayName("anchorOf undoes layout: any square's master leads back to the one anchor")
    void anchorOfInvertsLayout() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int w = 1; w <= 4; w++) {
                for (int h = 1; h <= 4; h++) {
                    BlockPos master = PaintingPass.layout(ANCHOR, facing, w, h).stream()
                        .filter(PaintingPass.Cell::master).findFirst().orElseThrow().pos();
                    assertEquals(ANCHOR, PaintingPass.anchorOf(master, facing, w, h), facing + " " + w + "x" + h);
                }
            }
        }
    }

    @Test
    @DisplayName("Any size keeps only the largest pictures that fit, as vanilla does")
    void anyKeepsLargest() {
        List<Picture> fitting = List.of(pic("a", 1, 1), pic("b", 2, 2), pic("c", 2, 1), pic("d", 2, 2));
        for (long roll = 0; roll < 16; roll++) {
            Picture chosen = PaintingPass.choose(fitting, VariantPainting.ANY, roll);
            assertEquals(4, chosen.area(), "roll " + roll);
        }
    }

    @Test
    @DisplayName("a fixed size picks among every fitting picture of it; nothing fitting gives null")
    void fixedSizeAndEmpty() {
        List<Picture> fitting = List.of(pic("a", 2, 1), pic("b", 2, 1));
        Set<ResourceLocation> chosen = new HashSet<>();
        for (long roll = 0; roll < 8; roll++) {
            chosen.add(PaintingPass.choose(fitting, VariantPainting.of(2, 1, null), roll).id());
        }
        assertEquals(2, chosen.size());
        assertNull(PaintingPass.choose(List.of(), VariantPainting.ANY, 3));
    }

    @Test
    @DisplayName("the picture roll is stable per seed / cell / index and varies between cells")
    void rollIsDeterministic() {
        long a = PaintingPass.roll(42L, ANCHOR.asLong(), 7);
        assertEquals(a, PaintingPass.roll(42L, ANCHOR.asLong(), 7));
        Set<Long> rolls = new HashSet<>();
        for (int i = 0; i < 16; i++) rolls.add(PaintingPass.roll(42L, ANCHOR.asLong(), i));
        assertTrue(rolls.size() > 12, "rolls spread across carriages");
    }

    private static Set<BlockPos> positions(List<PaintingPass.Cell> cells) {
        Set<BlockPos> out = new HashSet<>();
        for (PaintingPass.Cell c : cells) out.add(c.pos());
        return out;
    }
}
