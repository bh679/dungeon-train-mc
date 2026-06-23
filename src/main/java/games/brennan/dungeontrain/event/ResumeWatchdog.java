package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * Keeps riders aboard the train across a singleplayer pause→resume (#547).
 *
 * <p>In singleplayer the Esc/pause menu (and focus loss) freezes the integrated server's
 * tick loop. While frozen, Sable's collision-carry stops; on resume the train resumes
 * sliding forward (+X) and the stationary rider is left behind (observed ~150 blocks in a
 * few seconds), at which point {@link TrainCarriageAppender}'s "player left vicinity" bail
 * releases the force-loads and Sable culls the carriages — the train visibly disappears.</p>
 *
 * <p>A pause is invisible to {@code getGameTime()} (it doesn't advance while frozen), so this
 * watchdog detects resume by the only signal that survives a freeze: a multi-second
 * <em>wall-clock</em> gap between consecutive server ticks. On a detected resume, for every
 * player still on a train:</p>
 * <ol>
 *   <li><b>Layer A</b> — {@link TrainCarriageAppender#grantResumeGrace}: hold that train's
 *       force-loads for a short window so it can't be culled mid-re-anchor.</li>
 *   <li><b>Layer B</b> — re-teleport the player onto the train's current deck and fire
 *       {@link SpawnDeckHoldPacket}, reusing the exact spawn/respawn-on-deck path
 *       ({@link RespawnDimensionEvents}): the teleport closes the horizontal gap and the
 *       client deck-hold bridges the vertical settle until Sable carry re-engages.</li>
 * </ol>
 *
 * <p>Scoped to the integrated server via {@link MinecraftServer#isDedicatedServer()} (a
 * dedicated server never pauses), so it is inert on real server lag. Both layers are benign
 * on a false positive: Layer A only <em>holds</em> force-loads, and Layer B only re-snaps
 * players already standing on the train.</p>
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

        // Frontmost settled deck — same target spawn/respawn use. May be null if the
        // train hasn't bound yet, in which case Layer A's grace alone protects it.
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findFlatbedTarget(trainLevel, data);
        long gameTick = trainLevel.getGameTime();
        int reanchored = 0;
        for (ServerPlayer player : trainLevel.players()) {
            UUID trainId = Trains.trainIdContaining(trains, player);
            if (trainId == null) continue;            // not on a train — leave them be

            // Layer A: hold this train's force-loads through the re-anchor window.
            TrainCarriageAppender.grantResumeGrace(trainId, gameTick, RESUME_GRACE_TICKS);

            // Layer B: re-place onto the (now-advanced) deck + client deck-hold. Yaw -90
            // faces +X (travel direction), matching the spawn/respawn-on-deck pose.
            if (flat == null) continue;
            player.teleportTo(trainLevel, flat.x(), flat.y(), flat.z(), -90.0f, 0.0f);
            DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
                    data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
            reanchored++;
        }

        LOGGER.info("[DungeonTrain] Resume after {}ms pause — held {} train(s), re-anchored {} on-train player(s) (#547)",
                gapMs, trains.size(), reanchored);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastTickNanos = 0L; // next world's first tick mustn't read a stale (huge) gap
    }
}
