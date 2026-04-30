package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.CarriageIndexPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
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
 * Per-tick append-only carriage spawner. For each train ship, advances the
 * provider's {@code highestSpawnedIdx} / {@code lowestSpawnedIdx} watermarks
 * to cover the union of every near player's {@code [pIdx − halfBack,
 * pIdx + halfFront]} window, spawning new carriages at any index between the
 * old and new watermarks.
 *
 * <p>Replaces {@link TrainWindowManager}'s rolling-window pattern, which was
 * designed around Valkyrien Skies' COM-recalc-on-mutation, 128-chunk shipyard
 * wall, and physics-thread / server-thread split. None of those constraints
 * apply on Sable — its mass tracker is read-only, the kinematic ticker pushes
 * every server tick, and there's no shipyard wall — so the simpler "never
 * erase, only append" model is sufficient and avoids Sable's
 * {@code FloatingBlockController} sub-level split risk (a ship can be split
 * when block deletions disconnect the chain; we never delete).</p>
 *
 * <p>HUD push (per-player {@link CarriageIndexPacket}) is preserved verbatim
 * from {@code TrainWindowManager} — the carriage HUD is unchanged.</p>
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
        // [spacing] — if Sable's FloatingBlockController has split the train
        // (block disconnection from variant-block AIR placement, etc.), we'll
        // see more than one ManagedShip with a TrainTransformProvider. Both
        // ships share a provider via the DRIVERS_BY_UUID origin walk, so
        // appending to both means the seam math runs twice against the same
        // shipyardOrigin — a likely cause of erratic carriage positions.
        int trainCount = 0;
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            trainCount++;
            updateTrain(level, ship, provider, players, seenThisTick);
        }
        if (trainCount > 1) {
            // Throttle to avoid log spam — fire only at the moment the count
            // changes via a static flag would need state; for now, the WARN
            // every tick is the fastest way to spot when a split happened.
            LOGGER.warn("[DungeonTrain] [spacing] split detected — {} ManagedShip handles share a TrainTransformProvider this tick",
                trainCount);
        }
        clearDropouts(level, seenThisTick);
    }

    private static void updateTrain(
        ServerLevel level,
        ManagedShip ship,
        TrainTransformProvider provider,
        List<ServerPlayer> players,
        Set<UUID> seenThisTick
    ) {
        int count = provider.getCount();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        int originX = shipyardOrigin.getX();
        CarriageDims dims = provider.dims();

        AABBdc aabb = ship.worldAABB();

        List<Integer> nearPlayerPIdxs = new ArrayList<>();
        for (ServerPlayer player : players) {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double cdx = Math.max(0, Math.max(aabb.minX() - px, px - aabb.maxX()));
            double cdy = Math.max(0, Math.max(aabb.minY() - py, py - aabb.maxY()));
            double cdz = Math.max(0, Math.max(aabb.minZ() - pz, pz - aabb.maxZ()));
            if (cdx * cdx + cdy * cdy + cdz * cdz > NEAR_RADIUS_SQ) continue;

            Vector3d local = new Vector3d(px, py, pz);
            ship.worldToShip(local);
            int pIdx = (int) Math.floor((local.x - originX) / (double) dims.length());

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

        List<Integer> toSpawn = computeIndicesToSpawn(
            provider.getHighestSpawnedIdx(),
            provider.getLowestSpawnedIdx(),
            halfBack, halfFront,
            nearPlayerPIdxs);
        if (toSpawn.isEmpty()) return;

        // Generation config is read fresh each call so runtime-edits in the
        // settings screen pick up for new placements without a respawn.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        for (int idx : toSpawn) {
            spawnCarriage(level, provider, idx, shipyardOrigin, dims, genCfg);
            // Advance the matching watermark. computeIndicesToSpawn emits
            // forward indices ascending then backward indices descending,
            // so each index sits at exactly one frontier.
            if (idx > provider.getHighestSpawnedIdx()) {
                provider.setHighestSpawnedIdx(idx);
            } else if (idx < provider.getLowestSpawnedIdx()) {
                provider.setLowestSpawnedIdx(idx);
            }
        }
    }

    /**
     * Pure decision helper — exposed as package-private for unit tests.
     * Given the train's current watermarks, the count-derived half-back /
     * half-front, and a list of near-player pIdx values, return the indices
     * to spawn this tick in spawn order: forward frontier indices ascending,
     * then backward frontier indices descending. Indices already inside
     * {@code [currentLow, currentHigh]} are never re-emitted (watermark
     * monotonicity).
     */
    static List<Integer> computeIndicesToSpawn(
        int currentHigh,
        int currentLow,
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
        for (int i = currentHigh + 1; i <= maxNeededHigh; i++) out.add(i);
        for (int i = currentLow - 1; i >= minNeededLow; i--) out.add(i);
        return out;
    }

    private static void spawnCarriage(
        ServerLevel level,
        TrainTransformProvider provider,
        int idx,
        BlockPos shipyardOrigin,
        CarriageDims dims,
        CarriageGenerationConfig genCfg
    ) {
        BlockPos carriageOrigin = new BlockPos(
            shipyardOrigin.getX() + idx * dims.length(),
            shipyardOrigin.getY(),
            shipyardOrigin.getZ());
        int sMinX = carriageOrigin.getX();
        int sMaxX = sMinX + dims.length() - 1;
        // [spacing] — neighbour edge lookups so a diff against the previously
        // spawned carriage on this side is one log line, not a hunt through
        // the timeline.
        int prevIdx = idx > 0 ? idx - 1 : idx + 1;
        int prevMinX = shipyardOrigin.getX() + prevIdx * dims.length();
        int prevMaxX = prevMinX + dims.length() - 1;
        int seamGap = idx > 0 ? sMinX - (prevMaxX + 1) : prevMinX - (sMaxX + 1);

        CarriageVariant variant = CarriageTemplate.variantForIndex(idx, genCfg);
        Set<BlockPos> placed = CarriageTemplate.placeAt(level, carriageOrigin, variant, dims, genCfg, idx);
        provider.getActiveIndices().add(idx);
        if (placed.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Appender placed empty carriage idx={} variant={} at {}",
                idx, variant.id(), carriageOrigin);
        } else {
            LOGGER.info("[DungeonTrain] Appender added carriage idx={} variant={} at shipyard {} blocks={} x=[{}, {}] seamGapVsIdx{}={}",
                idx, variant.id(), carriageOrigin, placed.size(), sMinX, sMaxX, prevIdx, seamGap);
        }
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
