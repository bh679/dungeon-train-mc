package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.snapshot.CarriageOcclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Where to stand just outside the carriage a body lies in, looking back in at it: the side wall
 * nearest the body, preferring a side with a window at eye height so the shot looks in through it.
 *
 * <p>Carriage (plot) space: x along the train, z across it. The search walks across from the body
 * on both sides until it meets the wall, then checks the wall column one block up (eye height) for
 * glass or a pane. An open deck (no wall within {@link #MAX_HALF_WIDTH}) still gets a spot just off
 * the edge. Pure maths over a block lookup, so it is unit-testable.</p>
 *
 * @param side        +1 or -1: which side of the track (sign of the across offset)
 * @param wallAcross  distance across from the body to the wall's outer face (or the deck edge)
 * @param window      whether the wall there has a window at eye height
 */
public record CarriageWindow(int side, double wallAcross, boolean window) {

    /** Carriages are 7 wide; look no further than this for a wall. */
    static final int MAX_HALF_WIDTH = 6;
    /** How far outside the wall the lens starts. */
    static final double STAND_OFF = 1.6;

    /** A block lookup in carriage space. */
    public interface Blocks {
        boolean solid(BlockPos local);
        BlockState state(BlockPos local);
    }

    /** Pick the better side: a window wins, then the nearer wall. */
    public static CarriageWindow find(Blocks blocks, Vector3d bodyLocal) {
        CarriageWindow plus = scan(blocks, bodyLocal, 1);
        CarriageWindow minus = scan(blocks, bodyLocal, -1);
        if (plus.window != minus.window) return plus.window ? plus : minus;
        return plus.wallAcross <= minus.wallAcross ? plus : minus;
    }

    static CarriageWindow scan(Blocks blocks, Vector3d bodyLocal, int side) {
        int bx = (int) Math.floor(bodyLocal.x);
        int by = (int) Math.floor(bodyLocal.y);
        int bz = (int) Math.floor(bodyLocal.z);
        for (int d = 1; d <= MAX_HALF_WIDTH; d++) {
            BlockPos foot = new BlockPos(bx, by, bz + side * d);
            BlockPos eye = foot.above();
            boolean wall = blocks.solid(foot) || blocks.solid(eye);
            boolean window = isWindow(blocks.state(eye)) || isWindow(blocks.state(foot));
            if (wall || window) {
                double across = Math.abs((bz + side * d + (side > 0 ? 1.0 : 0.0)) - bodyLocal.z);
                return new CarriageWindow(side, across, window);
            }
        }
        return new CarriageWindow(side, MAX_HALF_WIDTH * 0.6, false);
    }

    static boolean isWindow(BlockState state) {
        return state.getBlock() instanceof TransparentBlock || state.getBlock() instanceof IronBarsBlock;
    }

    /** Lens start: just outside the wall, at eye height, looking in. Carriage space, relative to the body. */
    public Vector3d start() {
        return new Vector3d(0.0, 1.2, side * (wallAcross + STAND_OFF));
    }

    /** Lens end: a little further out and up, on the same side, behind the body along the train. */
    public Vector3d end() {
        return new Vector3d(-5.0, 6.0, side * (wallAcross + 9.0));
    }

    /** Adapter over a scanned carriage. */
    public static Blocks of(CarriageOcclusion.Carriage carriage) {
        return new Blocks() {
            @Override public boolean solid(BlockPos local) { return CarriageOcclusion.solidAt(carriage, local); }
            @Override public BlockState state(BlockPos local) { return CarriageOcclusion.blockAt(carriage, local); }
        };
    }
}
