package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.discord.DifficultyLevelReport;
import games.brennan.dungeontrain.net.BoardingProgressPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.portal.PortalRoomCell;
import games.brennan.dungeontrain.net.SnapshotCue;
import games.brennan.dungeontrain.net.SnapshotCuePacket;
import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.portal.PortalTripTracker;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.primitives.AABBdc;

import javax.annotation.Nullable;
import java.util.ArrayList;
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

    /**
     * Per-player last difficulty tier for which a Discord notification was posted.
     * Seeded to the current tier on login (avoids re-notifying a returning player)
     * and reset to 0 on respawn (fresh notifications each life). UUID → last-notified-tier.
     */
    private static final Map<UUID, Integer> LAST_NOTIFIED_TIER = new HashMap<>();

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

    /**
     * Per-portal-pair last-known entry-carriage centre. Drives the distance credited to players
     * standing in that pair's room, whose own position says nothing about how far they have
     * travelled. Pruned each scan to the pairs somebody is actually in — see
     * {@link #creditPortalRoomOccupants}. Pair key → last sampled centre.
     */
    private static final Map<Integer, Vec3> LAST_PAIR_CARRIAGE_POS = new HashMap<>();

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
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
                if (p == null) continue;
                // Lifetime train-time is a global stat — frozen for cheated runs and
                // while the player is dead (death-screen time shouldn't count as
                // "alive on the train"). The "boarded" advancement trigger, distance
                // and biome sampling below still run regardless of alive state.
                if (p.isAlive() && !RunIntegrity.isCheated(p)) {
                    long newTotal = GlobalPlayerStats.addTrainTicks(uuid, SCAN_PERIOD_TICKS);
                    AchievementEvents.notifyTrainTime(p, newTotal);
                }
                // Defer the "Dungeon Train" toast until the spawn intro loading
                // screen + cinematic have finished — same signal StartingBookEvents
                // uses to hold the welcome lightning, so the player sees the reveal
                // before the toast.
                if (!CinematicIntroService.isCinematicActive(uuid)) {
                    games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
                        .trigger(p, "boarded");
                }
                // Single-life time aboard: per-run boarded-tick counter that
                // resets on death. Twin of the cross-world train-time above —
                // also frozen while dead so the death screen doesn't rack up run time.
                if (p.isAlive()) {
                    long runTrainTicks = p.getData(ModDataAttachments.PLAYER_RUN_STATE.get())
                        .addTrainTimeTicks(SCAN_PERIOD_TICKS);
                    AchievementEvents.notifyRunTrainTime(p, runTrainTicks);
                }
                accumulateBoardedDistance(p);
                sampleBoardedBiome(level, p);
            }
        }
        // Drop tracking for players who disembarked since last scan, so the
        // first sample after they re-board starts a fresh delta baseline
        // rather than booking a teleport-sized jump.
        LAST_BOARDED_POS.keySet().retainAll(boarded.keySet());

        // A player who walked into a portal room is off every carriage AABB, so none of the above
        // sees them. Credit them here instead — see creditPortalRoomOccupants for what "credit"
        // means when the room they are standing in does not move.
        creditPortalRoomOccupants(level, carriages, boarded);

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
        LAST_NOTIFIED_TIER.remove(player.getUUID());
        // A trip that did not finish this session did not happen — and the room its entry point
        // referred to is itself re-stamped on approach. See PortalTripTracker.
        PortalTripTracker.forget(player.getUUID());
    }

    /**
     * Reset the notified-tier baseline on respawn so the player gets fresh
     * Discord tier notifications in their new life (tier resets to 0 on death),
     * and stamp the new life's distance origin.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_NOTIFIED_TIER.put(player.getUUID(), 0);
        // The respawn clone carries no RUN_SPAWN_X (not copyOnDeath), and the player has already been
        // placed at their respawn position here — so this is the exact origin for the new life.
        // Overwrite unconditionally: this is authoritative, the lazy capture below is only a fallback.
        player.setData(ModDataAttachments.RUN_SPAWN_X.get(), player.blockPosition().getX());
    }

    /**
     * World-X this player's current life started from, or {@code null} if it was never captured
     * (a life already in progress when this feature shipped, or one that never boarded).
     *
     * <p>Tests {@code hasData} first — {@code getData} would materialise the default {@code 0} and
     * make an uncaptured life indistinguishable from one that genuinely began at X=0.</p>
     */
    @Nullable
    public static Integer runSpawnX(ServerPlayer player) {
        return player.hasData(ModDataAttachments.RUN_SPAWN_X.get())
                ? player.getData(ModDataAttachments.RUN_SPAWN_X.get())
                : null;
    }

    /**
     * Credit everybody standing in a portal room's body the way the boarded path credits everybody
     * standing in a carriage.
     *
     * <h2>Why this exists</h2>
     * <p>A portal room is stamped into the basement below bedrock, and the player who walks into one
     * is inside no carriage AABB at all. Every counter above therefore stopped for them — silently,
     * as a side effect of how boarding is detected rather than as a decision anyone made. Going
     * through a portal room cost you progress. This is the explicit branch that says otherwise.</p>
     *
     * <h2>Distance is the train's, not the player's</h2>
     * <p>On the train, the world-position delta {@link #accumulateBoardedDistance} measures is the
     * carriage carrying the player plus the player walking. In a room neither term survives: the room
     * is static in the basement, so the player's own delta is only how far they paced about, and the
     * train they are travelling on has moved out from over them. So the figure credited is the
     * <b>entry carriage's</b> movement this scan — how far they travelled while inside — and pacing
     * the room earns nothing. Distance here means distance down the track, as it does everywhere
     * else this counter is read.</p>
     *
     * <p>Sampled per pair rather than per player, so two players in the same room are credited the
     * same movement rather than each re-deriving it, and a pair nobody is in costs nothing.</p>
     *
     * <h2>Corridors are not rooms</h2>
     * <p>{@link PortalCarriageEvents#portalRoomBodyPairKey} excludes the twin corridors and every
     * scattered copy of them. A player crossing a portal carriage passes through a doorway, not a
     * room, and neither the credit nor the dwell behind "Train inside a train?" should treat those
     * few blocks as having gone somewhere.</p>
     */
    /**
     * The THRESHOLD ride-photo cue for a player who has just arrived in a room, carrying the room
     * copy they are standing in and the corridors inside it so their client can keep the camera out
     * of both the copy next door and the twin corridors — see {@code PortalRoomCell}.
     *
     * <p>An unresolvable cell (the structure moved out from under the scan) sends the cue anyway,
     * unframed: a photo taken from a slightly wrong spot beats no photo of arriving at all.</p>
     */
    private static SnapshotCuePacket thresholdCue(CarriageDims dims, ServerPlayer player) {
        String reason = "entered a train dimension";
        PortalRoomCell cell = PortalCarriageEvents.portalRoomCell(
            dims, player.getX(), player.getY(), player.getZ());
        if (cell == null) return new SnapshotCuePacket(SnapshotCue.THRESHOLD, reason);
        List<SnapshotCuePacket.Box> exclude = new ArrayList<>(cell.corridors().size());
        for (BoundingBox corridor : cell.corridors()) exclude.add(toBox(corridor));
        return new SnapshotCuePacket(SnapshotCue.THRESHOLD, reason, toBox(cell.body()), exclude);
    }

    private static SnapshotCuePacket.Box toBox(BoundingBox box) {
        return new SnapshotCuePacket.Box(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private static void creditPortalRoomOccupants(ServerLevel level, List<Trains.Carriage> carriages,
                                                  Map<UUID, Integer> boarded) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        Map<Integer, Double> movedByPair = new HashMap<>();
        Map<UUID, Integer> occupants = new LinkedHashMap<>();

        for (ServerPlayer player : level.players()) {
            // Belt and braces: the room body sits below bedrock and the carriage AABBs above it, so
            // these sets are disjoint by construction — but a player credited twice in one scan is
            // exactly the bug this is cheap to make impossible.
            //
            // A boarded player is still ticked as "not in a room" rather than skipped outright: the
            // dwell counts CONSECUTIVE scans, so walking back out to the train has to clear it. Skip
            // them here and a player could bank four seconds, step onto the train, and come back to
            // earn the advancement one second later.
            Integer pairKey = boarded.containsKey(player.getUUID()) ? null
                : PortalCarriageEvents.portalRoomBodyPairKey(
                    dims, player.getX(), player.getY(), player.getZ());
            if (pairKey == null) {
                PortalTripTracker.tickDwell(player.getUUID(), false);
                continue;
            }
            occupants.put(player.getUUID(), pairKey);
        }

        // Drop the movement baseline for pairs nobody is in, so a room re-entered later starts a
        // fresh delta rather than booking everything the train did while it stood empty. Same
        // reasoning as LAST_BOARDED_POS's retainAll.
        LAST_PAIR_CARRIAGE_POS.keySet().retainAll(occupants.values());
        if (occupants.isEmpty()) return;

        for (Map.Entry<UUID, Integer> entry : occupants.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            int pairKey = entry.getValue();

            // Whose library is this? Silent for every room that does not lock its books, which is
            // almost all of them. Rides this scan rather than adding one: the occupancy question it
            // needs — which player is in which room body — has just been answered above.
            games.brennan.dungeontrain.narrative.PortalLibraryGreeter.tick(player, pairKey);

            // Lifetime train-time — same gating as the boarded path: frozen for a cheated run and
            // while dead, so a death screen in a portal room racks up no more than one on the train.
            if (player.isAlive() && !RunIntegrity.isCheated(player)) {
                long newTotal = GlobalPlayerStats.addTrainTicks(player.getUUID(), SCAN_PERIOD_TICKS);
                AchievementEvents.notifyTrainTime(player, newTotal);
            }
            // Single-life time aboard, the per-run twin of the above.
            if (player.isAlive()) {
                long runTrainTicks = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get())
                    .addTrainTimeTicks(SCAN_PERIOD_TICKS);
                AchievementEvents.notifyRunTrainTime(player, runTrainTicks);
            }

            double moved = movedByPair.computeIfAbsent(pairKey,
                key -> pairCarriageMovement(carriages, key));
            if (moved > 0.0) accumulatePortalRoomDistance(player, moved);

            // "Train inside a train?" — five seconds in the room body, not the doorway.
            boolean wasSatisfied = PortalTripTracker.dwellSatisfied(
                PortalTripTracker.dwell(player.getUUID()));
            if (PortalTripTracker.dwellSatisfied(
                    PortalTripTracker.tickDwell(player.getUUID(), true))) {
                AchievementEvents.notifyEnteredPortalRoom(player);
                // The same threshold, but only on the scan it is first crossed: the player has walked
                // in, looked around, and the room has had time to render. Photographing the teleport
                // itself would catch the arrival stall instead of the room. Every later scan is the
                // same visit, and would ask for the same photo over and over.
                if (!wasSatisfied) {
                    PacketDistributor.sendToPlayer(player, thresholdCue(dims, player));
                }
            }
        }
    }

    /**
     * How far this pair's entry carriage moved since the last scan, or {@code 0} when there is no
     * usable figure — the carriage is not resident, or the delta is teleport-sized.
     *
     * <p>The pair key is the entry corridor's carriage index ({@code PortalCarriageRole}), so this
     * is the carriage the player walked in from and the one whose travel is theirs.</p>
     */
    private static double pairCarriageMovement(List<Trains.Carriage> carriages, int pairKey) {
        Vec3 centre = null;
        for (Trains.Carriage c : carriages) {
            if (c.provider().getPIdx() != pairKey) continue;
            AABBdc bb = c.ship().worldAABB();
            centre = new Vec3((bb.minX() + bb.maxX()) / 2.0,
                (bb.minY() + bb.maxY()) / 2.0, (bb.minZ() + bb.maxZ()) / 2.0);
            break;
        }
        // A culled or not-yet-resident carriage has no pose to read. Dropping the baseline as well
        // as the sample means the scan it comes back on starts fresh instead of booking the whole
        // absence as one jump.
        if (centre == null) {
            LAST_PAIR_CARRIAGE_POS.remove(pairKey);
            return 0.0;
        }
        Vec3 last = LAST_PAIR_CARRIAGE_POS.put(pairKey, centre);
        if (last == null) return 0.0;
        double dx = centre.x - last.x;
        double dy = centre.y - last.y;
        double dz = centre.z - last.z;
        double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Same teleport guard the boarded path uses: a re-stamp, a relocation or a train reversal
        // must not be booked as travel.
        if (delta <= 0.0 || delta >= MAX_DELTA_PER_SCAN || !Double.isFinite(delta)) return 0.0;
        return delta;
    }

    /**
     * Book {@code metres} of the train's travel to a player standing in a portal room, into the same
     * two counters {@link #accumulateBoardedDistance} feeds and with the same cheat gating.
     */
    private static void accumulatePortalRoomDistance(ServerPlayer player, double metres) {
        double runMeters = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get())
            .addDistance(metres);
        AchievementEvents.notifyRunDistance(player, runMeters);
        if (!RunIntegrity.isCheated(player)) {
            double lifetimeMeters = GlobalPlayerStats.addDistanceBlocks(player.getUUID(), metres);
            AchievementEvents.notifyLifetimeDistance(player, lifetimeMeters);
        }
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
        // Fallback origin capture for a world's FIRST life, which never fires PlayerRespawnEvent.
        // Only when absent — a captured origin must never drift forward, or every later death in this
        // life would under-report its displacement.
        if (!player.hasData(ModDataAttachments.RUN_SPAWN_X.get())) {
            player.setData(ModDataAttachments.RUN_SPAWN_X.get(), player.blockPosition().getX());
        }
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
        // Global stat: frozen for cheated runs (per-run distance above still ticks).
        if (!RunIntegrity.isCheated(player)) {
            double lifetimeMeters = GlobalPlayerStats.addDistanceBlocks(player.getUUID(), delta);
            AchievementEvents.notifyLifetimeDistance(player, lifetimeMeters);
        }
    }

    /**
     * Sample the biome under {@code player}'s current (world-space) position and
     * fold it into their per-run {@link PlayerBiomeProgress}. A newly seen biome
     * advances the count tiers ("Far Afield" / "Many Lands" / "World Without
     * End" / "Terra Omnia").
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
    }

    /**
     * For each online player, send a {@link BoardingProgressPacket} carrying
     * THEIR OWN {@code travelledCarriageIndex} and the tier it maps to.
     * Per-player {@link #LAST_BROADCAST} guards against duplicate sends.
     */
    private static void broadcastPerPlayer(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // Effective (offset-inclusive) progress so the HUD Diff-Car/Diff-Level and
            // the Discord level-up post reflect an admin difficulty offset.
            int travelled = DifficultyProgression.effectiveTravelled(
                player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex());
            Integer last = LAST_BROADCAST.get(player.getUUID());
            if (last != null && last == travelled) continue;
            LAST_BROADCAST.put(player.getUUID(), travelled);
            DungeonTrainNet.sendTo(player, packetFor(travelled));
            int tier = DifficultyProgression.tierForTravelled(travelled);
            Integer lastNotifiedTier = LAST_NOTIFIED_TIER.get(player.getUUID());
            if (lastNotifiedTier != null && tier > lastNotifiedTier) {
                LAST_NOTIFIED_TIER.put(player.getUUID(), tier);
                DifficultyLevelReport.post(player, tier);
            }
        }
    }

    /** Send a player a snapshot packet for their current (offset-inclusive) travelled value. */
    public static void sendPlayerHudPacket(ServerPlayer player) {
        int travelled = DifficultyProgression.effectiveTravelled(
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex());
        LAST_BROADCAST.put(player.getUUID(), travelled);
        LAST_NOTIFIED_TIER.put(player.getUUID(), DifficultyProgression.tierForTravelled(travelled));
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
