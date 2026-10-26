package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;

/**
 * The default facing of a standing player head that joins a variant cell: it looks into the room.
 * Options over the compass point facing the footprint's centre plus the two points either side
 * (within 90 degrees), so a row of heads faces in with a little spread; a head at the very centre
 * gets all eight. The same rule #1800 applied once to the shipped templates, now applied as heads
 * are added.
 *
 * <p>Compass slots run s, sw, w, nw, n, ne, e, se ({@link RotationApplier}); slot {@code k} is
 * rotation {@code 2k}, which faces {@code (-sin 45k°, cos 45k°)} on x / z.</p>
 */
public final class HeadAim {

    private static final int SLOTS = 8;
    /** Slots either side of the centre-facing one: two is 90 degrees. */
    private static final int SPREAD = 2;
    /** Closer than this to the centre on both axes counts as at it. */
    private static final double AT_CENTRE = 0.01;

    private HeadAim() {}

    /** Options rotation facing {@code local}'s room centre, plus {@link #SPREAD} points each way. */
    public static VariantRotation towardCentre(BlockPos local, Vec3i footprint) {
        double dx = (footprint.getX() - 1) / 2.0 - local.getX();
        double dz = (footprint.getZ() - 1) / 2.0 - local.getZ();
        if (Math.abs(dx) < AT_CENTRE && Math.abs(dz) < AT_CENTRE) {
            return new VariantRotation(VariantRotation.Mode.OPTIONS, (1 << SLOTS) - 1);
        }
        int mask = 0;
        int centre = centreSlot(dx, dz);
        for (int d = -SPREAD; d <= SPREAD; d++) mask |= 1 << Math.floorMod(centre + d, SLOTS);
        return new VariantRotation(VariantRotation.Mode.OPTIONS, mask);
    }

    /**
     * {@code s} aimed into the room when it is a standing player head — its rotation from
     * {@link #towardCentre} and its state turned to the centre-facing point, so the editor shows it
     * looking in. Anything else comes back unchanged.
     */
    public static VariantState aim(VariantState s, BlockPos local, Vec3i footprint) {
        if (s == null || s.state() == null || !s.state().is(Blocks.PLAYER_HEAD)) return s;
        double dx = (footprint.getX() - 1) / 2.0 - local.getX();
        double dz = (footprint.getZ() - 1) / 2.0 - local.getZ();
        VariantState out = s.withRotation(towardCentre(local, footprint));
        boolean atCentre = Math.abs(dx) < AT_CENTRE && Math.abs(dz) < AT_CENTRE;
        return atCentre ? out
            : out.withState(RotationApplier.applySlot(s.state(), centreSlot(dx, dz)), s.blockEntityNbt());
    }

    /** The compass slot whose facing points along {@code (dx, dz)}. */
    static int centreSlot(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(-dx, dz));
        return Math.floorMod((int) Math.round(degrees / 45.0), SLOTS);
    }
}
