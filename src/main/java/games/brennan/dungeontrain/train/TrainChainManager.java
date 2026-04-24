package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.util.List;

/**
 * Spawns a successor train ahead of each active train once the player's
 * local pIdx approaches VS 2.4.11's 128-chunk shipyard-allocation wall.
 *
 * <h2>Why</h2>
 * Each VS ship gets a fixed ~2048-block (128-chunk) shipyard allocation
 * at creation time. The train's rolling-window + pIdx math demands
 * unbounded forward motion, so a single ship eventually paints carriages
 * past that wall and those carriages can't be collided with / rendered.
 * The only workable fix in VS 2.4.11 is a second ship with its own fresh
 * allocation that the player walks onto before ship A runs out.
 *
 * <h2>How</h2>
 * Per server tick, for each active train whose successor hasn't been
 * spawned yet: find the nearest player's local pIdx on that train; if
 * it has passed {@link #SUCCESSOR_TRIGGER_PIDX}, compute the world
 * position of the next carriage slot, spawn a successor train rooted
 * there via {@link TrainAssembler#spawnSuccessor}, and cap the
 * predecessor's forward extent so it stops painting past the seam.
 *
 * <h2>Seam geometry</h2>
 * The successor's carriage 0 lands at the world slot immediately after
 * the predecessor's {@code forwardLimit}-th carriage. Because both
 * trains carry the same velocity in the kinematic provider, the seam
 * width (one carriage) stays constant over time — the chain looks like
 * one continuous row from the player's POV.
 */
public final class TrainChainManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Local pIdx on the current train at which to trigger a successor spawn. 100 leaves ~128 carriages of safety before the 228 wall. */
    private static final int SUCCESSOR_TRIGGER_PIDX = 100;

    private TrainChainManager() {}

    public static void maybeSpawnSuccessors(
        ServerLevel level,
        List<LoadedServerShip> trains,
        List<ServerPlayer> players
    ) {
        if (trains.isEmpty() || players.isEmpty()) return;
        for (LoadedServerShip ship : trains) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            if (provider.isSuccessorSpawned()) continue;
            trySpawnSuccessor(level, ship, provider, players);
        }
    }

    private static void trySpawnSuccessor(
        ServerLevel level,
        LoadedServerShip predecessorShip,
        TrainTransformProvider predecessor,
        List<ServerPlayer> players
    ) {
        BlockPos shipyardOrigin = predecessor.getShipyardOrigin();
        CarriageDims dims = predecessor.dims();
        int length = dims.length();

        // Find the player with the largest local pIdx on this train —
        // they're the one closest to the wall.
        int maxPIdx = Integer.MIN_VALUE;
        for (ServerPlayer player : players) {
            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            predecessorShip.getTransform().getWorldToShip().transformPosition(local);
            int p = (int) Math.floor((local.x - shipyardOrigin.getX()) / (double) length);
            if (p > maxPIdx) maxPIdx = p;
        }
        if (maxPIdx < SUCCESSOR_TRIGGER_PIDX) return;

        // Cap the predecessor at trigger + halfFront so its rolling window
        // only paints up to (and including) the seam carriage. The next
        // carriage slot is where the successor's carriage 0 lives.
        int count = predecessor.getCount();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        int predecessorForwardLimit = SUCCESSOR_TRIGGER_PIDX + halfFront;
        int successorSeamPIdx = predecessorForwardLimit + 1;

        // Compute the successor's WORLD origin: where the predecessor's
        // carriage at `successorSeamPIdx` currently renders. This is the
        // voxel-shipyard position transformed by the predecessor's
        // current ship-to-world transform.
        Vector3d seamShipyard = new Vector3d(
            shipyardOrigin.getX() + successorSeamPIdx * (double) length,
            shipyardOrigin.getY(),
            shipyardOrigin.getZ());
        Vector3d seamWorld = new Vector3d(seamShipyard);
        predecessorShip.getTransform().getShipToWorld().transformPosition(seamWorld);

        BlockPos successorOrigin = new BlockPos(
            (int) Math.round(seamWorld.x),
            (int) Math.round(seamWorld.y),
            (int) Math.round(seamWorld.z));

        // Pass spawnerWorldPos offset so initialPIdx = halfBack → the
        // initial 11-carriage window lands at [0, count-1], NOT [-5, 5].
        // This prevents the successor from painting backwards onto the
        // predecessor's still-occupied trailing carriages during spawn.
        Vector3d spawnerOffset = new Vector3d(
            successorOrigin.getX() + halfBack * (double) length,
            successorOrigin.getY(),
            successorOrigin.getZ());

        Vector3dc velocity = predecessor.getTargetVelocity();
        int successorGlobalBase = predecessor.getGlobalPIdxBase() + successorSeamPIdx;

        LOGGER.info("[DungeonTrain] Spawning successor train: predecessorShipId={} predecessorMaxPIdx={} "
                + "predecessorForwardLimit={} successorOrigin={} successorGlobalBase={}",
            predecessorShip.getId(), maxPIdx, predecessorForwardLimit, successorOrigin, successorGlobalBase);

        var successorShip = TrainAssembler.spawnSuccessor(
            level, successorOrigin, velocity, count, spawnerOffset, dims, successorGlobalBase);

        if (successorShip.getTransformProvider() instanceof TrainTransformProvider successorProvider) {
            // Successor must not paint backwards into the predecessor's
            // forward extent — cap its window at pIdx=0 on the low side.
            successorProvider.setBackwardLimit(0);
        }

        // Cap predecessor + mark successorSpawned so we don't repeat.
        predecessor.setForwardLimit(predecessorForwardLimit);
        predecessor.setSuccessorSpawned(true);

        LOGGER.info("[DungeonTrain] Chain seam created — predecessor={} capped at pIdx={}, successor={} starts at pIdx=0, globalBase={}",
            predecessorShip.getId(), predecessorForwardLimit,
            successorShip.getId(), successorGlobalBase);
    }
}
