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

    /** Where one player stands (world X) and whether they are on the train. */
    record Sample(double x, boolean onTrain) {}

    /** The persisted pair after a scan. */
    record State(long frontierX, long slide) {}

    /**
     * Pure update rule. {@code frontierX} starts at {@code startX} (spawn): riders lower it, then any
     * off-train player behind it grows the slide to their distance past it, rounded up to {@link #STEP}.
     * Neither ever moves back.
     */
    static State next(long startX, long frontierX, long slide, List<Sample> players) {
        long f = Math.min(frontierX, startX);
        for (Sample p : players) {
            if (p.onTrain()) f = Math.min(f, (long) Math.floor(p.x()));
        }
        long s = slide;
        for (Sample p : players) {
            if (p.onTrain()) continue;
            long past = f - (long) Math.floor(p.x());
            if (past > s) s = Math.ceilDiv(past, STEP) * STEP;
        }
        return new State(f, s);
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

        State now = next(cycle.startX(), data.getReverseFrontierX(), data.getReverseSlide(), samples);
        long before = data.getReverseSlide();
        long frontierBefore = Math.min(data.getReverseFrontierX(), cycle.startX());
        data.setReverseSlideState(now.frontierX(), now.slide());
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
        long frontier = data.getReverseFrontierX();
        long startX = WorldGenCycle.fromConfig().startX();
        long earned = frontier == Long.MAX_VALUE ? 0L : Math.max(0L, startX - frontier);
        return new ReverseSlideSyncPacket(data.getReverseSlide(), earned, earning);
    }

    /** Every carriage of every train in the level, for the on-train footprint test. */
    private static List<Trains.Carriage> carriages(ServerLevel level) {
        List<Trains.Carriage> carriages = new ArrayList<>();
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) carriages.addAll(train);
        return carriages;
    }

    /**
     * Players that count: survival/adventure players in the overworld proper. Creative and spectator
     * players fly and teleport freely (testing, building), and a player in a portal twin's sealed space
     * is not out on the line.
     */
    private static List<Sample> samples(ServerLevel level) {
        List<Trains.Carriage> carriages = carriages(level);
        List<Sample> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative()) continue;
            if (PortalTwinSpace.isInside(level, p.getBlockX(), p.getY())) continue;
            out.add(new Sample(p.getX(), CarriageDeck.isOnTrainFootprint(carriages, p)));
        }
        return out;
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
