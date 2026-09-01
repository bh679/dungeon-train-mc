package games.brennan.dungeontrain.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in decision trace for the appender's BACKWARD spawn lane
 * ({@code [bwdgen]} log lines).
 *
 * <p><b>Why.</b> A backward group only spawns when it clears six independent
 * gates in {@code TrainCarriageAppender.updateTrain} — near-player, needed
 * window, anchor-not-already-known, registry-edge resolution, the placement /
 * cull-latch lane gate, and footprint chunk readiness. Each gate fails
 * silently and produces the same player-visible symptom: the train stops
 * extending toward the tail. This trace records which gate the lane died on,
 * every tick, with the full state that separates one cause from another, so a
 * single ride identifies the culprit instead of narrowing it one build at a
 * time.</p>
 *
 * <p><b>Cost when off.</b> Nothing. Every caller checks {@link #enabled()}
 * first, so a disabled trace adds one volatile read per train per tick and the
 * spawn loop is otherwise bit-identical — the same convention the appender's
 * {@code SEAMGAP_TRACE_ENABLED} probes follow.</p>
 *
 * <p><b>Emission policy</b> (see {@link #shouldEmit}): immediately whenever the
 * blocking reason CHANGES, otherwise once every {@link #SAMPLE_PERIOD_TICKS}
 * while a block persists. A block that outlives {@link #STOPPED_AFTER_TICKS}
 * escalates once to {@code WARN} (plus a chat line when
 * {@link DebugFlags#chatStallTrain()} is on), and the next successful spawn
 * logs a matching {@code RESUMED} line. So the log carries both a per-second
 * time series and an unmissable marker at the moment generation stopped.</p>
 *
 * <p>Server thread only. State is per-train and immutable: each tick builds a
 * fresh {@link Sample} record rather than mutating the stored one.</p>
 */
public final class BackwardGenTrace {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Why the backward lane did (or did not) spawn this tick. Ordered from
     * "made progress" to "blocked", and each constant maps to exactly one
     * gate in {@code updateTrain} so the log is unambiguous about which
     * branch was taken.
     */
    public enum Reason {
        /** A backward group spawned this tick through the normal gated path. */
        SPAWNED(false),
        /** A backward group spawned this tick as part of an in-flight catch-up FILL run. */
        FILL_RUN(false),
        /** No player within {@code NEAR_RADIUS} of any group — the train is unattended. */
        NOT_NEAR(true),
        /** The players' needed window no longer extends past the registry's min anchor. */
        NO_NEED(true),
        /** The next backward anchor is already in the spawn registry. */
        ANCHOR_KNOWN(true),
        /** Registry edge is neither visible, held, nor resident — deferred until it surfaces. */
        EDGE_DEFER(true),
        /** Registry edge was culled to Sable holding; a reload was issued and the tick deferred. */
        EDGE_RELOAD(true),
        /** The previous backward spawn has not reached {@code placedSuccessfully} yet. */
        GATE_PENDING(true),
        /** The cull-clear latch is holding the backward lane shut. */
        GATE_CULL_LATCH(true),
        /** Spawn deferred while the footprint's world chunks generate asynchronously. */
        CHUNKGEN_DEFER(true);

        private final boolean blocking;

        Reason(boolean blocking) {
            this.blocking = blocking;
        }

        /** True for every reason that means "no backward group was added this tick". */
        public boolean isBlocking() {
            return blocking;
        }
    }

    /**
     * One tick's worth of backward-lane state for one train. Immutable; a new
     * instance is built per emitted sample and the previous one is replaced
     * wholesale in {@link #LAST_SAMPLE}.
     *
     * @param gameTick       server tick the sample was taken on
     * @param reason         which gate the lane reached
     * @param blockedFor     consecutive ticks the lane has been blocked (0 when it spawned)
     * @param playerPIdx     nearest player's pIdx in the LEAD group's frame (drives the window)
     * @param occupiedPIdx   the pIdx of the group the player is physically standing in, or
     *                       {@code null} — divergence from {@code playerPIdx} is the frame-skew
     *                       signal
     * @param playerX        the player's world X (falls as they ride/walk toward the tail)
     * @param minNeeded      {@code globalMinNeededPIdx} — the lowest pIdx any player needs
     * @param registryMin    {@code trainMinAnchor} from the spawn registry (drives the need test)
     * @param visibleTail    lowest pIdx actually present in Sable's visible train
     * @param registryCount  number of registered anchors
     * @param visibleCount   number of visible groups
     * @param anchor         the backward anchor the lane would spawn at
     * @param deficit        {@code trainMinAnchor − globalMinNeededPIdx}, the lane's shortfall
     * @param ticksPending   ticks since the pending backward spawn was issued, or −1 if none
     * @param latchAge       ticks since the cull-clear latch was stamped, or −1 if unlatched
     * @param edgeSub        registry-edge sub-level id, or {@code null}
     * @param forceLoaded    number of sub-levels this train currently force-loads
     * @param chunkWait      ticks the lane has been waiting on footprint chunk-gen, or −1
     * @param targetCount    the player's target carriage count (config or auto-from-render-distance)
     * @param tailGapX       blocks of train physically behind the player: their world X minus the
     *                       visible tail group's lowest-X face. Near zero means they are standing at
     *                       the end of the train, which is the symptom being investigated — pIdx
     *                       accounting alone cannot distinguish "20 carriages behind me" from "a
     *                       void behind me". {@code NaN} when there is no near player.
     */
    public record Sample(
        long gameTick,
        Reason reason,
        long blockedFor,
        int playerPIdx,
        Integer occupiedPIdx,
        double playerX,
        int minNeeded,
        int registryMin,
        int visibleTail,
        int registryCount,
        int visibleCount,
        int anchor,
        int deficit,
        long ticksPending,
        long latchAge,
        UUID edgeSub,
        int forceLoaded,
        long chunkWait,
        int targetCount,
        double tailGapX
    ) {
        /**
         * How far the registry's min anchor sits below the visible tail. A
         * large or growing span means the registry is full of culled-but-still-
         * registered ghosts, which is what makes the needed-window test read
         * "nothing to do" while the player stands at a visibly-ended train.
         */
        public int span() {
            return visibleTail - registryMin;
        }

        /**
         * Divergence between the group the player is physically in and the pIdx
         * the lead group's frame says they are at. Non-zero and growing means
         * the window is being computed against a frame that no longer describes
         * where the player actually is. {@code 0} when the occupied group is
         * unknown.
         */
        public int skew() {
            return (occupiedPIdx == null) ? 0 : (occupiedPIdx - playerPIdx);
        }

        /** Single greppable line; the field set is the hypothesis discriminator. */
        public String format(UUID trainId) {
            return String.format(
                "[DungeonTrain][bwdgen] tick=%d train=%s reason=%s blockedFor=%d playerPIdx=%d "
                    + "occupiedPIdx=%s skew=%d playerX=%.2f minNeeded=%d registryMin=%d visibleTail=%d "
                    + "span=%d registryCount=%d visibleCount=%d anchor=%d deficit=%d ticksPending=%d "
                    + "latchAge=%d edgeSub=%s forceLoaded=%d chunkWait=%d target=%d tailGapX=%.1f",
                gameTick, shortId(trainId), reason, blockedFor, playerPIdx,
                (occupiedPIdx == null) ? "n/a" : occupiedPIdx.toString(), skew(), playerX,
                minNeeded, registryMin, visibleTail, span(), registryCount, visibleCount,
                anchor, deficit, ticksPending, latchAge, shortId(edgeSub), forceLoaded,
                chunkWait, targetCount, tailGapX);
        }
    }

    /**
     * Sample cadence while a block persists: one line per second at 20 Hz. A
     * reason CHANGE always emits immediately regardless of cadence, so no
     * transition is ever lost to the throttle.
     */
    static final long SAMPLE_PERIOD_TICKS = 20L;

    /**
     * Consecutive blocked ticks after which the lane is declared stopped and
     * escalated once to WARN + chat. 100 ticks = 5 s — comfortably past the
     * 60-tick placement settle that gates a healthy lane between spawns, so
     * normal pacing never trips it.
     */
    static final long STOPPED_AFTER_TICKS = 100L;

    /**
     * Master switch. Defaults to ON in a dev environment and OFF in
     * production, matching {@code GenProfiler}'s precedent: a dev test ride
     * captures the trace with no setup, while shipped builds stay silent.
     */
    private static volatile boolean enabled = !FMLLoader.isProduction();

    /** Newest sample per train, for the {@code traingen status} command. */
    private static final Map<UUID, Sample> LAST_SAMPLE = new ConcurrentHashMap<>();
    /** Tick the newest line was EMITTED for each train (not merely recorded). */
    private static final Map<UUID, Long> LAST_EMIT_TICK = new ConcurrentHashMap<>();
    /** Reason carried by each train's last emitted line, for change detection. */
    private static final Map<UUID, Reason> LAST_REASON = new ConcurrentHashMap<>();
    /** Tick each train's current block began, or absent while the lane is healthy. */
    private static final Map<UUID, Long> BLOCKED_SINCE = new ConcurrentHashMap<>();
    /** Trains already escalated to the STOPPED warning, cleared when they resume. */
    private static final Map<UUID, Boolean> STOPPED_WARNED = new ConcurrentHashMap<>();

    private BackwardGenTrace() {}

    /** @see #enabled */
    public static boolean enabled() {
        return enabled;
    }

    /** Toggle the trace. Server thread only. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Newest recorded sample for a train, or {@code null} if none this session. */
    public static Sample lastSample(UUID trainId) {
        return LAST_SAMPLE.get(trainId);
    }

    /** Every train with a recorded sample, newest state each. Snapshot copy. */
    public static Map<UUID, Sample> allSamples() {
        return Map.copyOf(LAST_SAMPLE);
    }

    /**
     * Is the lane actually FAILING to extend, as opposed to having nothing to do?
     *
     * <p>{@link Reason#isBlocking} alone answers "did a group spawn this tick", which is not the
     * same question. In the steady state a healthy lane spends most of its time on
     * {@link Reason#NO_NEED} with a non-positive deficit — it has already generated everything the
     * player's window asks for and is waiting for them to travel further. Escalating that produced
     * a STOPPED warning every five seconds through a 10-minute ride in which the lane never once
     * fell behind, which is worse than no signal: it makes a real stall indistinguishable from
     * normal pacing.
     *
     * <p>So a sample counts as stalling only when the lane WANTS to extend — a positive deficit —
     * or when it is held by a hard gate that no amount of waiting resolves.</p>
     */
    static boolean isStalling(Reason reason, int deficit) {
        if (!reason.isBlocking()) return false;
        if (reason == Reason.NO_NEED) return deficit > 0;
        return true;
    }

    /**
     * Pure emission policy, split out so the throttle is unit-testable without
     * a level: emit when the reason changed since the last line (including the
     * very first line for a train, where {@code previous} is {@code null}), or
     * when {@link #SAMPLE_PERIOD_TICKS} have elapsed since the last one.
     */
    static boolean shouldEmit(Reason previous, Reason current, long lastEmitTick, long now) {
        if (previous != current) return true;
        return now - lastEmitTick >= SAMPLE_PERIOD_TICKS;
    }

    /**
     * Consecutive blocked ticks given the tick the current block began.
     * {@code null} (no active block) yields 0, which is also what a
     * non-blocking reason reports.
     */
    static long blockedFor(Long blockedSinceTick, long now) {
        return (blockedSinceTick == null) ? 0L : Math.max(0L, now - blockedSinceTick);
    }

    /**
     * Record one tick of backward-lane state and emit a line if the policy says
     * so. Callers must check {@link #enabled()} first — this method assumes it.
     *
     * @param level   the train's level, used only to broadcast the stop notice
     * @param trainId the train being traced
     * @param sample  this tick's state, with {@code blockedFor} left at 0 (this
     *                method fills it in from the tracked block start)
     */
    public static void record(ServerLevel level, UUID trainId, Sample sample) {
        long now = sample.gameTick();
        boolean blocking = isStalling(sample.reason(), sample.deficit());

        Long blockedSince;
        if (blocking) {
            blockedSince = BLOCKED_SINCE.putIfAbsent(trainId, now);
            if (blockedSince == null) blockedSince = now;
        } else {
            blockedSince = null;
            BLOCKED_SINCE.remove(trainId);
        }

        long blocked = blockedFor(blockedSince, now);
        // Rebuild rather than mutate: Sample is immutable and blockedFor is the
        // one field the caller can't know.
        Sample stamped = new Sample(
            sample.gameTick(), sample.reason(), blocked, sample.playerPIdx(), sample.occupiedPIdx(),
            sample.playerX(), sample.minNeeded(), sample.registryMin(), sample.visibleTail(),
            sample.registryCount(), sample.visibleCount(), sample.anchor(), sample.deficit(),
            sample.ticksPending(), sample.latchAge(), sample.edgeSub(), sample.forceLoaded(),
            sample.chunkWait(), sample.targetCount(), sample.tailGapX());
        LAST_SAMPLE.put(trainId, stamped);

        Reason previous = LAST_REASON.get(trainId);
        Long lastEmit = LAST_EMIT_TICK.get(trainId);
        if (shouldEmit(previous, stamped.reason(), (lastEmit == null) ? Long.MIN_VALUE : lastEmit, now)) {
            LOGGER.info("{}", stamped.format(trainId));
            LAST_REASON.put(trainId, stamped.reason());
            LAST_EMIT_TICK.put(trainId, now);
        }

        if (!blocking) {
            // Resumed — announce it once so the log brackets the outage with a
            // matched STOPPED / RESUMED pair.
            if (STOPPED_WARNED.remove(trainId) != null) {
                LOGGER.warn("[DungeonTrain][bwdgen] RESUMED trainId={} at tick={} via {} — {}",
                    trainId, now, stamped.reason(), stamped.format(trainId));
                announce(level, Component.literal(
                    "[DT] Backward generation RESUMED (" + stamped.reason() + ")"
                ).withStyle(ChatFormatting.GREEN));
            }
            return;
        }

        if (blocked >= STOPPED_AFTER_TICKS && STOPPED_WARNED.putIfAbsent(trainId, Boolean.TRUE) == null) {
            LOGGER.warn("[DungeonTrain][bwdgen] STOPPED trainId={} — backward lane blocked {} ticks on {}; {}",
                trainId, blocked, stamped.reason(), stamped.format(trainId));
            announce(level, Component.literal(
                "[DT] Backward generation STOPPED — " + stamped.reason()
                    + " (anchor=" + stamped.anchor() + ", span=" + stamped.span()
                    + ", skew=" + stamped.skew() + ")"
            ).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Broadcast a stop/resume notice to players, reusing the existing
     * {@code chatlogs stall} gate so the chat side can be silenced
     * independently of the log. The WARN above fires regardless, so muting
     * chat never loses forensic data.
     */
    private static void announce(ServerLevel level, Component message) {
        if (!DebugFlags.chatStallTrain()) return;
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(message);
        }
    }

    /**
     * Human-readable state lines for the {@code /dungeontrain debug traingen
     * status} command — one per train with a recorded sample.
     */
    public static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Sample> e : LAST_SAMPLE.entrySet()) {
            Sample s = e.getValue();
            out.add(String.format(
                "train %s: %s (blocked %d ticks) anchor=%d playerPIdx=%d occupied=%s skew=%d "
                    + "minNeeded=%d registryMin=%d visibleTail=%d span=%d groups=%d/%d "
                    + "forceLoaded=%d pending=%d latch=%d chunkWait=%d tailGapX=%.1f",
                shortId(e.getKey()), s.reason(), s.blockedFor(), s.anchor(), s.playerPIdx(),
                (s.occupiedPIdx() == null) ? "n/a" : s.occupiedPIdx().toString(), s.skew(),
                s.minNeeded(), s.registryMin(), s.visibleTail(), s.span(),
                s.visibleCount(), s.registryCount(), s.forceLoaded(), s.ticksPending(),
                s.latchAge(), s.chunkWait(), s.tailGapX()));
        }
        return out;
    }

    /** Drop all per-train state. Called from the appender's train-wipe path. */
    public static void clear() {
        LAST_SAMPLE.clear();
        LAST_EMIT_TICK.clear();
        LAST_REASON.clear();
        BLOCKED_SINCE.clear();
        STOPPED_WARNED.clear();
    }

    /** First 8 chars of a UUID — enough to correlate lines, short enough to read. */
    static String shortId(UUID id) {
        return (id == null) ? "none" : id.toString().substring(0, 8);
    }
}
