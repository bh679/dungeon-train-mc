package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.editor.TestCopyMobs;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Where a VanillaBackport creaking heart really is, and whether it may wake its Creaking.
 *
 * <p>A heart stamped into a carriage lives at plot coordinates inside the Sable sub-level, while its
 * Creaking and every player are in world space. VanillaBackport measures everything — the 32-block
 * player check, the 34-block leash, the Creaking's home-bound pathing — from the heart's block
 * position, so on a train it never sees a player and would tear its Creaking down the tick it spawned.
 * The {@code mixin/vanillabackport} heart mixins route those reads through here instead.</p>
 *
 * <p>Test the Carriage stamps into the Train Editor world, which keeps {@code doMobSpawning} off
 * ({@code EditorQuietRules}); a heart inside a running test copy is allowed through anyway, so the
 * author sees the piece behave as it will on the train. Plain editor plots stay quiet.</p>
 */
public final class CreakingHeartPositions {

    private CreakingHeartPositions() {}

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

    /** The block containing the heart's world-space centre. */
    public static BlockPos worldBlock(Level level, BlockPos pos) {
        return BlockPos.containing(worldCenter(level, pos));
    }

    /** Whether the heart's {@code doMobSpawning} gate passes at {@code pos}. */
    public static boolean spawningAllowed(Level level, BlockPos pos, boolean rule) {
        if (rule) return true;
        return level instanceof ServerLevel server && allowsSpawning(false, TestCopyMobs.isInTestCopy(server, pos));
    }

    /** The gate itself: the game rule, or a running test copy. */
    public static boolean allowsSpawning(boolean rule, boolean inTestCopy) {
        return rule || inTestCopy;
    }
}
