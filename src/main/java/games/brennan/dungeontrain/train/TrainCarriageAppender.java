package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
     * Minimum visible gap (in blocks) between the new carriage and the
     * reference. Sable's collision broad-phase appears to consider very
     * narrow gaps as contact, so we add a small bias before rounding to
     * keep the gap at least this large. Combined with the chain
     * constraint ({@link Shipyard#lockAdjacentYZRotation}), this
     * eliminates the impulse-driven jitter on append.
     */
    private static final double MIN_GAP_BLOCKS = 0.1;

    /**
     * Place a new single-carriage sub-level at the world position
     * extrapolated from {@code reference}'s current world origin, with a
     * deliberate gap that guarantees no Sable rigid-body collision.
     *
     * <p><b>Why a gap.</b> Sable's collision response pushes intersecting
     * (or even very-near-touching) bodies apart, which manifests as
     * visible "jumping" of the train. We bias the rounding by
     * {@link #MIN_GAP_BLOCKS} so the gap always falls in
     * {@code [MIN_GAP_BLOCKS, 1 + MIN_GAP_BLOCKS]} blocks — strictly
     * positive, never overlapping, never touching.</p>
     * <ul>
     *   <li>Forward (newPIdx &gt; refPIdx) →
     *       {@code (int) Math.ceil(idealX + MIN_GAP_BLOCKS)} —
     *       new carriage's lowest-X face is strictly &gt; idealX + 0.1.</li>
     *   <li>Backward (newPIdx &lt; refPIdx) →
     *       {@code (int) Math.floor(idealX - MIN_GAP_BLOCKS)} —
     *       new carriage's lowest-X face is strictly &lt; idealX - 0.1.</li>
     * </ul>
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

        Vector3d refWorldOriginVec = new Vector3d(
            refShipyardOrigin.getX(), refShipyardOrigin.getY(), refShipyardOrigin.getZ());
        reference.ship().shipToWorld(refWorldOriginVec);

        double idealX = refWorldOriginVec.x + (newPIdx - refPIdx) * (double) length;
        double idealY = refWorldOriginVec.y;
        double idealZ = refWorldOriginVec.z;

        boolean forward = newPIdx > refPIdx;
        int placeX = forward
            ? (int) Math.ceil(idealX + MIN_GAP_BLOCKS)
            : (int) Math.floor(idealX - MIN_GAP_BLOCKS);
        int placeY = (int) Math.round(idealY);
        int placeZ = (int) Math.round(idealZ);
        BlockPos newCarriageOrigin = new BlockPos(placeX, placeY, placeZ);
        double gap = forward ? (placeX - idealX) : (idealX - placeX);

        ManagedShip newShip = TrainAssembler.spawnCarriage(
            level, newCarriageOrigin, velocity, newPIdx, dims, trainId);

        // Link the new carriage to the reference (lead for forward, tail
        // for backward) so the physics solver pulls them into the same
        // Y/Z/rotation each tick. Free LINEAR_X lets each kinematic driver
        // advance independently.
        Shipyards.of(level).lockAdjacentYZRotation(reference.ship(), newShip);

        LOGGER.info("[DungeonTrain] Appender added carriage pIdx={} trainId={} ship id={} placedAt={} (idealX={}, dir={}, gapBlocks={})",
            newPIdx, trainId, newShip.id(), newCarriageOrigin,
            String.format("%.4f", idealX),
            forward ? "forward" : "backward",
            String.format("%.4f", gap));
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
