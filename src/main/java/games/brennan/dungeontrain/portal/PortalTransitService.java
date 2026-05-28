package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-dimension carriage transit. Given a {@link TransitJob} describing a
 * carriage that just crossed a portal plane, this service spawns a fresh
 * carriage in the target dim with the same train identity (trainId, pIdx,
 * groupSize, dims, velocity), migrates any player passengers via vanilla
 * {@link DimensionTransition}, and deletes the source carriage.
 *
 * <h2>Why "spawn fresh" rather than "serialize + fullyLoad"</h2>
 * <p>An earlier v1 prototype used {@code SubLevelSerializer.toData} +
 * {@code fullyLoad} to clone the source sub-level into the target dim,
 * which would have preserved the carriage's interior contents (chests,
 * decorations, etc.). That approach crashed Sable's
 * {@code SubLevelHoldingChunkMap.processChanges} 3 ticks after each
 * transit — Sable's per-sub-level lifecycle wasn't built for a sub-level
 * to migrate between {@link ServerLevel}s while keeping its UUID, and the
 * subsequent holding-chunk-map reconciliation NPE'd on a null
 * {@code GlobalSavedSubLevelPointer}.</p>
 *
 * <p>The current approach side-steps the lifecycle issue by going through
 * Sable's normal spawn path: {@link TrainAssembler#spawnGroup} on the
 * target side creates a brand-new sub-level with a fresh UUID, fully
 * registered with Sable's containers from the start. The trade-off is
 * that carriage interior contents are regenerated rather than transferred
 * — the new carriage looks freshly minted, with deterministic-but-fresh
 * variants chosen by {@code TrainAssembler.spawnGroup}'s per-pIdx variant
 * selector. Train identity (trainId, pIdx, velocity) IS preserved, so the
 * train remains the same logical train across the portal.</p>
 *
 * <h2>Failure handling</h2>
 * <p>If {@code spawnGroup} throws or returns null, the source carriage is
 * NOT deleted — the train keeps moving through the portal as a (failed)
 * source-side passthrough. The detector will retry on the next tick (the
 * lastX state for the carriage is unchanged on failure).</p>
 *
 * <h2>v1 known limitations</h2>
 * <ul>
 *   <li>Carriage interior contents (chests, decorations) NOT preserved — see above.</li>
 *   <li>Player passengers detected via world-AABB intersection — exotic
 *       seating arrangements may miss. {@link PlayerPortalCrossListener}
 *       handles the on-foot fallback for those.</li>
 *   <li>Non-player entities riding the carriage are not migrated. Lost in
 *       source dim on transit.</li>
 *   <li>Partner uses same world coords as source (no Nether 1:8 scaling).</li>
 * </ul>
 */
public final class PortalTransitService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One unit of work: a carriage that should transit from
     * {@code (sourceLevel, sourcePortalPos)} to {@code (targetDim, targetPortalPos)}.
     * Created by {@link CarriageTransitDetector}.
     */
    public record TransitJob(
        ServerLevel sourceLevel,
        Trains.Carriage carriage,
        BlockPos sourcePortalPos,
        ResourceKey<Level> targetDim,
        BlockPos targetPortalPos
    ) {}

    private PortalTransitService() {}

    /**
     * Execute a single transit job. Returns {@code true} on success — the
     * source carriage is gone and a fresh equivalent now lives in the
     * target dim. Returns {@code false} if execution was deferred (chunks
     * not ready, target dim unresolvable, spawn threw) — caller may retry
     * on a later tick.
     */
    public static boolean transit(TransitJob job) {
        ServerLevel sourceLevel = job.sourceLevel;
        ServerLevel targetLevel = sourceLevel.getServer().getLevel(job.targetDim);
        if (targetLevel == null) {
            LOGGER.warn("[Portal] Transit aborted — target dim {} unresolved", job.targetDim.location());
            return false;
        }

        if (!ChunkPrewarmer.isReady(targetLevel, new ChunkPos(job.targetPortalPos), 3)) {
            return false;
        }

        if (!(job.carriage.ship() instanceof SableManagedShip sableSource)) {
            LOGGER.warn("[Portal] Transit aborted — non-Sable ship {}", job.carriage.ship().id());
            return false;
        }

        TrainTransformProvider sourceProvider = job.carriage.provider();

        // 1. Snapshot player passengers in the source carriage's world AABB.
        AABBdc worldAabb = sableSource.worldAABB();
        AABB box = new AABB(
            worldAabb.minX(), worldAabb.minY(), worldAabb.minZ(),
            worldAabb.maxX(), worldAabb.maxY(), worldAabb.maxZ());
        List<ServerPlayer> passengers =
            new ArrayList<>(sourceLevel.getPlayers(p -> box.contains(p.position())));

        // 2. Compute the target world spawn origin from the source carriage's
        //    shipyardOrigin (model-space back corner) → world-space, via the
        //    source ship's current pose. Same world coords on both sides
        //    (v1 partner positioning) so this places the new carriage at
        //    the same world location the source was about to leave.
        BlockPos sourceShipyardOrigin = sourceProvider.getShipyardOrigin();
        Vector3d worldCorner = new Vector3d(
            sourceShipyardOrigin.getX(),
            sourceShipyardOrigin.getY(),
            sourceShipyardOrigin.getZ());
        sableSource.shipToWorld(worldCorner);
        BlockPos targetOrigin = new BlockPos(
            (int) Math.round(worldCorner.x),
            (int) Math.round(worldCorner.y),
            (int) Math.round(worldCorner.z));

        // 3. Fresh spawn in the target dim via Sable's normal assembly path.
        ManagedShip newShip;
        try {
            newShip = TrainAssembler.spawnGroup(
                targetLevel, targetOrigin,
                sourceProvider.getTargetVelocity(),
                sourceProvider.getPIdx(),
                sourceProvider.getGroupSize(),
                sourceProvider.dims(),
                sourceProvider.getTrainId());
        } catch (Throwable t) {
            LOGGER.error("[Portal] spawnGroup in target dim threw — keeping source carriage in place", t);
            return false;
        }
        if (newShip == null) {
            LOGGER.warn("[Portal] spawnGroup returned null — keeping source carriage in place");
            return false;
        }

        // 4. Migrate players. Vanilla changeDimension causes the inevitable
        //    client "Loading terrain" flash; v1 accepts this.
        for (ServerPlayer player : passengers) {
            Vec3 sourcePos = player.position();
            DimensionTransition transition = new DimensionTransition(
                targetLevel, sourcePos, player.getDeltaMovement(),
                player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING);
            try {
                player.changeDimension(transition);
            } catch (Throwable t) {
                LOGGER.error("[Portal] Player dim change threw for {} — player may be in inconsistent state",
                    player.getName().getString(), t);
            }
        }

        // 5. Delete the source carriage. Routes through Sable's standard
        //    markRemoved flow. Critically NOT a fullyLoad call, so no
        //    holding-chunk-map cross-dim NPE.
        Shipyards.of(sourceLevel).delete(sableSource);

        LOGGER.info("[Portal] Transited carriage trainId={} pIdx={} from {} to {} ({} player(s))",
            sourceProvider.getTrainId(), sourceProvider.getPIdx(),
            sourceLevel.dimension().location(), job.targetDim.location(),
            passengers.size());

        return true;
    }
}
