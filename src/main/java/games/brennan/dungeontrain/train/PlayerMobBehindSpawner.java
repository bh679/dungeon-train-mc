package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.playermob.compat.TrainConfinement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Each time a PlayerMob spawns, gives a configurable <b>percent</b> chance to ALSO spawn a PlayerMob
 * one full carriage group <em>behind</em> a riding player, marching the player's own direction of
 * travel so it closes in from the rear.
 *
 * <p>Triggered from {@link PlayerMobGroupSpawner#maybeSpawnForGroup} right after a (forward)
 * PlayerMob is placed — i.e. <b>per mob spawn, not per tick</b>. The heading is the SIGN of the
 * player's signed carriage index ({@link TrainConfinement#carriageIndex}: positive = travelled
 * forward, negative = travelled backward), applied via {@link TrainConfinement#setMarchDirection};
 * there is no targeting or aggression. "One full group behind" is the carriage group adjacent to the
 * player's current group on the side they came from. Spawning reuses
 * {@link PlayerMobGroupSpawner#spawnPlayerMob}, so the mob is contents-tagged and spared by the
 * kill-ahead sweep (which only culls <em>ahead</em> of the train).</p>
 *
 * <p>Rate is the world's effective behind-spawn percent
 * ({@link DungeonTrainWorldData#getEffectivePlayerMobBehindSpawnPercent}); 0 disables.</p>
 */
public final class PlayerMobBehindSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Slack (blocks) when testing which group's world AABB a player is standing in. */
    private static final double AABB_SLACK = 1.5;

    private PlayerMobBehindSpawner() {}

    /**
     * Roll the behind-spawn gate once (percent) and, on success, spawn a PlayerMob one full carriage
     * group behind a player riding {@code trainId}, marching that player's travel direction. Called
     * once per forward PlayerMob spawn.
     */
    public static void maybeSpawnBehind(ServerLevel level, UUID trainId, RandomSource rng) {
        int percent = DungeonTrainWorldData.get(level.getServer().overworld()).getEffectivePlayerMobBehindSpawnPercent();
        if (percent <= 0) return;                          // disabled
        if (rng.nextInt(100) >= percent) return;           // percent% chance per mob spawn

        List<Trains.Carriage> groups = Trains.findById(level, trainId);
        if (groups.isEmpty()) return;

        ServerPlayer player = pickRidingPlayer(level, groups, rng);
        if (player == null) return;
        int pIdx = TrainConfinement.carriageIndex(player);
        if (pIdx == TrainConfinement.NO_CARRIAGE || pIdx == 0) return;   // not riding, or origin (no travel sign)
        int travelDir = Integer.signum(pIdx);              // the player's direction of travel

        Trains.Carriage playerGroup = groupContaining(groups, player.getX(), player.getY(), player.getZ());
        if (playerGroup == null) return;
        int anchor = playerGroup.provider().getPIdx();
        int groupSize = playerGroup.provider().getGroupSize();

        // One full group behind, toward where the player came from (against their travel direction).
        int behindAnchor = anchor - travelDir * groupSize;
        Trains.Carriage behindGroup = groupWithAnchor(groups, behindAnchor);
        if (behindGroup == null) return;                   // player at the trailing edge — nothing one group back

        int targetPIdx = behindAnchor + (groupSize - 1) / 2;            // centre carriage of the behind group
        BlockPos floorPos = behindFloorPos(behindGroup, targetPIdx);
        boolean ok = PlayerMobGroupSpawner.spawnPlayerMob(level, floorPos, targetPIdx, rng, travelDir);
        LOGGER.info("[DungeonTrain] PlayerMob behind-spawn ({}%) for {} (playerPIdx={} dir={}) one group back at carriagePIdx={} pos={} -> {}",
            percent, player.getGameProfile().getName(), pIdx, travelDir, targetPIdx, floorPos, ok ? "spawned" : "FAILED");
    }

    /** A random player riding this train (valid, non-origin carriage index), or null if none. */
    private static ServerPlayer pickRidingPlayer(ServerLevel level, List<Trains.Carriage> groups, RandomSource rng) {
        List<ServerPlayer> riders = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            int idx = TrainConfinement.carriageIndex(p);
            if (idx == TrainConfinement.NO_CARRIAGE || idx == 0) continue;
            if (groupContaining(groups, p.getX(), p.getY(), p.getZ()) != null) {
                riders.add(p);
            }
        }
        if (riders.isEmpty()) return null;
        return riders.get(rng.nextInt(riders.size()));
    }

    /** The group whose world AABB contains {@code (x,y,z)} (with a little slack), or null. */
    private static Trains.Carriage groupContaining(List<Trains.Carriage> groups, double x, double y, double z) {
        for (Trains.Carriage c : groups) {
            var bb = c.ship().worldAABB();
            if (bb == null) continue;
            if (x >= bb.minX() - AABB_SLACK && x <= bb.maxX() + AABB_SLACK
                    && y >= bb.minY() - AABB_SLACK && y <= bb.maxY() + AABB_SLACK
                    && z >= bb.minZ() - AABB_SLACK && z <= bb.maxZ() + AABB_SLACK) {
                return c;
            }
        }
        return null;
    }

    /** The loaded group anchored at {@code anchorPIdx} with a real (Sable-ticked) world AABB, or null. */
    private static Trains.Carriage groupWithAnchor(List<Trains.Carriage> groups, int anchorPIdx) {
        for (Trains.Carriage c : groups) {
            if (c.provider().getPIdx() != anchorPIdx) continue;
            var bb = c.ship().worldAABB();
            if (bb == null || bb.maxX() <= bb.minX() || bb.maxY() <= bb.minY() || bb.maxZ() <= bb.minZ()) {
                return null;                               // exists but not ticked yet → no spawnable position
            }
            return c;                                      // anchor is unique per group
        }
        return null;
    }

    /**
     * Interior floor-centre {@link BlockPos} (shipyard coords) of the carriage at {@code targetPIdx}
     * within {@code group}, mirroring the per-carriage layout in {@code TrainAssembler.spawnGroup}.
     */
    private static BlockPos behindFloorPos(Trains.Carriage group, int targetPIdx) {
        TrainTransformProvider provider = group.provider();
        CarriageDims dims = provider.dims();
        int slot = targetPIdx - provider.getPIdx();                      // 0-based slot within the group
        int length = dims.length();
        int enclosedStartOffset = provider.getGroupSize() > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos carriageOrigin = provider.getShipyardOrigin().offset(enclosedStartOffset + slot * length, 0, 0);
        return PlayerMobGroupSpawner.interiorFloorCentre(carriageOrigin, dims);
    }
}
