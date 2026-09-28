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
 * <p>The <b>origin</b> is where a player first stood on the train: "spawn" for this rule, so earned
 * distance is measured from it and the reversed bands start their lead-gap distance behind it (the slide
 * starts at however far the origin sits ahead of the cycle's anchor).</p>
 *
 * <p>The <b>frontier</b> is the lowest world X any player has stood at while on the train — world space,
 * so it is the train's own position plus wherever the player is along it; carriages walked in the
 * train's frame never count, only the net backward displacement beyond the train's forward motion. A
 * player off the train and further back than the frontier pushes the <b>slide</b> out to their distance
 * past it, so the reversed cycle ({@link WorldGenCycle#reverseSlide}) keeps its gap from them: jumping
 * off and walking back never brings the next band closer. Both values only move one way and are
 * persisted in {@link DungeonTrainWorldData}.</p>
 *
 * <p>The slide only reaches terrain generated after it grows — chunks already on disk keep what they
 * were generated as. It steps in {@link #STEP}-block quanta so it changes rarely: each change clears the
 * per-chunk band caches and syncs every client (whose sky, fog and upside-down render read the same
 * cycle). The same packet feeds the dev-HUD distance read-out beside Diff-Car.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ReverseSlide {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Slide granularity in blocks — the slide is always a multiple of this. */
    static final long STEP = 64L;

    /** Ticks between scans; a player covers at most a few dozen blocks in a second. */
    private static final int SCAN_PERIOD_TICKS = 20;

    private ReverseSlide() {}

    /** Sentinel for an origin / frontier not yet set. */
    static final long UNSET = Long.MAX_VALUE;

    /**
     * Where one player stands (world X), whether they are on the train, and whether their off-train
     * position may push the bands back (not creative — flying and teleporting to test must not).
     */
    record Sample(double x, boolean onTrain, boolean slides) {}

    /** The persisted state after a scan. */
    record State(long originX, long frontierX, long slide) {}

    /**
     * Pure update rule. The origin is set once, at the first on-train position seen; until then the
     * cycle's {@code startX} stands in. The frontier starts at the origin and riders lower it; then any
     * sliding off-train player behind it grows the slide to their distance past it. The slide is never
     * less than the origin's lead over {@code startX}, so the bands start their lead gap behind where the
     * train was boarded. Rounded up to {@link #STEP}; the frontier and slide never move back.
     */
    static State next(long startX, long originX, long frontierX, long slide, List<Sample> players) {
        long o = originX;
        if (o == UNSET) {
            for (Sample p : players) {
                if (p.onTrain()) o = Math.min(o, (long) Math.floor(p.x()));
            }
        }
        long f = Math.min(frontierX, o == UNSET ? startX : o);
        for (Sample p : players) {
            if (p.onTrain()) f = Math.min(f, (long) Math.floor(p.x()));
        }
        long s = Math.max(slide, o == UNSET ? 0L : roundUp(o - startX));
        for (Sample p : players) {
            if (p.onTrain() || !p.slides()) continue;
            s = Math.max(s, roundUp(f - (long) Math.floor(p.x())));
        }
        return new State(o, f, s);
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
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return;
        List<Sample> samples = samples(level);
        if (samples.isEmpty()) return;

        State now = next(cycle.startX(), data.getReverseOriginX(), data.getReverseFrontierX(),
                data.getReverseSlide(), samples);
        long before = data.getReverseSlide();
        // Earning needs an origin that was already set: the first boarding itself earns nothing.
        long frontierBefore = data.getReverseOriginX() == UNSET
                ? Long.MIN_VALUE : Math.min(data.getReverseFrontierX(), data.getReverseOriginX());
        data.setReverseSlideState(now.originX(), now.frontierX(), now.slide());
        if (now.slide() != before) applyChange(level, before, now);
        syncHud(level, data, frontierBefore, now);
    }

    /** Last packet sent to each player, so the HUD read-out only costs traffic when it changes. */
    private static final Map<UUID, ReverseSlideSyncPacket> LAST_SENT = new ConcurrentHashMap<>();

    /**
     * Push each player's read-out: the world's earned distance, and whether this player is earning it —
     * on the train, at the frontier, and the frontier moved back this scan.
     */
    private static void syncHud(ServerLevel level, DungeonTrainWorldData data, long frontierBefore, State now) {
        boolean moved = now.frontierX() < frontierBefore;
        List<Trains.Carriage> carriages = carriages(level);
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            boolean earning = moved && p.level() == level
                    && (long) Math.floor(p.getX()) <= now.frontierX()
                    && CarriageDeck.isOnTrainFootprint(carriages, p);
            ReverseSlideSyncPacket packet = packetFor(data, earning);
            if (packet.equals(LAST_SENT.put(p.getUUID(), packet))) continue;
            DungeonTrainNet.sendTo(p, packet);
        }
    }

    /** The sync packet for a player: the world slide, the earned distance, and whether they are earning it. */
    public static ReverseSlideSyncPacket packetFor(DungeonTrainWorldData data, boolean earning) {
        long origin = data.getReverseOriginX();
        long earned = origin == UNSET ? 0L : Math.max(0L, origin - Math.min(data.getReverseFrontierX(), origin));
        return new ReverseSlideSyncPacket(data.getReverseSlide(), earned, earning);
    }

    /** Every carriage of every train in the level, for the on-train footprint test. */
    private static List<Trains.Carriage> carriages(ServerLevel level) {
        List<Trains.Carriage> carriages = new ArrayList<>();
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) carriages.addAll(train);
        return carriages;
    }

    /**
     * Players that count: everyone in the overworld proper except spectators and anyone in a portal twin's
     * sealed space (not out on the line). Creative players earn on the train like anyone — that is how the
     * rule gets tested — but never push the bands back, since they fly and teleport freely.
     */
    private static List<Sample> samples(ServerLevel level) {
        List<Trains.Carriage> carriages = carriages(level);
        List<Sample> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (PortalTwinSpace.isInside(level, p.getBlockX(), p.getY())) continue;
            out.add(new Sample(p.getX(), CarriageDeck.isOnTrainFootprint(carriages, p), !p.isCreative()));
        }
        return out;
    }

    /**
     * How far behind spawn {@code player} has ridden: the blocks back of the first-boarding origin (or
     * {@code startX} before anyone has boarded) they stand at while on the train — the same world-space
     * measure as the frontier — or 0 when off the train, in a portal twin's sealed space, or ahead of it.
     * Gamemode is not checked; callers decide who counts.
     */
    public static long riddenBehindSpawn(ServerLevel level, long startX, ServerPlayer player) {
        long origin = DungeonTrainWorldData.get(level).getReverseOriginX();
        long back = (origin == UNSET ? startX : origin) - (long) Math.floor(player.getX());
        if (back <= 0L) return 0L;
        if (PortalTwinSpace.isInside(level, player.getBlockX(), player.getY())) return 0L;
        return CarriageDeck.isOnTrainFootprint(carriages(level), player) ? back : 0L;
    }

    private static void applyChange(ServerLevel level, long before, State now) {
        WorldGenCycle.setReverseSlide(now.slide());
        StacksBand.invalidateCache();
        ChuncksBand.invalidateCache();
        SpheresBand.invalidateCache();
        MixBand.invalidateCache();
        LegacyBands.invalidateCache();
        LOGGER.info("[DungeonTrain] reverse bands slid back {} -> {} blocks (on-train frontier X={})",
                before, now.slide(), now.frontierX());
    }
}
