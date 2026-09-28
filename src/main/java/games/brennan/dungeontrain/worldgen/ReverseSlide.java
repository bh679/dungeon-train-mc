package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ReverseSlideSyncPacket;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the reversed bands behind spawn exactly as far away as players have <em>earned</em> by riding.
 *
 * <p>The <b>origin</b> is where a player first stood on the train — "spawn" for this rule. Distance is
 * <b>earned</b> only by walking back along the train into new ground: a player must be on the train at
 * two consecutive scans, and only the part of that step behind everywhere anyone has already reached
 * counts. Everything is world space — the train's own position plus wherever the player is along it —
 * so carriages walked in the train's frame never count, only the net backward displacement beyond the
 * train's forward motion.</p>
 *
 * <p>Any other way of getting further back — walking off the train, flying over it, teleporting, or a
 * jump longer than anyone can walk in a scan — still moves the <b>reach</b> (the lowest world X anyone
 * counted has been), but earns nothing. The reversed cycle ({@link WorldGenCycle#reverseSlide}) starts
 * its lead gap behind {@code reach + earned}, so the gap in front of the furthest-back player only ever
 * shrinks by earned distance: unearned ground pushes the bands back by exactly as much. Origin, reach,
 * earned and slide only move one way and are persisted in {@link DungeonTrainWorldData}.</p>
 *
 * <p>The slide only reaches terrain generated after it grows — chunks already on disk keep what they
 * were generated as. It steps in {@link #STEP}-block quanta so it changes rarely: each change clears the
 * per-chunk band caches and syncs every client (whose sky, fog and upside-down render read the same
 * cycle). The same packet feeds the dev-HUD "Back:" read-out beside Diff-Car.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ReverseSlide {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Slide granularity in blocks — the slide is always a multiple of this. */
    static final long STEP = 64L;

    /** Ticks between scans. */
    private static final int SCAN_PERIOD_TICKS = 20;

    /**
     * Longest backward step between two scans that still counts as walking along the train. A sprint-jump
     * covers well under this in a second; anything longer is a teleport or a long fall and earns nothing.
     */
    static final double MAX_EARNED_STEP = 12.0;

    /** Sentinel for an origin / reach not yet set. */
    static final long UNSET = Long.MAX_VALUE;

    private ReverseSlide() {}

    /**
     * One player this scan: world X, whether they are on the train, whether their position may push the
     * bands back (not creative on a server — see {@link #samples}), and their world X at the previous scan
     * if they were on the train then too ({@code NaN} otherwise — nothing to earn from).
     */
    record Sample(double x, boolean onTrain, boolean slides, double prevOnTrainX) {}

    /** The persisted state. */
    record State(long originX, long reachX, long earned, long slide) {}

    /** A scan's new state and the blocks each sample earned in it (same order as the samples). */
    record Result(State state, long[] earnedBy) {}

    /**
     * Pure update rule. The origin is set once, at the first on-train position seen; until then the
     * cycle's {@code startX} stands in. For each player on the train now and at the previous scan, the
     * part of their backward step behind the reach is earned. Every counted player (on the train, or a
     * sliding one off it) then lowers the reach. The slide places the bands' lead gap behind
     * {@code reach + earned}: {@code startX − reach − earned}, rounded up to {@link #STEP}, never below 0
     * and never shrinking.
     */
    static Result next(long startX, State prev, List<Sample> players) {
        long o = prev.originX();
        if (o == UNSET) {
            for (Sample p : players) {
                if (p.onTrain()) o = Math.min(o, (long) Math.floor(p.x()));
            }
        }
        long reach = prev.reachX() != UNSET ? prev.reachX() : (o == UNSET ? startX : o);
        boolean reached = prev.reachX() != UNSET || o != UNSET;
        long earned = prev.earned();
        long[] earnedBy = new long[players.size()];
        for (int i = 0; i < players.size(); i++) {
            Sample p = players.get(i);
            long x = (long) Math.floor(p.x());
            if (p.onTrain() && prev.originX() != UNSET && isWalk(p)) {
                long gain = Math.max(0L, Math.min((long) Math.floor(p.prevOnTrainX()), reach) - x);
                earnedBy[i] = gain;
                earned += gain;
            }
            if ((p.onTrain() || p.slides()) && x < reach) {
                reach = x;
                reached = true;
            }
        }
        long s = Math.max(prev.slide(), roundUp(startX - reach - earned));
        return new Result(new State(o, reached ? reach : UNSET, earned, s), earnedBy);
    }

    /** True when the player was on the train last scan too and stepped back no further than a walk. */
    private static boolean isWalk(Sample p) {
        return !Double.isNaN(p.prevOnTrainX()) && p.prevOnTrainX() - p.x() <= MAX_EARNED_STEP;
    }

    /** {@code blocks} rounded up to a whole {@link #STEP}; 0 when not positive. */
    private static long roundUp(long blocks) {
        return blocks <= 0L ? 0L : Math.ceilDiv(blocks, STEP) * STEP;
    }

    /** Load the world's slide into the cycle as the overworld comes up — before any chunk generates. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) return;
        WorldGenCycle.setReverseSlide(DungeonTrainWorldData.get(level).getReverseSlide());
    }

    /** No world, no slide — a later world (or the title-screen client) must not inherit this one's. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WorldGenCycle.setReverseSlide(0L);
        LAST_SENT.clear();
        LAST_ON_TRAIN_X.clear();
    }

    /** Each player's world X at the previous scan, present only if they were on the train then. */
    private static final Map<UUID, Double> LAST_ON_TRAIN_X = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return;
        List<ServerPlayer> players = new ArrayList<>();
        List<Sample> samples = samples(level, players);

        State before = new State(data.getReverseOriginX(), data.getReverseReachX(),
                data.getReverseEarned(), data.getReverseSlide());
        Result result = next(cycle.startX(), before, samples);
        State now = result.state();
        data.setReverseSlideState(now.originX(), now.reachX(), now.earned(), now.slide());
        if (now.slide() != before.slide()) applyChange(before.slide(), now);

        for (int i = 0; i < samples.size(); i++) {
            UUID id = players.get(i).getUUID();
            if (samples.get(i).onTrain()) LAST_ON_TRAIN_X.put(id, samples.get(i).x());
            else LAST_ON_TRAIN_X.remove(id);
        }
        syncHud(level, data, players, result.earnedBy());
    }

    /** Last packet sent to each player, so the HUD read-out only costs traffic when it changes. */
    private static final Map<UUID, ReverseSlideSyncPacket> LAST_SENT = new ConcurrentHashMap<>();

    /** Push each player's read-out: the world's earned distance, and whether this player earned some this scan. */
    private static void syncHud(ServerLevel level, DungeonTrainWorldData data, List<ServerPlayer> counted,
                                long[] earnedBy) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            int i = counted.indexOf(p);
            boolean earning = i >= 0 && earnedBy[i] > 0L;
            ReverseSlideSyncPacket packet = packetFor(data, earning);
            if (packet.equals(LAST_SENT.put(p.getUUID(), packet))) continue;
            DungeonTrainNet.sendTo(p, packet);
        }
    }

    /** The sync packet for a player: the world slide, the earned distance, and whether they are earning it. */
    public static ReverseSlideSyncPacket packetFor(DungeonTrainWorldData data, boolean earning) {
        return new ReverseSlideSyncPacket(data.getReverseSlide(), data.getReverseEarned(), earning);
    }

    /** Every carriage of every train in the level, for the on-train footprint test. */
    private static List<Trains.Carriage> carriages(ServerLevel level) {
        List<Trains.Carriage> carriages = new ArrayList<>();
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) carriages.addAll(train);
        return carriages;
    }

    /**
     * Players that count: everyone in the overworld proper except spectators and anyone in a portal twin's
     * sealed space (not out on the line). Creative players earn on the train like anyone. In singleplayer
     * they count fully; on a server a creative player (an admin flying or teleporting around) never pushes
     * the bands back for everyone else.
     */
    private static List<Sample> samples(ServerLevel level, List<ServerPlayer> players) {
        List<Trains.Carriage> carriages = carriages(level);
        boolean singleplayer = level.getServer().isSingleplayer();
        List<Sample> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (PortalTwinSpace.isInside(level, p.getBlockX(), p.getY())) continue;
            Double prev = LAST_ON_TRAIN_X.get(p.getUUID());
            out.add(new Sample(p.getX(), CarriageDeck.isOnTrainFootprint(carriages, p),
                    singleplayer || !p.isCreative(), prev == null ? Double.NaN : prev));
            players.add(p);
        }
        return out;
    }

    /**
     * How far behind spawn {@code player} has ridden: the world's earned distance ({@link #next}) while they
     * are on the train — flying, teleporting or walking off-train never adds to it — or 0 when off the
     * train or in a portal twin's sealed space. {@code startX} is unused since distance became earned
     * rather than positional; kept for callers. Gamemode is not checked; callers decide who counts.
     */
    public static long riddenBehindSpawn(ServerLevel level, long startX, ServerPlayer player) {
        long earned = DungeonTrainWorldData.get(level).getReverseEarned();
        if (earned <= 0L) return 0L;
        if (PortalTwinSpace.isInside(level, player.getBlockX(), player.getY())) return 0L;
        return CarriageDeck.isOnTrainFootprint(carriages(level), player) ? earned : 0L;
    }

    private static void applyChange(long before, State now) {
        WorldGenCycle.setReverseSlide(now.slide());
        StacksBand.invalidateCache();
        ChuncksBand.invalidateCache();
        SpheresBand.invalidateCache();
        MixBand.invalidateCache();
        LegacyBands.invalidateCache();
        LOGGER.info("[DungeonTrain] reverse bands slid back {} -> {} blocks (reach X={}, earned {})",
                before, now.slide(), now.reachX(), now.earned());
    }
}
