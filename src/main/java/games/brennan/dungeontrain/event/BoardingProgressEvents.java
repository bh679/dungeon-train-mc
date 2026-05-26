package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.net.BoardingProgressPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.primitives.AABBdc;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advances {@link BoardingProgressData}'s travelled-carriage counter based
 * on player movement through carriage groups while boarded on the train.
 *
 * <p>Single-leader rule: at any time, at most one boarded player is the
 * "leader" whose carriage-pIdx delta moves the counter. When the leader
 * disembarks, no delta is applied for that tick — a new leader is picked
 * from any remaining boarded players (their then-current carriage becomes
 * the new {@code lastLeaderCarriage}). This avoids spurious counter jumps
 * when leaders swap.</p>
 *
 * <p>When no player is on the train, the leader is cleared and the counter
 * is frozen. When a player boards again — possibly at a different carriage
 * than the prior leader's disembark point — a new session starts and the
 * counter resumes advancing relative to that boarding entry, never jumping
 * to absorb the carriage-index gap.</p>
 *
 * <p>Gap tolerance: the AABB check is padded horizontally by
 * {@link #HORIZONTAL_PADDING} to bridge the small joints between adjacent
 * carriage groups, and the leader is held for {@link #OFF_TRAIN_GRACE_SCANS}
 * scans after they fall out of every AABB before being cleared — so a
 * player walking continuously across the train doesn't have each
 * cross-group delta eaten by a momentary "off-train" reset.</p>
 *
 * <p>Throttled to once every {@link #SCAN_PERIOD_TICKS} ticks per level.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BoardingProgressEvents {

    /** Per-level scan period. Players don't cross carriage groups faster than this. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /**
     * Scans the leader can be off every carriage AABB before we conclude
     * they actually disembarked (vs briefly traversing a joint between
     * carriage groups). 6 scans × 10 ticks ≈ 3 seconds.
     */
    private static final int OFF_TRAIN_GRACE_SCANS = 6;

    /**
     * Horizontal pad applied to each carriage's worldAABB before the
     * containment check, in blocks. Joints between adjacent carriage groups
     * are tiny but non-zero; without this pad, the player flickers
     * "off-train" once per group boundary and we lose the cross-group delta.
     */
    private static final double HORIZONTAL_PADDING = 1.0;

    /**
     * Last broadcast value of {@code travelledCarriageIndex} — guards against
     * pushing the HUD packet every tick when nothing changed.
     */
    private static int lastBroadcastTravelled = Integer.MIN_VALUE;

    /** Transient: consecutive scans the active leader has been off every AABB. */
    private static int leaderOffTrainScans = 0;

    private BoardingProgressEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;

        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return;

        // Map of boarded players → their current carriage anchor pIdx.
        Map<UUID, Integer> boarded = new LinkedHashMap<>();
        for (ServerPlayer player : level.players()) {
            Integer pIdx = findPlayerCarriagePIdx(carriages, player);
            if (pIdx != null) boarded.put(player.getUUID(), pIdx);
        }

        BoardingProgressData data = BoardingProgressData.get(level);
        UUID leader = data.activeLeaderUUID();

        if (leader != null && boarded.containsKey(leader)) {
            // Happy path: leader currently in a carriage AABB. Apply delta.
            int current = boarded.get(leader);
            int delta = current - data.lastLeaderCarriage();
            data.advance(delta, current);
            leaderOffTrainScans = 0;
        } else if (leader != null) {
            // Leader exists but isn't in any AABB right now. Could be a
            // brief joint between carriage groups, or they actually jumped
            // off. Hold the leader for OFF_TRAIN_GRACE_SCANS before
            // concluding they disembarked.
            boolean leaderOnline = level.getServer().getPlayerList().getPlayer(leader) != null;
            if (!leaderOnline) {
                handOffOrClear(data, boarded);
                leaderOffTrainScans = 0;
            } else {
                leaderOffTrainScans++;
                if (leaderOffTrainScans > OFF_TRAIN_GRACE_SCANS) {
                    handOffOrClear(data, boarded);
                    leaderOffTrainScans = 0;
                }
                // Within grace: keep leader + lastLeaderCarriage, do nothing.
            }
        } else {
            // No leader. Promote any boarded player to leader; otherwise idle.
            leaderOffTrainScans = 0;
            if (!boarded.isEmpty()) {
                Map.Entry<UUID, Integer> first = boarded.entrySet().iterator().next();
                data.setLeader(first.getKey(), first.getValue());
            }
        }

        broadcastIfChanged(data);
    }

    /**
     * Either promote a remaining boarded player to leader (multiplayer
     * hand-off) or clear the leader and freeze the counter.
     */
    private static void handOffOrClear(BoardingProgressData data, Map<UUID, Integer> boarded) {
        if (boarded.isEmpty()) {
            data.clearLeader();
        } else {
            Map.Entry<UUID, Integer> first = boarded.entrySet().iterator().next();
            data.setLeader(first.getKey(), first.getValue());
        }
    }

    /**
     * Initial sync — give the joining player's HUD a value to show before
     * the next tick-driven broadcast fires.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BoardingProgressData data = BoardingProgressData.get(player.serverLevel());
        DungeonTrainNet.sendTo(player, packetFor(data));
    }

    private static void broadcastIfChanged(BoardingProgressData data) {
        int travelled = data.travelledCarriageIndex();
        if (travelled == lastBroadcastTravelled) return;
        lastBroadcastTravelled = travelled;
        PacketDistributor.sendToAllPlayers(packetFor(data));
    }

    private static BoardingProgressPacket packetFor(BoardingProgressData data) {
        int travelled = data.travelledCarriageIndex();
        int carriagesPerTier = Math.max(1, DungeonTrainConfig.getCarriagesPerTier());
        int tier = Math.abs(travelled) / carriagesPerTier;
        return new BoardingProgressPacket(travelled, tier);
    }

    /**
     * Find which carriage's worldAABB contains the player, or null if none.
     * Horizontal bounds are padded by {@link #HORIZONTAL_PADDING} to bridge
     * the small joints between adjacent carriage groups; Y is padded above
     * by 1 to count players standing on a carriage roof as "on the train."
     */
    @Nullable
    private static Integer findPlayerCarriagePIdx(List<Trains.Carriage> carriages, ServerPlayer player) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (px < bb.minX() - HORIZONTAL_PADDING || px > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (py < bb.minY() || py > bb.maxY() + 1.0) continue;
            if (pz < bb.minZ() - HORIZONTAL_PADDING || pz > bb.maxZ() + HORIZONTAL_PADDING) continue;
            return c.provider().getPIdx();
        }
        return null;
    }
}
