package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick append-only carriage spawner. For each train (group of
 * single-carriage sub-levels sharing a {@link TrainTransformProvider#getTrainId() trainId}),
 * extends the train's pIdx range by spawning new sub-levels at the
 * appropriate world position when a player's needed window
 * {@code [pIdx − halfBack, pIdx + halfFront]} extends beyond the train's
 * current min/max pIdx.
 *
 * <p>Each new carriage is its own Sable sub-level — see
 * {@link TrainAssembler#spawnCarriage(ServerLevel, BlockPos, Vector3dc, int, CarriageDims, UUID)}.
 * This eliminates the COM-drift jitter the previous "one big sub-level"
 * model suffered from on every block add.</p>
 *
 * <p>Append-only: never erases. Walking back over a previously-spawned
 * carriage shows it intact (its blocks were stamped once at spawn and never
 * modified after).</p>
 */
public final class TrainCarriageAppender {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;

    /**
     * Per-player last carriage index pushed to the client via
     * {@link CarriageIndexPacket}. Server-thread only. Entry absence means
     * we haven't sent a value yet (or have cleared it). Used to skip
     * duplicate packets and to detect dropouts in {@link #clearDropouts}.
     */
    private static final Map<UUID, Integer> LAST_SENT_PIDX = new HashMap<>();

    private TrainCarriageAppender() {}

    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        Set<UUID> seenThisTick = new HashSet<>();
        Map<UUID, List<Trains.Carriage>> trainsById = Trains.byTrainId(level);
        for (List<Trains.Carriage> train : trainsById.values()) {
            updateTrain(level, train, players, seenThisTick);
        }
        clearDropouts(level, seenThisTick);
    }

    private static void updateTrain(
        ServerLevel level,
        List<Trains.Carriage> train,
        List<ServerPlayer> players,
        Set<UUID> seenThisTick
    ) {
        if (train.isEmpty()) return;
        // Any carriage of the train can opt the whole train out (debug probes).
        for (Trains.Carriage c : train) {
            if (c.provider().isAppenderDisabled()) return;
        }

        Trains.Carriage lead = Trains.lead(train);
        Trains.Carriage tail = Trains.tail(train);
        TrainTransformProvider leadProvider = lead.provider();
        ManagedShip leadShip = lead.ship();
        UUID trainId = leadProvider.getTrainId();
        CarriageDims dims = leadProvider.dims();
        Vector3dc velocity = leadProvider.getTargetVelocity();
        BlockPos leadShipyardOrigin = leadProvider.getShipyardOrigin();
        int leadPIdx = leadProvider.getPIdx();
        int length = dims.length();

        // Target carriage count from config — drives halfBack / halfFront so
        // /dt carriages adjusts how aggressively the appender extends.
        int targetCount = DungeonTrainConfig.getNumCarriages();
        int halfBack = (targetCount - 1) / 2;
        int halfFront = targetCount - halfBack - 1;

        List<Integer> nearPlayerPIdxs = new ArrayList<>();
        for (ServerPlayer player : players) {
            // Player is "near" the train if within NEAR_RADIUS of any of the
            // train's carriage AABBs. Each carriage has its own world AABB
            // since each is a separate sub-level.
            boolean near = false;
            for (Trains.Carriage c : train) {
                AABBdc aabb = c.ship().worldAABB();
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                double cdx = Math.max(0, Math.max(aabb.minX() - px, px - aabb.maxX()));
                double cdy = Math.max(0, Math.max(aabb.minY() - py, py - aabb.maxY()));
                double cdz = Math.max(0, Math.max(aabb.minZ() - pz, pz - aabb.maxZ()));
                if (cdx * cdx + cdy * cdy + cdz * cdz <= NEAR_RADIUS_SQ) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;

            // Compute pIdx using the lead carriage as a reference frame.
            // worldToShip(playerWorld) maps the player into lead's shipyard
            // coordinates; offset from lead.shipyardOrigin in length-units
            // gives the pIdx delta from lead, plus lead.pIdx for the
            // absolute carriage index. This works regardless of which
            // carriage the player is physically standing on, because all
            // carriages of a train advance in lockstep (same velocity, same
            // tick cadence).
            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            leadShip.worldToShip(local);
            int pIdx = (int) Math.floor((local.x - leadShipyardOrigin.getX()) / (double) length) + leadPIdx;

            UUID uuid = player.getUUID();
            seenThisTick.add(uuid);
            Integer lastSent = LAST_SENT_PIDX.get(uuid);
            if (lastSent == null || lastSent != pIdx) {
                DungeonTrainNet.sendTo(player, new CarriageIndexPacket(true, pIdx));
                LAST_SENT_PIDX.put(uuid, pIdx);
            }

            nearPlayerPIdxs.add(pIdx);
        }
        if (nearPlayerPIdxs.isEmpty()) return;

        int trainMaxPIdx = leadPIdx;
        int trainMinPIdx = tail.provider().getPIdx();
        List<Integer> toSpawn = computeIndicesToSpawn(
            trainMaxPIdx, trainMinPIdx, halfBack, halfFront, nearPlayerPIdxs);
        if (toSpawn.isEmpty()) return;

        for (int pIdx : toSpawn) {
            // Forward indices use the lead as the world-position reference;
            // backward indices use the tail. Both references compute the
            // new carriage's world origin by adding (newPIdx - refPIdx) *
            // length to the reference's current world origin.
            Trains.Carriage reference = (pIdx > trainMaxPIdx) ? lead : tail;
            spawnNewCarriage(level, reference, pIdx, dims, velocity, trainId);
        }
    }

    /**
     * Pure decision helper — exposed package-private for unit tests.
     * Given the train's current min/max pIdx and a list of near-player pIdx
     * values, return the indices to spawn this tick in spawn order:
     * forward indices ascending, then backward indices descending. Indices
     * already inside {@code [trainMinPIdx, trainMaxPIdx]} are never
     * re-emitted (append-only monotonicity).
     */
    static List<Integer> computeIndicesToSpawn(
        int trainMaxPIdx,
        int trainMinPIdx,
        int halfBack,
        int halfFront,
        List<Integer> playerPIdxs
    ) {
        if (playerPIdxs.isEmpty()) return List.of();
        int maxNeededHigh = Integer.MIN_VALUE;
        int minNeededLow = Integer.MAX_VALUE;
        for (int p : playerPIdxs) {
            int h = p + halfFront;
            int l = p - halfBack;
            if (h > maxNeededHigh) maxNeededHigh = h;
            if (l < minNeededLow) minNeededLow = l;
        }
        List<Integer> out = new ArrayList<>();
        for (int i = trainMaxPIdx + 1; i <= maxNeededHigh; i++) out.add(i);
        for (int i = trainMinPIdx - 1; i >= minNeededLow; i--) out.add(i);
        return out;
    }

    /**
     * Place a new single-carriage sub-level at the world position
     * extrapolated from {@code reference}'s current world origin.
     *
     * <p>World coords are rounded to the nearest integer for the
     * {@link BlockPos} placement (Sable's setBlock takes an integer
     * BlockPos), then the new sub-level is immediately teleported by the
     * fractional rounding error so its rendered blocks land at the exact
     * ideal fractional position — aligning with the existing train's
     * velocity drift. Without this teleport step, each new carriage would
     * have up to 0.5 blocks of visible misalignment with its neighbours
     * (gap on one side, overlap on the other), which causes Sable's
     * rigid-body collision to push the train around.</p>
     *
     * <p>The teleport happens via {@link ManagedShip#applyTickOutput} so
     * the rotationPoint pin in
     * {@link games.brennan.dungeontrain.ship.sable.SableManagedShip}
     * applies on the same call. The next
     * {@link TrainTransformProvider#nextTransform} captures the
     * teleported pose as the carriage's
     * {@link TrainTransformProvider#getCanonicalPos() canonical position},
     * and from there it advances in lockstep with the rest of the train.</p>
     */
    private static void spawnNewCarriage(
        ServerLevel level,
        Trains.Carriage reference,
        int newPIdx,
        CarriageDims dims,
        Vector3dc velocity,
        UUID trainId
    ) {
        BlockPos refShipyardOrigin = reference.provider().getShipyardOrigin();
        int refPIdx = reference.provider().getPIdx();
        int length = dims.length();

        // Reference carriage's current world origin = shipToWorld of its
        // shipyard origin. Includes velocity drift since spawn.
        Vector3d refWorldOriginVec = new Vector3d(
            refShipyardOrigin.getX(), refShipyardOrigin.getY(), refShipyardOrigin.getZ());
        reference.ship().shipToWorld(refWorldOriginVec);

        // New carriage's ideal world origin: ref + (Δ pIdx) * length in +X
        // for a 1D train. Fractional because the existing train has drifted
        // by velocity*dt for some number of ticks.
        double idealX = refWorldOriginVec.x + (newPIdx - refPIdx) * (double) length;
        double idealY = refWorldOriginVec.y;
        double idealZ = refWorldOriginVec.z;

        // Round to integer for block placement. The error is in [-0.5, 0.5].
        BlockPos newCarriageOrigin = new BlockPos(
            (int) Math.round(idealX),
            (int) Math.round(idealY),
            (int) Math.round(idealZ));

        ManagedShip newShip = TrainAssembler.spawnCarriage(
            level, newCarriageOrigin, velocity, newPIdx, dims, trainId);

        // Sub-block alignment: shift the new sub-level's pose by the fractional
        // rounding error so its rendered blocks land at the exact ideal X.
        // After spawnCarriage, pose.position is at the integer-anchored value;
        // the carriage's blocks render at integer world coords. Adding
        // (idealX − round(idealX)) to pose.position translates every voxel by
        // the same amount, putting the lowest-X block at world idealX exactly.
        double fracX = idealX - newCarriageOrigin.getX();
        double fracY = idealY - newCarriageOrigin.getY();
        double fracZ = idealZ - newCarriageOrigin.getZ();
        Vector3dc currentPos = newShip.currentWorldPosition();
        Vector3dc currentPivot = newShip.currentPositionInModel();
        Vector3d alignedPos = new Vector3d(
            currentPos.x() + fracX,
            currentPos.y() + fracY,
            currentPos.z() + fracZ);
        Quaterniondc identityRot = new Quaterniond();
        KinematicDriver.TickOutput alignedOutput = new KinematicDriver.TickOutput(
            alignedPos, identityRot, currentPivot, velocity, new Vector3d());
        newShip.applyTickOutput(alignedOutput);

        LOGGER.info("[DungeonTrain] Appender added carriage pIdx={} trainId={} ship id={} placedAt={} (idealX={}, fracX={}, alignedX={})",
            newPIdx, trainId, newShip.id(), newCarriageOrigin,
            String.format("%.4f", idealX),
            String.format("%.4f", fracX),
            String.format("%.4f", alignedPos.x));
    }

    /**
     * Clear the HUD for any player who had a pIdx last tick but wasn't
     * reached by any train this tick — they walked outside
     * {@link #NEAR_RADIUS}. Also drops offline UUIDs so the tracking map
     * doesn't grow without bound.
     */
    private static void clearDropouts(ServerLevel level, Set<UUID> seenThisTick) {
        if (LAST_SENT_PIDX.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = LAST_SENT_PIDX.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID uuid = entry.getKey();
            if (seenThisTick.contains(uuid)) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                DungeonTrainNet.sendTo(player, CarriageIndexPacket.absent());
            }
            it.remove();
        }
    }
}
