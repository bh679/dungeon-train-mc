package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Which face of a portal room a resize moves, and where the room's contents land afterwards.
 *
 * <p>Growth used to be one-sided: the plot origin is fixed by {@code TrackSidePlots}, so every block
 * a stepper added arrived on the {@code +X}/{@code +Y}/{@code +Z} face and every block it removed came
 * off the same one. A room centred on the doorway line drifted off it as the author dialled a size in.
 * Length and width alternate faces here instead, so a room grows outwards from where it was built.</p>
 *
 * <p><b>Height only ever moves its ceiling.</b> A room's floor is the corridor's floor —
 * {@link PortalRoomLayout#roomOrigin} anchors Y at the entry twin's own origin — and the doorways open
 * onto it. Alternating in Y would drop the floor a block below the corridor mouth on every other
 * click and leave the doorway plane opening into a wall.</p>
 *
 * <p><b>The side rule is a function of the size, never of a stored cursor.</b> A step from {@code s} to
 * {@code s + 1} lands on {@link #growSide}{@code (axis, s)}, and the step back from {@code s + 1} to
 * {@code s} takes that same face away. Grow and shrink are therefore exact inverses at every size, so
 * a slab that a shrink stashed is always restored on the face it left from — no history to keep in
 * step with the world, and nothing to go stale across a restart. Keyed on the size rather than on the
 * order of clicks, "remember what was cropped" reduces to a map lookup: see
 * {@code PortalRoomResizeMemory}.</p>
 */
public final class PortalRoomResize {

    /** Which axis of a room a size command addresses. */
    public enum Axis { LENGTH, WIDTH, HEIGHT }

    /** Which face of the box a step moves — the low local coordinate, or the high one. */
    public enum Side { MIN, MAX }

    /**
     * One single-block step.
     *
     * @param axis      the axis that changed
     * @param side      the face that moved
     * @param grow      true when a row was added, false when one was removed
     * @param sizeAfter the whole box after this step
     * @param shift     where the surviving content lands in the new box — {@code +1} on {@code axis}
     *                  when the {@link Side#MIN} face grew, {@code -1} when it shrank, zero otherwise
     * @param slabIndex the local coordinate, on {@code axis}, of the row this step adds or removes.
     *                  For a grow it indexes into the <b>new</b> box; for a shrink, into the old one.
     * @param memoryKey the size this step's slab is filed under — the smaller of the two sizes, so a
     *                  shrink and the grow that undoes it name the same record
     */
    public record Step(Axis axis, Side side, boolean grow, Vec3i sizeAfter, Vec3i shift,
                       int slabIndex, int memoryKey) {}

    private PortalRoomResize() {}

    /** The value {@code axis} currently has in {@code size}. */
    public static int of(Vec3i size, Axis axis) {
        return switch (axis) {
            case LENGTH -> size.getX();
            case WIDTH -> size.getZ();
            case HEIGHT -> size.getY();
        };
    }

    /** {@code size} with {@code axis} set to {@code value}. */
    public static Vec3i with(Vec3i size, Axis axis, int value) {
        return switch (axis) {
            case LENGTH -> new Vec3i(value, size.getY(), size.getZ());
            case WIDTH -> new Vec3i(size.getX(), size.getY(), value);
            case HEIGHT -> new Vec3i(size.getX(), value, size.getZ());
        };
    }

    /** A unit vector along {@code axis}, scaled by {@code n}. */
    public static Vec3i along(Axis axis, int n) {
        return switch (axis) {
            case LENGTH -> new Vec3i(n, 0, 0);
            case WIDTH -> new Vec3i(0, 0, n);
            case HEIGHT -> new Vec3i(0, n, 0);
        };
    }

    /** The smallest legal value of {@code axis} in this world — the parity rule counts up from here. */
    public static int minOf(CarriageDims dims, Axis axis) {
        return switch (axis) {
            case LENGTH -> PortalRoomLayout.MIN_LENGTH;
            case WIDTH -> PortalRoomLayout.minWidth(dims);
            case HEIGHT -> PortalRoomLayout.minHeight(dims);
        };
    }

    /**
     * Which face a step from {@code sizeBefore} to {@code sizeBefore + 1} lands on.
     *
     * <p>Counted from {@link #minOf} rather than from zero so the alternation is stable against a
     * world whose carriages — and therefore whose minimum room width — differ from another's.</p>
     */
    public static Side growSide(CarriageDims dims, Axis axis, int sizeBefore) {
        if (axis == Axis.HEIGHT) return Side.MAX;
        int steps = sizeBefore - minOf(dims, axis);
        return Math.floorMod(steps, 2) == 0 ? Side.MAX : Side.MIN;
    }

    /**
     * The ordered single-block steps that take {@code from} to {@code wanted} on one axis.
     *
     * <p>A typed size is walked one block at a time on purpose: it then splits across the two faces
     * exactly as tapping the stepper that many times would, so typing {@code 21} and clicking
     * {@code +} ten times leave the room in the same place.</p>
     *
     * <p>{@code wanted} is taken as already clamped — {@link PortalRoomLayout#clampSize} is the single
     * authority on what is legal, and re-clamping here would hide a caller that skipped it.</p>
     */
    public static List<Step> plan(CarriageDims dims, Axis axis, Vec3i from, int wanted) {
        List<Step> steps = new ArrayList<>();
        Vec3i size = from;
        int current = of(size, axis);

        while (current < wanted) {
            Side side = growSide(dims, axis, current);
            size = with(size, axis, current + 1);
            steps.add(new Step(axis, side, true, size,
                side == Side.MIN ? along(axis, 1) : Vec3i.ZERO,
                side == Side.MIN ? 0 : current,
                current));
            current++;
        }
        while (current > wanted) {
            // The face that a grow to `current` would have added is the face this takes back.
            Side side = growSide(dims, axis, current - 1);
            size = with(size, axis, current - 1);
            steps.add(new Step(axis, side, false, size,
                side == Side.MIN ? along(axis, -1) : Vec3i.ZERO,
                side == Side.MIN ? 0 : current - 1,
                current - 1));
            current--;
        }
        return steps;
    }

    /**
     * The steps that take {@code from} to the whole box {@code wanted}.
     *
     * <p>Axis order is fixed — length, then width, then height — so the same typed size always
     * produces the same plan.</p>
     */
    public static List<Step> plan(CarriageDims dims, Vec3i from, Vec3i wanted) {
        List<Step> steps = new ArrayList<>();
        Vec3i size = from;
        for (Axis axis : Axis.values()) {
            List<Step> forAxis = plan(dims, axis, size, of(wanted, axis));
            steps.addAll(forAxis);
            if (!forAxis.isEmpty()) size = forAxis.get(forAxis.size() - 1).sizeAfter();
        }
        return steps;
    }

    /**
     * The world box of the single row {@code step} adds or removes.
     *
     * <p>{@code size} is the box the row is indexed against — {@link Step#sizeAfter} for a grow, the
     * size before the step for a shrink, which is the same pairing {@link Step#slabIndex} documents.
     * One block thick on {@code step}'s axis and the room's full cross-section on the other two.</p>
     *
     * <p>Here rather than inlined in the editor because it is the one piece of the "grow into air"
     * rule that is pure arithmetic: what a grow must leave blank is exactly this box.</p>
     */
    public static BoundingBox slabBox(BlockPos origin, Vec3i size, Step step) {
        BlockPos at = origin.offset(along(step.axis(), step.slabIndex()));
        Vec3i slab = with(size, step.axis(), 1);
        return new BoundingBox(
            at.getX(), at.getY(), at.getZ(),
            at.getX() + slab.getX() - 1,
            at.getY() + slab.getY() - 1,
            at.getZ() + slab.getZ() - 1);
    }

    /** Total shift the surviving content undergoes across {@code steps}. */
    public static Vec3i totalShift(List<Step> steps) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (Step s : steps) {
            x += s.shift().getX();
            y += s.shift().getY();
            z += s.shift().getZ();
        }
        return new Vec3i(x, y, z);
    }
}
