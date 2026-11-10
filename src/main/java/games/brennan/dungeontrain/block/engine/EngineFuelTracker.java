package games.brennan.dungeontrain.block.engine;

import games.brennan.dungeontrain.train.SharedBuild;
import games.brennan.dungeontrain.train.SharedBuildBlockEntityEdits;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps a drifting build's live fuel in step with the coal in its Engine Block. The count travels to
 * the relay with the build's next save, delta, heartbeat or return, and the engine's cell is queued so
 * the blob catches up too.
 */
public final class EngineFuelTracker {

    private EngineFuelTracker() {}

    /** The engine at {@code pos} now holds {@code coal}. Server thread only. */
    static void onEngineChanged(ServerLevel level, BlockPos pos, int coal) {
        SharedBuild build = buildAt(level, pos);
        if (build == null || build.isCulled() || build.fuel() == coal) return;
        build.setFuel(coal);
        SharedBuildBlockEntityEdits.onBlockEntityEdited(level, pos);
    }

    /**
     * The engine at {@code pos} is gone. A build with no engine has no fuel, so it retires when it
     * leaves this world, unless someone sets an engine back down. A culled build is being torn down,
     * not broken, and keeps the fuel it had.
     */
    static void onEngineRemoved(ServerLevel level, BlockPos pos) {
        SharedBuild build = buildAt(level, pos);
        if (build == null || build.isCulled()) return;
        build.setFuel(0);
    }

    /** The drifting carriage or dimensional room holding {@code pos}, or null. */
    public static SharedBuild buildAt(ServerLevel level, BlockPos pos) {
        SharedBuild carriage = SharedCarriageLookup.byBlockPos(level, pos);
        if (carriage != null) return carriage;
        return SharedRoomRegistry.isEmpty() ? null : SharedRoomRegistry.byWorldPos(level, pos);
    }
}
