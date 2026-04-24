package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick rolling-window driver: keeps each {@link TrainTransformProvider}-marked
 * ship's carriage blocks aligned with the union of per-player windows.
 *
 * For each player within {@link #NEAR_RADIUS} blocks of a train:
 *   - Project world position to ship-local via {@code ship.transform.worldToShip}.
 *   - Compute carriage index {@code pIdx = floor((localX - shipyardOriginX) / LENGTH)}.
 *   - Contribute carriage indices {@code [pIdx - halfBack, pIdx + halfFront]}.
 *
 * Active set = union across players. Diff against the provider's recorded indices:
 *   - Missing → place a carriage at that shipyard offset.
 *   - Stale → erase it, unless a player is currently inside (safety).
 */
public final class TrainWindowManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    // See {@link TrainTransformProvider#JITTER_LOGGER} — Stage 1 diagnostic.
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;
    // Ticks to wait before accepting a reversed pIdx shift. ~1/3 second at
    // 20 Hz server tick: long enough to absorb single-tick flaps caused by
    // block-change/transform sync races, short enough that real direction
    // changes feel responsive.
    private static final int REVERSAL_COOLDOWN_TICKS = 6;

    /**
     * Per-player last carriage index we pushed to the client via
     * {@link CarriageIndexPacket}. Server-thread only (entire tick chain is
     * on the server thread), so no synchronisation needed. Entry absence
     * means "we haven't sent an index to that player yet, or we've cleared
     * it". Used to skip duplicate packets (only send on change) and to
     * detect players who drifted out of range so we can clear their HUD.
     */
    private static final Map<UUID, Integer> LAST_SENT_PIDX = new HashMap<>();

    private TrainWindowManager() {}

    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        Set<UUID> seenThisTick = new HashSet<>();
        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            updateWindow(level, ship, provider, players, seenThisTick);
        }
        clearDropouts(level, seenThisTick);
    }

    /**
     * Send a HUD-clear packet to any player who had a pIdx last tick but
     * wasn't reached by any train's window this tick (drifted outside
     * {@link #NEAR_RADIUS}). Also drops offline UUIDs so the tracking map
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

    private static void updateWindow(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        List<ServerPlayer> players,
        Set<UUID> seenThisTick
    ) {
        // First time we see this ship, snapshot its inertia so VS's mass-
        // recalc-on-block-change can be reverted after each window mutation.
        // See ShipInertiaLocker for the why; this is the "Path A" fix for
        // the flatbed-adjacent train hop.
        if (provider.getLockedInertia() == null) {
            provider.setLockedInertia(ShipInertiaLocker.capture(ship));
        }

        int count = provider.getCount();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        int originX = shipyardOrigin.getX();
        int originY = shipyardOrigin.getY();
        int originZ = shipyardOrigin.getZ();
        // Per-train dims captured at spawn time. Reading from the provider
        // (not DungeonTrainWorldData) avoids a SavedData lookup on every
        // tick for a value that cannot change for this train's lifetime.
        CarriageDims dims = provider.dims();

        // Generation mode + groupSize are runtime-editable via the settings
        // screen, so they must be read fresh each tick — new placements then
        // pick up the new mode without a respawn. The per-world seed portion
        // is sourced from SavedData (cached by DataStorage, cheap).
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        Vector3dc shipWorldPos = ship.getTransform().getPosition();
        double shipWx = shipWorldPos.x();
        double shipWy = shipWorldPos.y();
        double shipWz = shipWorldPos.z();

        // Jitter probe snapshot BEFORE any block mutation — used after the
        // mutation to confirm whether VS re-centred the pivot synchronously
        // with our setBlock calls (H1). See `.claude/plans/swirling-fluttering-squid.md`.
        Vector3dc pivotPreMutate = new Vector3d(ship.getTransform().getPositionInModel());
        Vector3dc shipWorldPosPreMutate = new Vector3d(shipWorldPos);
        org.joml.primitives.AABBdc aabbPreMutate = new org.joml.primitives.AABBd(ship.getWorldAABB());

        Set<Integer> current = provider.getActiveIndices();
        Set<Integer> desired = new HashSet<>();
        Set<Integer> occupied = new HashSet<>();

        for (ServerPlayer player : players) {
            // Distance from the player to the ship's world AABB — zero if
            // inside, otherwise the distance to the nearest face. Using the
            // AABB instead of ship.getTransform().getPosition() matters for
            // long trains: the ship's origin point sits at one end of a
            // 180-block-long carriage set, so a player who's walked to the
            // far end can be 90+ blocks from the origin while still standing
            // on the train. With the COM-pin fix (ShipInertiaLocker) the
            // origin no longer drifts along with mutations, so the origin-
            // distance check was filtering out players before they reached
            // a carriage boundary — which stopped the rolling window from
            // adding new carriages.
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double cdx = Math.max(0, Math.max(aabbPreMutate.minX() - px, px - aabbPreMutate.maxX()));
            double cdy = Math.max(0, Math.max(aabbPreMutate.minY() - py, py - aabbPreMutate.maxY()));
            double cdz = Math.max(0, Math.max(aabbPreMutate.minZ() - pz, pz - aabbPreMutate.maxZ()));
            if (cdx * cdx + cdy * cdy + cdz * cdz > NEAR_RADIUS_SQ) continue;

            Vector3d local = new Vector3d(px, py, pz);
            ship.getTransform().getWorldToShip().transformPosition(local);
            int pIdx = (int) Math.floor((local.x - originX) / (double) dims.length());

            // Push raw pIdx to the client HUD on change. Raw (not committed)
            // is intentional — the HUD is a real-time debug read; debouncing
            // would make the value lag behind the player's actual motion.
            UUID uuid = player.getUUID();
            seenThisTick.add(uuid);
            Integer lastSent = LAST_SENT_PIDX.get(uuid);
            if (lastSent == null || lastSent != pIdx) {
                DungeonTrainNet.sendTo(player, new CarriageIndexPacket(true, pIdx));
                LAST_SENT_PIDX.put(uuid, pIdx);
            }

            occupied.add(pIdx);

            // Reversal debounce: if the player's pIdx flipped direction in the
            // last few ticks, the previous intended shift is still propagating
            // to clients. Honour the reversal only after REVERSAL_COOLDOWN
            // ticks have passed since the last committed shift. Within the
            // cooldown, treat pIdx as unchanged so the window doesn't flap.
            int committed = provider.getCommittedPIdx();
            int lastDir = provider.getLastShiftDirection();
            long now = level.getGameTime();
            long ticksSince = now - provider.getLastShiftTick();
            int effectivePIdx = pIdx;
            if (pIdx != committed) {
                int dir = Integer.signum(pIdx - committed);
                if (lastDir != 0 && dir == -lastDir && ticksSince < REVERSAL_COOLDOWN_TICKS) {
                    // Reversal too soon — suppress. Player still rides on committed.
                    effectivePIdx = committed;
                } else {
                    provider.setCommittedPIdx(pIdx);
                    provider.setLastShiftDirection(dir);
                    provider.setLastShiftTick(now);
                }
            }

            for (int i = effectivePIdx - halfBack; i <= effectivePIdx + halfFront; i++) {
                desired.add(i);
            }
        }

        if (desired.isEmpty()) return;

        boolean mutated = false;
        for (Integer i : desired) {
            if (current.contains(i)) continue;
            BlockPos carriageOrigin = new BlockPos(originX + i * dims.length(), originY, originZ);
            CarriageVariant variant = CarriageTemplate.variantForIndex(i, genCfg);
            Set<BlockPos> placed = CarriageTemplate.placeAt(level, carriageOrigin, variant, dims, genCfg, i);
            current.add(i);
            mutated = true;
            if (placed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Window placed empty carriage idx={} variant={} at {}",
                    i, variant.id(), carriageOrigin);
            } else {
                LOGGER.info("[DungeonTrain] Added carriage idx={} variant={} at shipyard {} blocks={}",
                    i, variant.id(), carriageOrigin, placed.size());
            }
        }

        Set<Integer> toErase = new HashSet<>();
        for (Integer i : current) {
            if (desired.contains(i)) continue;
            if (occupied.contains(i)) continue;
            toErase.add(i);
        }
        for (Integer i : toErase) {
            BlockPos carriageOrigin = new BlockPos(originX + i * dims.length(), originY, originZ);
            CarriageTemplate.eraseAt(level, carriageOrigin, dims);
            current.remove(i);
            mutated = true;
            LOGGER.debug("[DungeonTrain] Erased carriage idx={} at shipyard {}", i, carriageOrigin);
        }

        // After voxel mutations, re-push the compensated transform in the
        // same server tick. This tightens the client-visible window between
        // "new voxels applied" and "new transform applied" — otherwise the
        // client can render the new blocks against the stale transform for
        // one frame, producing a brief backwards teleport (most visible when
        // a flatbed carriage enters/leaves the window because the flatbed's
        // small mass swings the COM by more blocks per shift).
        if (mutated) {
            // Record the mutation timestamp BEFORE calling restore so the
            // physics-thread probe can distinguish "pivot moved due to
            // this mutation" (msSinceMutation small) from "pivot moved
            // independently, long after the last mutation" (msSinceMutation
            // large — would indicate VS recomputes COM spontaneously).
            provider.setLastMutationTick(level.getGameTime());
            provider.setLastMutationNanos(System.nanoTime());

            // Path A fix: VS's ShipInertiaDataImpl.onSetBlock has already
            // shifted _centerOfMassInShip and _mass for every block we just
            // placed/erased. Revert those changes before the next physics
            // tick reads getCenterOfMass() — that's what was making
            // positionInModel drift by ~10 blocks per mutation, which our
            // compensation then translated into the visible hop.
            ShipInertiaLocker.restore(ship, provider.getLockedInertia());

            logMutationProbe(level, ship, provider, pivotPreMutate, shipWorldPosPreMutate,
                aabbPreMutate, players, shipWx, shipWy, shipWz, originX);
            BodyTransform compensated = provider.computeCompensatedTransform(ship.getTransform());
            if (compensated != null) {
                ship.unsafeSetTransform(compensated);
            }
        }
    }

    /**
     * Stage 1 probe — called immediately after voxel mutations so the caller
     * can compare the pivot / world-position snapshots taken before the
     * mutation against the values VS reports now. If VS has not re-centred
     * the COM yet, pivotPost == pivotPre and the "atomic" unsafeSetTransform
     * that follows is a no-op (hypothesis H1). Per-player lines log each
     * player's world and ship-local X so the pIdx trace (H3) can be
     * correlated with mutation events in the log stream.
     */
    private static void logMutationProbe(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        Vector3dc pivotPreMutate,
        Vector3dc shipWorldPosPreMutate,
        org.joml.primitives.AABBdc aabbPreMutate,
        List<ServerPlayer> players,
        double shipWx, double shipWy, double shipWz,
        int originX
    ) {
        if (!JITTER_LOGGER.isTraceEnabled()) return;

        Vector3dc pivotPostMutate = new Vector3d(ship.getTransform().getPositionInModel());
        Vector3dc shipWorldPosPostMutate = new Vector3d(ship.getTransform().getPosition());
        org.joml.primitives.AABBdc aabbPostMutate = ship.getWorldAABB();
        Vector3d pivotDelta = new Vector3d(pivotPostMutate).sub(pivotPreMutate);
        Vector3d shipPosDelta = new Vector3d(shipWorldPosPostMutate).sub(shipWorldPosPreMutate);

        JITTER_LOGGER.trace(
            "[windowManager] tick={} shipId={} pivotPre={} pivotPost={} pivotDelta={} shipPosPre={} shipPosPost={} shipPosDelta={} aabbPre=[{},{},{} → {},{},{}] aabbPost=[{},{},{} → {},{},{}]",
            level.getGameTime(), ship.getId(),
            TrainTransformProvider.fmt(pivotPreMutate), TrainTransformProvider.fmt(pivotPostMutate), TrainTransformProvider.fmt(pivotDelta),
            TrainTransformProvider.fmt(shipWorldPosPreMutate), TrainTransformProvider.fmt(shipWorldPosPostMutate), TrainTransformProvider.fmt(shipPosDelta),
            String.format("%.3f", aabbPreMutate.minX()), String.format("%.3f", aabbPreMutate.minY()), String.format("%.3f", aabbPreMutate.minZ()),
            String.format("%.3f", aabbPreMutate.maxX()), String.format("%.3f", aabbPreMutate.maxY()), String.format("%.3f", aabbPreMutate.maxZ()),
            String.format("%.3f", aabbPostMutate.minX()), String.format("%.3f", aabbPostMutate.minY()), String.format("%.3f", aabbPostMutate.minZ()),
            String.format("%.3f", aabbPostMutate.maxX()), String.format("%.3f", aabbPostMutate.maxY()), String.format("%.3f", aabbPostMutate.maxZ()));

        for (ServerPlayer player : players) {
            double dx = player.getX() - shipWx;
            double dy = player.getY() - shipWy;
            double dz = player.getZ() - shipWz;
            if (dx * dx + dy * dy + dz * dz > NEAR_RADIUS_SQ) continue;

            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            ship.getTransform().getWorldToShip().transformPosition(local);
            int pIdx = (int) Math.floor((local.x - originX) / (double) provider.dims().length());
            JITTER_LOGGER.trace(
                "[pIdx] tick={} player={} worldPos=({}, {}, {}) shipLocalX={} pIdx={} committedPIdx={} lastShiftDir={} ticksSinceShift={}",
                level.getGameTime(), player.getName().getString(),
                String.format("%.6f", player.getX()), String.format("%.6f", player.getY()), String.format("%.6f", player.getZ()),
                String.format("%.6f", local.x), pIdx,
                provider.getCommittedPIdx(), provider.getLastShiftDirection(),
                level.getGameTime() - provider.getLastShiftTick());
        }
    }
}
