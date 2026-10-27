package games.brennan.dungeontrain.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Where a block stamped into a carriage really is right now.
 *
 * <p>A carriage block lives at plot coordinates inside its Sable sub-level, while players and mobs are
 * in world space. Vanilla and mod blocks that measure distance to a player from their own block
 * position (a vault's player detector, a creaking heart's range check) never see anyone on the train
 * unless they measure from the carriage's current position of that block instead.</p>
 */
public final class PlotWorldPositions {

    private PlotWorldPositions() {}

    /**
     * World-space point of {@code pos} offset by ({@code dx},{@code dy},{@code dz}) within the block:
     * the carriage's current position of it when {@code pos} is a plot block, else the point itself.
     * {@code shipToWorld} mutates its argument, so it is handed a throwaway vector.
     */
    public static Vec3 worldPoint(Level level, BlockPos pos, double dx, double dy, double dz) {
        double x = pos.getX() + dx, y = pos.getY() + dy, z = pos.getZ() + dz;
        if (!(level instanceof ServerLevel server)) return new Vec3(x, y, z);
        ManagedShip ship = Shipyards.of(server).findAt(pos);
        if (ship == null) return new Vec3(x, y, z);
        Vector3d world = ship.shipToWorld(new Vector3d(x, y, z));
        return new Vec3(world.x, world.y, world.z);
    }

    /** {@link #worldPoint} at the block's centre. */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return worldPoint(level, pos, 0.5, 0.5, 0.5);
    }

    /** The block containing the block's world-space centre. */
    public static BlockPos worldBlock(Level level, BlockPos pos) {
        return BlockPos.containing(worldCenter(level, pos));
    }
}
