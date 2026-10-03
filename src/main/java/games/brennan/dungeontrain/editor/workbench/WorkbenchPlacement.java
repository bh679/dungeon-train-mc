package games.brennan.dungeontrain.editor.workbench;

import games.brennan.dungeontrain.editor.EditorLayerSweep;
import games.brennan.dungeontrain.editor.EditorLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a newly staged build stands in the Workbench.
 *
 * <p>The Workbench is the one editor category whose plots are <em>placed</em> rather than laid out:
 * every other category computes its origins from registry order, so adding a template shifts the
 * ones after it. Here a build is given a slot once, the slot is recorded in the world, and nothing
 * moves it afterwards — a removed build leaves a gap, and a later build small enough to fit may take
 * it. This class is the pure allocator; the recording is {@code DungeonTrainWorldData}.</p>
 *
 * <p>One row along {@code +X} at {@code Z = 0}, on the lower {@link EditorLayout#BUILDINGS_PLOT_Y}
 * layer so a building as tall as the Buildings category allows fits under the sky's ceiling. A
 * caller may name an {@code anchorX} — the player's X when the Workbench is the resident category —
 * and the first free slot at or past it is preferred, so a build stamps beside whoever loaded it.</p>
 */
public final class WorkbenchPlacement {

    /** Plot floor of every Workbench slot. */
    public static final int PLOT_Y = EditorLayout.BUILDINGS_PLOT_Y;
    /** Z of every Workbench slot's origin: the shared editor origin, like every other row. */
    public static final int ROW_Z = 0;
    /** Air between two slots' cages. */
    public static final int GAP = EditorLayout.GAP;
    /** First X a slot may start at. */
    public static final int FIRST_X = 0;

    private WorkbenchPlacement() {}

    /**
     * The origin of the first slot of {@code size} that overlaps none of {@code taken}, scanning
     * {@code +X} from {@code FIRST_X}. Boxes in {@code taken} are cage-inclusive
     * ({@link EditorLayerSweep#plotBox}), so the gap is measured cage to cage.
     */
    public static BlockPos nextFree(Collection<BoundingBox> taken, Vec3i size) {
        return nextFree(taken, size, FIRST_X);
    }

    /**
     * As {@link #nextFree(Collection, Vec3i)}, preferring the first free slot at or past
     * {@code anchorX}. Falls back to the first free slot anywhere when nothing fits past the anchor —
     * which cannot happen, since the row is unbounded in {@code +X}, but the scan is written to
     * terminate regardless.
     */
    public static BlockPos nextFree(Collection<BoundingBox> taken, Vec3i size, int anchorX) {
        return nextFree(taken, size, anchorX, box -> false);
    }

    /**
     * How many candidate slots the scan will try before giving up on "free of blocks" and landing
     * past every recorded box. Each rejection by {@code occupied} steps one gap along, so this bounds
     * the walk along a long run of stray blocks.
     */
    static final int MAX_OCCUPIED_STEPS = 512;

    /**
     * As {@link #nextFree(Collection, Vec3i, int)}, also refusing any slot {@code occupied} says holds
     * blocks — the world itself, not just the record. The record can lag the world (a plot whose entry
     * was lost, a leftover of another category's layer, an erase still queued), and a build must never
     * be stamped into something standing: a slot rejected only by {@code occupied} steps one gap along
     * and tries again.
     */
    public static BlockPos nextFree(Collection<BoundingBox> taken, Vec3i size, int anchorX,
                                    Predicate<BoundingBox> occupied) {
        int startX = Math.max(FIRST_X, anchorX);
        int x = startX;
        for (int guard = 0; guard <= taken.size() + 1 + MAX_OCCUPIED_STEPS; guard++) {
            BoundingBox candidate = EditorLayerSweep.plotBox(new BlockPos(x, PLOT_Y, ROW_Z), size);
            BoundingBox hit = firstOverlap(candidate, taken);
            if (hit != null) {
                // The next slot starts GAP past the cage it collided with.
                x = hit.maxX() + 1 + GAP;
                continue;
            }
            if (occupied.test(candidate)) {
                x += GAP + 1;
                continue;
            }
            return new BlockPos(x, PLOT_Y, ROW_Z);
        }
        // Past every recorded box, whatever the world holds — the stamp erases its footprint first.
        int maxX = FIRST_X;
        for (BoundingBox b : taken) maxX = Math.max(maxX, b.maxX() + 1 + GAP);
        return new BlockPos(Math.max(maxX, x), PLOT_Y, ROW_Z);
    }

    /** The cage-inclusive box a build of {@code size} at {@code origin} occupies. */
    public static BoundingBox boxAt(BlockPos origin, Vec3i size) {
        return EditorLayerSweep.plotBox(origin, size);
    }

    /** {@code [x, y, z, sx, sy, sz]} — the world-data form — as a cage-inclusive box. */
    public static BoundingBox boxOf(int[] recorded) {
        if (recorded == null || recorded.length != 6) return null;
        return boxAt(new BlockPos(recorded[0], recorded[1], recorded[2]),
                new Vec3i(recorded[3], recorded[4], recorded[5]));
    }

    /** The world-data form of a plot at {@code origin} with footprint {@code size}. */
    public static int[] record(BlockPos origin, Vec3i size) {
        return new int[] {origin.getX(), origin.getY(), origin.getZ(), size.getX(), size.getY(), size.getZ()};
    }

    private static BoundingBox firstOverlap(BoundingBox candidate, Collection<BoundingBox> taken) {
        // Lowest maxX first so the jump lands on the nearest free gap, not past a far box.
        BoundingBox nearest = null;
        for (BoundingBox b : taken) {
            if (b == null || !b.intersects(candidate)) continue;
            if (nearest == null || b.maxX() < nearest.maxX()) nearest = b;
        }
        return nearest;
    }

    /** Whether {@code box} overlaps any of {@code taken}. */
    public static boolean collides(BoundingBox box, List<BoundingBox> taken) {
        return firstOverlap(box, taken) != null;
    }
}
