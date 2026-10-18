package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Protects the train's sub-levels across a singleplayer pause→resume (#547) — the
 * <em>data layer</em> only.
 *
 * <p>In singleplayer the Esc/pause menu, a book screen, an advancement popup (and focus
 * loss) freeze the integrated server's tick loop. While frozen, Sable's collision-carry
 * stops; on resume the train resumes sliding forward (+X) and the stationary rider is
 * left behind, at which point {@link TrainCarriageAppender}'s "player left vicinity" bail
 * would release the force-loads and Sable culls the carriages — and any un-serialized
 * group culls into a null-pointer holding entry {@code snatchAndLoad} can't revive, so it
 * regenerates fresh. (That same autosave-on-pause is also what orphaned a player's
 * carriages "until reload" in the field.)</p>
 *
 * <p>A pause is invisible to {@code getGameTime()} (it doesn't advance while frozen), so
 * this watchdog detects resume by the only signal that survives a freeze: a multi-second
 * <em>wall-clock</em> gap <em>between</em> server ticks — from one tick's
 * {@link ServerTickEvent.Post} to the next tick's {@link ServerTickEvent.Pre}. While paused,
 * {@code IntegratedServer.tickServer} runs {@code tickPaused()} instead of the server tick, so
 * neither event fires and the idle gap grows. A server <em>stall</em> is different: it happens
 * inside a tick (between Pre and Post), and the catch-up ticks after it run back to back, so
 * the idle gap stays ~0. Measuring Post→Pre (not tick-to-tick) is what keeps this inert on
 * lag — a tick-to-tick gap read every multi-second stall as a resume, re-armed the whole-train
 * hold, whose chunk loads caused the next stall, and so on (player log 14 Oct 2026: 5–20 s
 * stalls chained after each autosave). On a detected resume, for every loaded train it:</p>
 * <ul>
 *   <li>{@link TrainCarriageAppender#grantResumeGrace} — suppresses the walk-away
 *       force-load release for a short (renewing, capped) window while the flung-off rider
 *       re-anchors, so the transient "not near" can't drop the train; and</li>
 *   <li>{@link TrainCarriageAppender#holdWholeTrainForResume} — pins every <em>at-risk</em>
 *       carriage resident (not just the trailing-N) for that window: those near a player, which
 *       depend on the Sable proximity residency that evaporates while the rider is absent, and
 *       any not yet serialized, which could not be revived after a cull.</li>
 * </ul>
 *
 * <p>Both holds self-drain back to the trailing-N window once the rider is stably aboard
 * (the grace lapses; {@code reconcileForceLoads} returns to the sliding window, un-serialized
 * groups staying per {@code shouldRetainOnWalkAway}).</p>
 *
 * <p><b>Deliberately data-layer only.</b> This does NOT re-teleport the rider or fire a deck
 * hold packet — that rider-positioning layer (the reverted #548 / #564) is unrelated to the
 * carriage-vanish and is out of scope here. The remaining <em>client</em> render-snap on
 * resume (a Sable interpolation issue) is a separate follow-up. Scoped to the integrated
 * server via {@link MinecraftServer#isDedicatedServer()} (a dedicated server never pauses),
 * so it is inert on real server lag; a false positive only <em>holds</em> force-loads briefly.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ResumeWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Idle wall-clock gap (ms) between two server ticks that counts as a resume-from-pause. */
    private static final long RESUME_GAP_MS = 2_000L;

    /** Ticks to hold the force-load window after a resume while the rider re-anchors (~3 s). */
    private static final int RESUME_GRACE_TICKS = 60;

    /** nanoTime at the end of the previous server tick; {@code 0} before the first tick / after a stop. */
    private static long lastTickEndNanos = 0L;

    private ResumeWatchdog() {}

    /**
     * Pure core: is the idle gap from the previous tick's end ({@code prevTickEndNanos}) to this
     * tick's start ({@code tickStartNanos}) long enough to be a resume? {@code 0} = no baseline.
     */
    static boolean isResumeGap(long prevTickEndNanos, long tickStartNanos) {
        if (prevTickEndNanos == 0L) return false;
        return (tickStartNanos - prevTickEndNanos) / 1_000_000L >= RESUME_GAP_MS;
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (event.getServer().isDedicatedServer()) return; // only the integrated server pauses
        lastTickEndNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server.isDedicatedServer()) return;

        long now = System.nanoTime();
        long prev = lastTickEndNanos;
        if (!isResumeGap(prev, now)) return;          // normal cadence, or a stall's catch-up
        long gapMs = (now - prev) / 1_000_000L;

        // Nobody near a train (e.g. reading books in a portal room's Stat Room): there is no rider to
        // fling, so skip the hold and the force-loads it would cost on every resume.
        if (!TrainCarriageAppender.anyPlayerNearLoadedTrain(server)) {
            LOGGER.info("[DungeonTrain] Resume after {}ms pause — no player near a train, hold skipped", gapMs);
            return;
        }

        // Protect every train on this resume tick: grant the grace window and pin the at-risk
        // groups resident so the transient rider-fling can't cull (and regenerate) any carriage.
        // No per-player resolution is needed — the hold self-drains once riders re-anchor. This is
        // the same hold the save guard applies (MinecraftServerSaveMixin) — shared in
        // TrainCarriageAppender#holdAllLoadedTrains.
        int held = TrainCarriageAppender.holdAllLoadedTrains(server, RESUME_GRACE_TICKS);
        if (held > 0) {
            LOGGER.info("[DungeonTrain] Resume after {}ms pause — held {} train(s) resident through the resume fling (#547)",
                    gapMs, held);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastTickEndNanos = 0L; // next world's first tick mustn't read a stale (huge) gap
    }
}
