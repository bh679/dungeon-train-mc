package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure geometry of re-laying Fast Paintings' block paintings under a template transform.
 *
 * <p>Fast Paintings turns every painting into {@code fastpaintings:painting} blocks: one
 * <b>master</b> cell (holds the block entity with the variant) and, for anything larger than
 * 1×1, <b>slave</b> cells that point back at it through two state properties. From the mod's
 * own {@code getMasterPos}:</p>
 *
 * <pre>master = cell.above(y_offset).relative(facing.getClockWise(), x_offset)</pre>
 *
 * <p>so the run of a painting goes along {@code facing.getCounterClockWise()} from the master and
 * <em>down</em> from it. {@code PaintingBlock} overrides neither {@code mirror()} nor
 * {@code rotate()}, so a vanilla mirrored stamp keeps the authored {@code facing} — the painting
 * lands beside the opposite wall facing away from it, fails {@code canSurvive}, and every cell
 * pops as an item. Even with the facing fixed, a mirror reverses the run, so the master would sit
 * at the wrong end and every slave would point at air.</p>
 *
 * <p>{@link #relayout} takes the cells of one stamp <b>after</b> vanilla has moved them (positions
 * are post-transform, states are still authored) and returns the same cells with {@code facing},
 * the offsets and the block-entity ownership re-derived so the painting hangs on the wall it now
 * stands beside. No Fast Paintings types here — the processor maps block states to {@link Cell}
 * and back — so this is plain geometry and unit-testable without the mod.</p>
 */
public final class PaintingBlockLayout {

    private PaintingBlockLayout() {}

    /**
     * One painting cell: where it landed, what its authored state says, and the block-entity data it
     * carried — non-null only on the authored master. {@code relayout} hands that data to whichever
     * cell becomes the master, so the variant travels with the painting rather than the cell.
     */
    public record Cell(BlockPos pos, Direction facing, int xOffset, int yOffset, Object data) {

        /** True for the cell that holds the block entity. */
        public boolean master() {
            return data != null;
        }
    }

    /**
     * Re-lay {@code cells} for a stamp that applied {@code mirror} then {@code rotation} (vanilla
     * order) and, optionally, DT's own vertical flip. Returns cells in the input order, each with
     * its new facing and offsets; the block-entity data moves to whichever cell of a painting now
     * sits at the top of the run's start. Cells whose facing is not horizontal are
     * returned untouched.
     */
    public static List<Cell> relayout(List<Cell> cells, Mirror mirror, Rotation rotation, boolean yFlipped) {
        if (cells.isEmpty()) return cells;
        if (mirror == Mirror.NONE && rotation == Rotation.NONE && !yFlipped) return cells;

        // Group by where the authored master landed: undo each cell's authored offsets along the
        // TRANSFORMED run direction (positions already moved, states did not).
        Map<BlockPos, List<Cell>> groups = new LinkedHashMap<>();
        for (Cell c : cells) {
            if (c.facing().getAxis().isVertical()) continue;
            Direction movedRun = transform(c.facing().getCounterClockWise(), mirror, rotation);
            BlockPos movedMaster = c.pos()
                .relative(movedRun, -c.xOffset())
                .above(yFlipped ? -c.yOffset() : c.yOffset());
            groups.computeIfAbsent(movedMaster, k -> new ArrayList<>()).add(c);
        }

        Map<Cell, Cell> relaid = new LinkedHashMap<>();
        for (List<Cell> group : groups.values()) {
            Direction facing = transform(group.get(0).facing(), mirror, rotation);
            Direction run = facing.getCounterClockWise();
            BlockPos master = pickMaster(group, run);
            Object data = group.stream().map(Cell::data).filter(d -> d != null).findFirst().orElse(null);
            for (Cell c : group) {
                int x = along(c.pos().subtract(master), run);
                int y = master.getY() - c.pos().getY();
                boolean isMaster = x == 0 && y == 0;
                relaid.put(c, new Cell(c.pos(), facing, x, y, isMaster ? data : null));
            }
        }

        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(relaid.getOrDefault(c, c));
        return List.copyOf(out);
    }

    /** The cell at the start of the run (least along {@code run}) and the top of the column. */
    private static BlockPos pickMaster(List<Cell> group, Direction run) {
        BlockPos best = null;
        int bestAlong = Integer.MAX_VALUE;
        for (Cell c : group) {
            int a = along(c.pos(), run);
            if (best == null || a < bestAlong || (a == bestAlong && c.pos().getY() > best.getY())) {
                best = c.pos();
                bestAlong = a;
            }
        }
        return best;
    }

    /** Signed distance of {@code v} along the horizontal direction {@code d}. */
    private static int along(BlockPos v, Direction d) {
        return v.getX() * d.getStepX() + v.getZ() * d.getStepZ();
    }

    /** A direction under the same transform {@code StructureTemplate.transform} applies to positions. */
    static Direction transform(Direction d, Mirror mirror, Rotation rotation) {
        return rotation.rotate(mirror.mirror(d));
    }
}
