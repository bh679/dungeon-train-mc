package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <em>wall-clock</em> gap between consecutive server ticks. On a detected resume, for every
 * loaded train it:</p>
 * <ul>
 *   <li>{@link TrainCarriageAppender#grantResumeGrace} — suppresses the walk-away
 *       force-load release for a short (renewing, capped) window while the flung-off rider
 *       re-anchors, so the transient "not near" can't drop the train; and</li>
 *   <li>{@link TrainCarriageAppender#holdWholeTrainForResume} — pins <em>every</em> carriage
 *       resident (not just the trailing-N) for that window, removing the dependency on Sable
 *       proximity residency that evaporates while the rider is absent.</li>
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

    /** Wall-clock gap (ms) between consecutive server ticks that counts as a resume-from-pause. */
    private static final long RESUME_GAP_MS = 2_000L;

    /** Ticks to hold the force-load window after a resume while the rider re-anchors (~3 s). */
    private static final int RESUME_GRACE_TICKS = 60;

    /** nanoTime of the previous server tick; {@code 0} before the first tick / after a stop. */
    private static long lastTickNanos = 0L;

    private ResumeWatchdog() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        // Run once per SERVER tick by gating on the overworld server level (mirrors
        // PlayerJoinEvents.onLevelTick). Client levels never match ServerLevel.
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        if (server.isDedicatedServer()) return; // only the integrated server pauses

        long now = System.nanoTime();
        long prev = lastTickNanos;
        lastTickNanos = now;
        if (prev == 0L) return;                       // first tick after start — no baseline
        long gapMs = (now - prev) / 1_000_000L;
        if (gapMs < RESUME_GAP_MS) return;            // normal cadence — not a resume

        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) return;
        StartingDimension startingDim = data.startingDimension();
        ServerLevel trainLevel = server.getLevel(startingDim.levelKey());
        if (trainLevel == null) return;

        Map<UUID, List<Trains.Carriage>> trains = Trains.byTrainId(trainLevel);
        if (trains.isEmpty()) return;

        // Protect every train on this resume tick: grant the grace window and pin the whole
        // train resident so the transient rider-fling can't cull (and regenerate) any carriage.
        // No per-player resolution is needed — the hold is independent of where riders are,
        // and it self-drains once they re-anchor.
        long gameTick = trainLevel.getGameTime();
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trains.entrySet()) {
            UUID trainId = entry.getKey();
            TrainCarriageAppender.grantResumeGrace(trainId, gameTick, RESUME_GRACE_TICKS);
            TrainCarriageAppender.holdWholeTrainForResume(trainLevel, trainId, entry.getValue());
        }

        LOGGER.info("[DungeonTrain] Resume after {}ms pause — held {} train(s) resident through the resume fling (#547)",
                gapMs, trains.size());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastTickNanos = 0L; // next world's first tick mustn't read a stale (huge) gap
    }
}
