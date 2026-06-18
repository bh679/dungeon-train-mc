package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.net.BoardingProgressPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.BiomeFamilies;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Per-player last broadcast value of {@code travelledCarriageIndex} —
     * guards against pushing the HUD packet to a player when their own value
     * hasn't changed since the last scan. UUID → last-sent-travelled.
     */
    private static final Map<UUID, Integer> LAST_BROADCAST = new HashMap<>();

    /** Transient: consecutive scans the active leader has been off every AABB. */
    private static int leaderOffTrainScans = 0;

    /**
     * Per-player last-known boarded position. Used to compute world-space
     * movement deltas for {@link PlayerRunState#distanceBlocks}. Entries
     * for players who disembark are dropped each scan so a re-boarding
     * player doesn't book a teleport-sized jump.
     */
    private static final Map<UUID, Vec3> LAST_BOARDED_POS = new HashMap<>();

    /**
     * Sanity cap on a single-scan distance delta. Above this is assumed to
     * be a teleport / dimension change rather than real movement, and we
     * skip the accumulation. SCAN_PERIOD_TICKS = 10 ticks = 0.5 s; even
     * sprint-jumping is well under 20 blocks in that window.
     */
    private static final double MAX_DELTA_PER_SCAN = 100.0;

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

        // Cumulative time-on-train: every boarded player accrues
        // SCAN_PERIOD_TICKS into their global stats. Drives the train-time
        // advancement tier (2h / 10h / 24h). Also fires the "boarded"
        // signal that unlocks the Dungeon Train tab's root advancement
        // (All aboard) — first boarding earns the tab.
        if (!boarded.isEmpty()) {
            for (UUID uuid : boarded.keySet()) {
                long newTotal = GlobalPlayerStats.addTrainTicks(uuid, SCAN_PERIOD_TICKS);
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
                if (p != null) {
                    AchievementEvents.notifyTrainTime(p, newTotal);
                    games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
                        .trigger(p, "boarded");
                    // Single-life time aboard: per-run boarded-tick counter that
                    // resets on death. Twin of the cross-world train-time above.
                    long runTrainTicks = p.getData(ModDataAttachments.PLAYER_RUN_STATE.get())
                        .addTrainTimeTicks(SCAN_PERIOD_TICKS);
                    AchievementEvents.notifyRunTrainTime(p, runTrainTicks);
                    accumulateBoardedDistance(p);
                    sampleBoardedBiome(level, p);
                }
            }
        }
        // Drop tracking for players who disembarked since last scan, so the
        // first sample after they re-board starts a fresh delta baseline
        // rather than booking a teleport-sized jump.
        LAST_BOARDED_POS.keySet().retainAll(boarded.keySet());

        BoardingProgressData data = BoardingProgressData.get(level);
        UUID leader = data.activeLeaderUUID();

        if (leader != null && boarded.containsKey(leader)) {
            // Happy path: leader currently in a carriage AABB. Apply delta.
            int current = boarded.get(leader);
            int delta = current - data.lastLeaderCarriage();
            data.advance(delta, current);
            // Tick the per-player carts-since-death counter so the
            // carts_in_run advancement fires once the leader has actually
            // traversed forward. Negative / zero deltas no-op inside the
            // notify helper.
            ServerPlayer leaderPlayer = level.getServer().getPlayerList().getPlayer(leader);
            if (leaderPlayer != null) {
                AchievementEvents.notifyCartAdvance(leaderPlayer, delta);
                // Per-player travelled-carriage-index drives mob difficulty
                // (max across players, resets on respawn). Signed delta —
                // backward movement reduces tier just like the global
                // counter did before.
                PlayerRunState leaderRun = leaderPlayer.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
                leaderRun.advanceTravelled(delta);
            }
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

        broadcastPerPlayer(level);
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
     * the next tick-driven broadcast fires. Reads the player's own
     * {@link PlayerRunState#travelledCarriageIndex} so the HUD reflects
     * their per-life progress, not the world-global value.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        sendPlayerHudPacket(player);
    }

    /**
     * Drop a player's last-sent cache entry when they log out so a future
     * relogin gets a fresh send. Without this, {@link #LAST_BROADCAST}
     * accumulates stale UUID keys.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_BROADCAST.remove(player.getUUID());
    }

    /**
     * Sample the player's current world position and, if they were boarded
     * in the previous scan, add the magnitude of the delta to their
     * {@link PlayerRunState#distanceBlocks} counter. Naturally combines
     * train-carried movement and on-train walking — both move the player's
     * world position.
     */
    private static void accumulateBoardedDistance(ServerPlayer player) {
        Vec3 current = new Vec3(player.getX(), player.getY(), player.getZ());
        Vec3 last = LAST_BOARDED_POS.put(player.getUUID(), current);
        if (last == null) return;
        double dx = current.x - last.x;
        double dy = current.y - last.y;
        double dz = current.z - last.z;
        double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (delta <= 0.0 || delta >= MAX_DELTA_PER_SCAN || !Double.isFinite(delta)) return;
        // Per-run (single-life) distance — death-screen stat + single-life
        // distance advancements.
        double runMeters = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addDistance(delta);
        AchievementEvents.notifyRunDistance(player, runMeters);
        // Lifetime distance — the same delta, accrued across all worlds/sessions.
        double lifetimeMeters = GlobalPlayerStats.addDistanceBlocks(player.getUUID(), delta);
        AchievementEvents.notifyLifetimeDistance(player, lifetimeMeters);
    }

    /**
     * Sample the biome under {@code player}'s current (world-space) position and
     * fold it into their per-run {@link PlayerBiomeProgress}. A newly seen biome
     * advances the count tiers ("Far Afield" / "Many Lands" / "World Without
     * End"); the first biome of a newly reached family fires "All Under Heaven".
     *
     * <p>Uses {@code player.blockPosition()} — the same world-space position
     * {@link #accumulateBoardedDistance} books distance against (the deck
     * position in world space, not a Sable sub-level coordinate).</p>
     */
    private static void sampleBoardedBiome(ServerLevel level, ServerPlayer player) {
        Holder<Biome> biome = level.getBiome(player.blockPosition());
        Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        if (key.isEmpty()) return;
        ResourceLocation id = key.get().location();

        PlayerBiomeProgress progress = player.getData(ModDataAttachments.PLAYER_BIOME_PROGRESS.get());
        if (progress.addBiome(id)) {
            AchievementEvents.notifyBiomesVisited(player, progress.biomeCount());
        }

        Optional<String> family = BiomeFamilies.classify(biome);
        if (family.isPresent() && progress.addFamily(family.get())) {
            AchievementEvents.notifyBiomeFamilies(player, progress.familyCount());
        }
    }

    /**
     * For each online player, send a {@link BoardingProgressPacket} carrying
     * THEIR OWN {@code travelledCarriageIndex} and the tier it maps to.
     * Per-player {@link #LAST_BROADCAST} guards against duplicate sends.
     */
    private static void broadcastPerPlayer(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            int travelled = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex();
            Integer last = LAST_BROADCAST.get(player.getUUID());
            if (last != null && last == travelled) continue;
            LAST_BROADCAST.put(player.getUUID(), travelled);
            DungeonTrainNet.sendTo(player, packetFor(travelled));
        }
    }

    /** Send a player a snapshot packet for their current travelled value. */
    public static void sendPlayerHudPacket(ServerPlayer player) {
        int travelled = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex();
        LAST_BROADCAST.put(player.getUUID(), travelled);
        DungeonTrainNet.sendTo(player, packetFor(travelled));
    }

    private static BoardingProgressPacket packetFor(int travelled) {
        int tier = DifficultyProgression.tierForTravelled(travelled);
        return new BoardingProgressPacket(travelled, tier);
    }

    /**
     * Find which carriage's worldAABB contains the player, or null if none.
     * Horizontal bounds are padded by {@link #HORIZONTAL_PADDING} to bridge
     * the small joints between adjacent carriage groups; Y is padded above
     * by 3 to count players standing on or sprint-jumping from the roof as
     * "on the train" (sprint-jump peaks at ~1.25 blocks above standing).
     */
    @Nullable
    private static Integer findPlayerCarriagePIdx(List<Trains.Carriage> carriages, ServerPlayer player) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (px < bb.minX() - HORIZONTAL_PADDING || px > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (py < bb.minY() || py > bb.maxY() + 3.0) continue;
            if (pz < bb.minZ() - HORIZONTAL_PADDING || pz > bb.maxZ() + HORIZONTAL_PADDING) continue;
            return c.provider().getPIdx();
        }
        return null;
    }
}
