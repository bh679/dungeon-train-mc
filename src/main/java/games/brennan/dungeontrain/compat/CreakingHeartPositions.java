package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.editor.TestCopyMobs;
import games.brennan.dungeontrain.ship.PlotWorldPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

    /** {@link PlotWorldPositions#worldPoint} for the heart. */
    public static Vec3 worldPoint(Level level, BlockPos pos, double dx, double dy, double dz) {
        return PlotWorldPositions.worldPoint(level, pos, dx, dy, dz);
    }

    /** {@link PlotWorldPositions#worldCenter} for the heart. */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return PlotWorldPositions.worldCenter(level, pos);
    }

    /** The block containing the heart's world-space centre. */
    public static BlockPos worldBlock(Level level, BlockPos pos) {
        return PlotWorldPositions.worldBlock(level, pos);
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
