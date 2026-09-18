package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a player lands when they enter a plot.
 *
 * <p><b>From above</b> ({@link #inFrontOfMenu}): on the roof, a few blocks in front of the plot's
 * world-space menu, facing it. Every editor's {@code enter(…, onTop=true)} used to drop the player
 * at the footprint centre with whatever heading they arrived with — on a long carriage that is half
 * a plot from the menu, as often as not behind them. The menu anchors at
 * {@link EditorPlotLabels#anchorAbove}, so the landing spot is derived from that same anchor: the
 * two cannot drift apart.</p>
 *
 * <p><b>Inside</b> ({@link #inside}): at a preferred cell — the front doorway, or the centre when
 * asked — but only if a player fits there. Custom templates wall up doorways and fill centres, and a
 * teleport into a block is a teleport into darkness, so the landing steps to the nearest free
 * two-block column instead.</p>
 *
 * <p>{@link #land} is the one entry point the editors use: it picks between the two from the
 * caller's {@code onTop}/{@link Inside} request, so no editor carries its own copy of that
 * dispatch.</p>
 */
public record EditorPlotArrival(double x, double y, double z, float yaw, float pitch) {

    /** Blocks to stand back from the menu, along -X, so it is readable rather than in your face. */
    static final int STANDOFF = 3;

    /** Minecraft yaw that looks along +X — toward the menu from the roof, into the build from its door. */
    static final float FACING_POSITIVE_X = -90f;

    /** Where an inside landing aims for before the free-space search has its say. */
    public enum Inside {
        /** The -X doorway, facing in — the way a player walking the train arrives. */
        FRONT_DOOR,
        /** The footprint centre, heading kept — the landing every inside teleport used to make. */
        CENTRE
    }

    /**
     * The spot on the roof of the plot at {@code origin} with the given {@code footprint}
     * (length, height, width) from which its menu is straight ahead.
     *
     * <p>Clamped to the plot's own -X edge so a short plot — a pillar section is one block long —
     * still lands the player on the roof rather than in the air beside it.</p>
     */
    public static EditorPlotArrival inFrontOfMenu(BlockPos origin, Vec3i footprint) {
        BlockPos anchor = EditorPlotLabels.anchorAbove(origin, footprint);
        double x = Math.max(origin.getX() + 0.5, anchor.getX() + 0.5 - STANDOFF);
        // Same roof height the centre landing used: one above the bedrock cage.
        double y = origin.getY() + footprint.getY() + 1.0;
        double z = anchor.getZ() + 0.5;
        return new EditorPlotArrival(x, y, z, FACING_POSITIVE_X, 0f);
    }

    /**
     * Land inside the plot at {@code origin}/{@code footprint}, at {@code preferred} if a player
     * fits there, else at the nearest cell that has room — see {@link #freeCell}.
     *
     * <p>{@code yaw}/{@code pitch} are the heading to arrive with; the door landing faces in
     * ({@link #FACING_POSITIVE_X}), the centre landing keeps the player's own.</p>
     */
    public static EditorPlotArrival inside(ServerLevel level, BlockPos origin, Vec3i footprint,
                                           BlockPos preferred, float yaw, float pitch) {
        BlockPos cell = freeCell(origin, footprint, preferred, p -> passable(level, p));
        return new EditorPlotArrival(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, yaw, pitch);
    }

    /** The lower cell of the plot's -X doorway: {@code doorBase} itself, as an inside landing. */
    public static EditorPlotArrival atFrontDoor(ServerLevel level, BlockPos origin, Vec3i footprint,
                                                BlockPos doorBase) {
        return inside(level, origin, footprint, doorBase, FACING_POSITIVE_X, 0f);
    }

    /** The footprint centre on the floor, heading kept — the landing every inside teleport used to make. */
    public static EditorPlotArrival atCentre(ServerLevel level, BlockPos origin, Vec3i footprint,
                                             ServerPlayer player) {
        return inside(level, origin, footprint, centreOf(origin, footprint), player.getYRot(), player.getXRot());
    }

    /** The floor cell at the middle of the footprint. */
    private static BlockPos centreOf(BlockPos origin, Vec3i footprint) {
        return new BlockPos(origin.getX() + footprint.getX() / 2, origin.getY() + 1,
            origin.getZ() + footprint.getZ() / 2);
    }

    /**
     * Put {@code player} in the plot at {@code origin}/{@code footprint}: on the roof in front of
     * the menu when {@code onTop}, else at {@code doorBase} facing in when {@code inside} asks for
     * the door and the plot has one, else at the centre with the player's own heading.
     *
     * @param doorBase the lower cell of the plot's -X doorway, or {@code null} for a plot without
     *                 one — parts, pillars, track tiles, tunnels — which lands at the centre
     *                 whatever {@code inside} says.
     */
    public static void land(ServerPlayer player, ServerLevel level, BlockPos origin, Vec3i footprint,
                            boolean onTop, Inside inside, @Nullable BlockPos doorBase) {
        landing(origin, footprint, onTop, inside, doorBase, player.getYRot(), player.getXRot(),
            p -> passable(level, p)).teleport(player, level);
    }

    /**
     * The dispatch behind {@link #land}, with the player's heading and the world's block test
     * passed in so it can be checked without either.
     */
    static EditorPlotArrival landing(BlockPos origin, Vec3i footprint, boolean onTop, Inside inside,
                                     @Nullable BlockPos doorBase, float yaw, float pitch,
                                     Predicate<BlockPos> fits) {
        if (onTop) return inFrontOfMenu(origin, footprint);
        boolean door = inside == Inside.FRONT_DOOR && doorBase != null;
        BlockPos preferred = door ? doorBase : centreOf(origin, footprint);
        BlockPos cell = freeCell(origin, footprint, preferred, fits);
        return new EditorPlotArrival(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5,
            door ? FACING_POSITIVE_X : yaw, door ? 0f : pitch);
    }

    /**
     * The cell a player can stand in nearest {@code preferred}, by {@code fits} — a two-block column
     * test on the cell and the one above it.
     *
     * <p>Order: {@code preferred} itself; then its row toward +X, which from a doorway is the
     * natural "step inside"; then every interior cell by distance. Interior means one block in from
     * every face of the footprint, so the fallback never lands in the shell or the cage. If nothing
     * fits — a sealed build — {@code preferred} comes back as-is, which is where the landing always
     * was.</p>
     */
    static BlockPos freeCell(BlockPos origin, Vec3i footprint, BlockPos preferred, Predicate<BlockPos> fits) {
        if (fits.test(preferred)) return preferred;
        int maxX = origin.getX() + footprint.getX() - 2;
        for (int x = preferred.getX() + 1; x <= maxX; x++) {
            BlockPos p = new BlockPos(x, preferred.getY(), preferred.getZ());
            if (fits.test(p)) return p;
        }
        List<BlockPos> interior = new ArrayList<>();
        for (int dx = 1; dx <= footprint.getX() - 2; dx++) {
            for (int dz = 1; dz <= footprint.getZ() - 2; dz++) {
                for (int dy = 1; dy <= footprint.getY() - 3; dy++) {
                    interior.add(origin.offset(dx, dy, dz));
                }
            }
        }
        interior.sort(Comparator.comparingDouble(p -> p.distSqr(preferred)));
        for (BlockPos p : interior) {
            if (fits.test(p)) return p;
        }
        return preferred;
    }

    /** Whether a player fits standing at {@code feet}: nothing to collide with there or at head height. */
    static boolean passable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** Put {@code player} here. */
    public void teleport(ServerPlayer player, ServerLevel level) {
        player.teleportTo(level, x, y, z, yaw, pitch);
    }
}
