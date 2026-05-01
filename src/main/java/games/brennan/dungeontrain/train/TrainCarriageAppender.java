package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
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
 * Per-tick append-only group spawner. For each train (collection of
 * groups sharing a {@link TrainTransformProvider#getTrainId() trainId}),
 * extends the train by spawning new groups at the appropriate world
 * position when a player's needed pIdx window
 * {@code [pIdx − halfBack, pIdx + halfFront]} extends beyond the train's
 * current min/max pIdx.
 *
 * <p>Each spawned group is its own Sable sub-level holding {@code groupSize}
 * adjacent carriages — see
 * {@link TrainAssembler#spawnGroup(ServerLevel, BlockPos, Vector3dc, int, int, CarriageDims, UUID)}.
 * The group's blocks are placed once at assembly and never modified after,
 * so the per-sub-level MassTracker / rotationPoint stays constant.</p>
 *
 * <p>Append-only: never erases. Walking back over a previously-spawned
 * group shows it intact.</p>
 */
public final class TrainCarriageAppender {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;

    /**
     * Per-player last carriage index pushed to the client via
     * {@link CarriageIndexPacket}. Server-thread only.
     */
    private static final Map<UUID, Integer> LAST_SENT_PIDX = new HashMap<>();

    /**
     * Minimum visible gap (in blocks) between a freshly-spawned group and
     * its reference. Sable's collision broad-phase appears to consider
     * very narrow gaps as contact, so we add this bias before rounding to
     * keep the gap at least this large.
     */
    private static final double MIN_GAP_BLOCKS = 0.1;

    /**
     * Hard upper bound on how many GROUPS the appender will spawn in a
     * single server tick. Set to 1 — Sable's
     * {@link dev.ryanhcode.sable.api.sublevel.SubLevelContainer#getAllSubLevels}
     * is asynchronous: a freshly-assembled sub-level can take several
     * server ticks to appear in {@code findAll()}. Bursting more than one
     * spawn per tick races against this lazy load and produces visible
     * overlap (each new spawn happens before Sable has registered the
     * previous one's plot, so the appender's reference math may pick the
     * wrong sub-level as lead/tail).
     *
     * <p>The {@link Trains#knownAnchors} registry remains the
     * source of truth for "what anchors does this train own" — even with
     * the throttle, any duplicate the appender accidentally requests is
     * deduped against the registry. The throttle is the architectural
     * fix; the registry is the safety net.</p>
     *
     * <p>Throughput cost: at groupSize=3, this caps carriages added per
     * tick at 3. The seed group from
     * {@link TrainAssembler#spawnTrain} plus the appender's first ~9
     * ticks fully populate the player's default 10-carriage window in
     * &lt;0.5 seconds — imperceptible.</p>
     */
    private static final int MAX_SPAWNS_PER_TICK = 1;

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
        // Any group of the train can opt the whole train out (debug probes).
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
        int leadAnchorPIdx = leadProvider.getPIdx();
        int groupSize = leadProvider.getGroupSize();
        int length = dims.length();

        // Target carriage count from config — drives halfBack / halfFront so
        // /dt carriages adjusts how aggressively the appender extends.
        int targetCount = DungeonTrainConfig.getNumCarriages();
        int halfBack = (targetCount - 1) / 2;
        int halfFront = targetCount - halfBack - 1;

        List<Integer> nearPlayerPIdxs = new ArrayList<>();
        for (ServerPlayer player : players) {
            // Player is "near" the train if within NEAR_RADIUS of any group's
            // world AABB.
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

            // Player's absolute carriage pIdx via the lead group's frame.
            // The lead group's shipyardOrigin sits at the BACK PAD's
            // lowest-X corner (groupSize > 1) or at the anchor carriage's
            // lowest-X corner (groupSize == 1). The anchor enclosed
            // carriage starts at shipyardOrigin + enclosedStartOffset,
            // where enclosedStartOffset = halfPadLen for groupSize > 1
            // and 0 for groupSize == 1. Subtract this offset before
            // dividing by length so pIdx 0's enclosed carriage maps to
            // (local.x − shipyardOrigin − enclosedStartOffset) ∈ [0, length).
            int halfPadLen = CarriageTemplate.halfPadLen(dims);
            int enclosedStartOffset = (groupSize > 1) ? halfPadLen : 0;
            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            leadShip.worldToShip(local);
            int pIdx = (int) Math.floor(
                (local.x - leadShipyardOrigin.getX() - enclosedStartOffset) / (double) length
            ) + leadAnchorPIdx;

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

        // Use the spawn-time registry (not Sable's visible train) to
        // determine the train's anchor range. Sable's
        // SubLevelContainer.getAllSubLevels() is asynchronous after
        // assembly — bootstrap-spawned sub-levels can take several ticks
        // to appear in the visible train, during which `lead`/`tail`
        // computed from the visible list misrepresent the actual range
        // (often returning just one or two of the four bootstrap groups).
        // Without this guard, the appender requests anchors that already
        // exist and stacks duplicate sub-levels at the same world position.
        Set<Integer> knownAnchors = Trains.knownAnchors(trainId);
        int trainMaxAnchor;
        int trainMinAnchor;
        if (knownAnchors.isEmpty()) {
            // Defensive — should never happen since the visible train has
            // at least one carriage and the spawn path always registers.
            trainMaxAnchor = leadAnchorPIdx;
            trainMinAnchor = tail.provider().getPIdx();
        } else {
            int maxA = Integer.MIN_VALUE;
            int minA = Integer.MAX_VALUE;
            for (int a : knownAnchors) {
                if (a > maxA) maxA = a;
                if (a < minA) minA = a;
            }
            trainMaxAnchor = maxA;
            trainMinAnchor = minA;
        }

        List<Integer> anchorsToSpawn = computeGroupAnchorsToSpawn(
            trainMaxAnchor, trainMinAnchor, halfBack, halfFront, groupSize, nearPlayerPIdxs);
        if (anchorsToSpawn.isEmpty()) return;

        // Belt-and-braces: even though trainMin/Max came from the
        // registry, drop any anchor that's already known. Protects against
        // races in computeGroupAnchorsToSpawn or future logic changes.
        anchorsToSpawn.removeIf(a -> {
            if (!knownAnchors.contains(a)) return false;
            LOGGER.debug("[DungeonTrain] Appender skipping already-spawned anchor={} for trainId={} (in registry)",
                a, trainId);
            return true;
        });
        if (anchorsToSpawn.isEmpty()) return;

        // Diagnostic: every time we're about to spawn, log the train state
        // we based the decision on. Helps catch "appender thinks tail is X
        // but a sub-level at X-groupSize actually exists" — which causes
        // duplicate spawns on top of existing groups.
        if (LOGGER.isDebugEnabled()) {
            StringBuilder pidxs = new StringBuilder();
            for (Trains.Carriage c : train) {
                if (pidxs.length() > 0) pidxs.append(",");
                pidxs.append(c.provider().getPIdx());
            }
            LOGGER.debug("[DungeonTrain] Appender about to spawn anchors={} (trainAnchor=[{},{}] trainPIdxList=[{}] players={})",
                anchorsToSpawn, trainMinAnchor, trainMaxAnchor, pidxs, nearPlayerPIdxs);
        }

        // Safety cap.
        if (anchorsToSpawn.size() > MAX_SPAWNS_PER_TICK) {
            LOGGER.warn("[DungeonTrain] Appender wanted to spawn {} groups this tick (trainAnchor=[{}, {}] groupSize={} players={}); clamping to {}",
                anchorsToSpawn.size(), trainMinAnchor, trainMaxAnchor, groupSize, nearPlayerPIdxs, MAX_SPAWNS_PER_TICK);
            anchorsToSpawn = anchorsToSpawn.subList(0, MAX_SPAWNS_PER_TICK);
        }

        for (int newAnchor : anchorsToSpawn) {
            // Forward groups use the lead as the world-position reference;
            // backward groups use the tail. The reference's shipyardOrigin
            // is its own anchor's world position; we extrapolate to the
            // new group's anchor by adding (newAnchor - refAnchor) * length.
            Trains.Carriage reference = (newAnchor > trainMaxAnchor) ? lead : tail;
            spawnNewGroup(level, reference, newAnchor, groupSize, dims, velocity, trainId);
        }
    }

    /**
     * Pure decision helper — exposed package-private for unit tests.
     * Given the train's current min/max group anchors and a list of
     * near-player pIdx values, return the list of new group anchors to
     * spawn this tick in spawn order: forward anchors ascending, then
     * backward anchors descending. Anchors already inside
     * {@code [trainMinAnchor, trainMaxAnchor]} are never re-emitted.
     *
     * @param trainMaxAnchor current lead anchor pIdx
     * @param trainMinAnchor current tail anchor pIdx
     * @param halfBack       per-player look-behind in carriage units
     * @param halfFront      per-player look-ahead in carriage units
     * @param groupSize      carriages per group (≥ 1)
     * @param playerPIdxs    near-player absolute pIdx values
     */
    static List<Integer> computeGroupAnchorsToSpawn(
        int trainMaxAnchor,
        int trainMinAnchor,
        int halfBack,
        int halfFront,
        int groupSize,
        List<Integer> playerPIdxs
    ) {
        if (playerPIdxs.isEmpty()) return List.of();
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }

        int maxNeededPIdx = Integer.MIN_VALUE;
        int minNeededPIdx = Integer.MAX_VALUE;
        for (int p : playerPIdxs) {
            int h = p + halfFront;
            int l = p - halfBack;
            if (h > maxNeededPIdx) maxNeededPIdx = h;
            if (l < minNeededPIdx) minNeededPIdx = l;
        }
        // Snap needed pIdx range outward to group anchors. Math.floorDiv
        // handles negative pIdx correctly.
        int maxNeededAnchor = Math.floorDiv(maxNeededPIdx, groupSize) * groupSize;
        int minNeededAnchor = Math.floorDiv(minNeededPIdx, groupSize) * groupSize;

        List<Integer> out = new ArrayList<>();
        for (int a = trainMaxAnchor + groupSize; a <= maxNeededAnchor; a += groupSize) {
            out.add(a);
        }
        for (int a = trainMinAnchor - groupSize; a >= minNeededAnchor; a -= groupSize) {
            out.add(a);
        }
        return out;
    }

    /**
     * Place a new {@code groupSize}-carriage sub-level at the world position
     * extrapolated from {@code reference}'s current world origin, with a
     * deliberate gap that guarantees no Sable rigid-body collision.
     *
     * <p><b>Why a gap.</b> Sable's collision response pushes intersecting
     * (or even very-near-touching) bodies apart, manifesting as visible
     * "jumping" of the train. We bias the rounding by
     * {@link #MIN_GAP_BLOCKS} so the visible gap between the new group's
     * lowest-X face and the reference's nearest face is always strictly
     * positive (range {@code [MIN_GAP_BLOCKS, 1 + MIN_GAP_BLOCKS]} blocks).</p>
     */
    private static void spawnNewGroup(
        ServerLevel level,
        Trains.Carriage reference,
        int newAnchor,
        int groupSize,
        CarriageDims dims,
        Vector3dc velocity,
        UUID trainId
    ) {
        BlockPos refShipyardOrigin = reference.provider().getShipyardOrigin();
        int refAnchor = reference.provider().getPIdx();
        int length = dims.length();
        int halfPadLen = CarriageTemplate.halfPadLen(dims);

        // World stride between adjacent sub-levels (the world delta one
        // appender step should add). For groupSize > 1, the stride is
        // groupSize × length + 2 × halfPadLen (two pads per sub-level).
        // For groupSize == 1 there are no pads, stride is just length.
        int subLevelStride = (groupSize > 1) ? (groupSize * length + 2 * halfPadLen) : length;

        // Reference sub-level's current world origin (= back pad's
        // lowest-X corner for groupSize > 1, or anchor carriage's
        // lowest-X corner for groupSize == 1, after velocity drift since
        // spawn).
        Vector3d refWorldOriginVec = new Vector3d(
            refShipyardOrigin.getX(), refShipyardOrigin.getY(), refShipyardOrigin.getZ());
        reference.ship().shipToWorld(refWorldOriginVec);

        // New sub-level's ideal world origin = ref + (anchor delta in
        // groups) × subLevelStride. Anchors are always group-aligned by
        // construction, so the integer division is exact even for
        // negative deltas.
        int anchorDelta = newAnchor - refAnchor;
        int subLevelDelta = anchorDelta / groupSize;
        double idealX = refWorldOriginVec.x + subLevelDelta * (double) subLevelStride;
        double idealY = refWorldOriginVec.y;
        double idealZ = refWorldOriginVec.z;

        boolean forward = newAnchor > refAnchor;
        int placeX = forward
            ? (int) Math.ceil(idealX + MIN_GAP_BLOCKS)
            : (int) Math.floor(idealX - MIN_GAP_BLOCKS);
        int placeY = (int) Math.round(idealY);
        int placeZ = (int) Math.round(idealZ);
        BlockPos newGroupOrigin = new BlockPos(placeX, placeY, placeZ);
        double gap = forward ? (placeX - idealX) : (idealX - placeX);

        ManagedShip newShip = TrainAssembler.spawnGroup(
            level, newGroupOrigin, velocity, newAnchor, groupSize, dims, trainId);

        LOGGER.info("[DungeonTrain] Appender added group anchorPIdx={} groupSize={} trainId={} ship id={} placedAt={} (idealX={}, dir={}, gapBlocks={}, subLevelStride={})",
            newAnchor, groupSize, trainId, newShip.id(), newGroupOrigin,
            String.format("%.4f", idealX),
            forward ? "forward" : "backward",
            String.format("%.4f", gap),
            subLevelStride);
    }

    /**
     * Clear the HUD for any player who had a pIdx last tick but wasn't
     * reached by any train this tick — they walked outside {@link #NEAR_RADIUS}.
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
