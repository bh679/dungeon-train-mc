package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Where a visitor stands and looks so the TV fills their view: a little way out from the screen's
 * centre along the direction it faces, eyes on the screen's middle.
 *
 * <p>Pure: the screen's blocks and facing go in, a pose comes out, so every facing is unit-tested.</p>
 */
public final class TributeRoomCamera {

    /** How far out from the screen plane the visitor's eyes stand, in blocks. */
    public static final double STAND_OFF = 1.5;
    /** A standing player's eye height. */
    static final double EYE_HEIGHT = 1.62;

    private TributeRoomCamera() {}

    /** A place to stand and a way to look. Yaw and pitch in Minecraft's degrees. */
    public record Pose(double x, double y, double z, float yaw, float pitch) {}

    /**
     * The pose for a screen made of {@code screen} blocks whose picture faces {@code facing}, standing
     * on a floor whose walkable surface is {@code floorY} (the first air row of the room).
     *
     * <p>The screen plane is the face of the screen blocks on the {@code facing} side; the stand point
     * is {@link #STAND_OFF} beyond it. Pitch is whatever brings the eye line onto the screen's centre.</p>
     */
    public static Pose facing(List<BlockPos> screen, Direction facing, int floorY) {
        if (screen.isEmpty()) throw new IllegalArgumentException("no screen blocks");
        double cx = 0, cy = 0, cz = 0;
        for (BlockPos p : screen) {
            cx += p.getX() + 0.5;
            cy += p.getY() + 0.5;
            cz += p.getZ() + 0.5;
        }
        cx /= screen.size();
        cy /= screen.size();
        cz /= screen.size();
        // From the block centre to its facing-side face is half a block; then the stand-off.
        double out = 0.5 + STAND_OFF;
        double x = cx + facing.getStepX() * out;
        double z = cz + facing.getStepZ() * out;
        double eyeY = floorY + EYE_HEIGHT;
        // Look back at the screen: the direction opposite to the one it faces.
        float yaw = facing.getOpposite().toYRot();
        double drop = eyeY - cy;
        float pitch = (float) Math.toDegrees(Math.atan2(drop, STAND_OFF + 0.5));
        return new Pose(x, floorY, z, Mth.wrapDegrees(yaw), pitch);
    }
}
