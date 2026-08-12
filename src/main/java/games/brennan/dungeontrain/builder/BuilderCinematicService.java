package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.BuilderCinematicPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side trigger for the Train Builder's arrival shot — the builder's counterpart to
 * {@link games.brennan.dungeontrain.event.CinematicIntroService}, and deliberately a lot smaller
 * than it.
 *
 * <p>The join intro has to wait for a moving train to settle, hold a loading screen over the gap
 * and shield the player while they can't act. None of that applies here: a builder world is a
 * fixed platform in creative mode, so the whole job is "work out where the template is, send the
 * camera move".</p>
 *
 * <p>Two entry points, because the shot has to fire on <em>every</em> load and the world is in a
 * different state each time:</p>
 * <ul>
 *   <li>{@link #playNow} — the fresh-creation path. {@code BuilderSetupPacket} has just stamped
 *       the world, so the template exists this instant and the player has just been stood in
 *       front of it.</li>
 *   <li>{@link #queue} — the reopen path. At login the world is already stamped, but the client
 *       has barely arrived; the shot is held for {@link #JOIN_DELAY_TICKS} so it doesn't open on
 *       a half-rendered world.</li>
 * </ul>
 */
public final class BuilderCinematicService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How long after login the reopen shot waits. Long enough for the client to have the
     * platform and carriages meshed (the world is tiny and loads in about two seconds), short
     * enough that it still reads as part of arriving.
     */
    private static final int JOIN_DELAY_TICKS = 30;

    /** Player UUID → server ticks remaining before their queued shot fires. */
    private static final Map<UUID, Integer> PENDING = new ConcurrentHashMap<>();

    private BuilderCinematicService() {}

    /** True in a {@code dungeontrain:builder} world — the only place this shot ever plays. */
    public static boolean isBuilderLevel(ServerLevel level) {
        return level != null
                && level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
    }

    /**
     * Queue the shot for a player who just logged into an already-stamped builder world.
     * No-op outside a builder world, or in one that hasn't been stamped yet — on a freshly
     * created world the setup packet owns the trigger, and firing here as well would play the
     * shot over a world that is still void.
     */
    public static void queueOnJoin(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!isBuilderLevel(level)) return;
        if (DungeonTrainWorldData.get(level).builderMode() == null) {
            return; // not stamped yet — BuilderSetupPacket will play it once it is
        }
        queue(player, JOIN_DELAY_TICKS);
    }

    /** Fire {@code delayTicks} from now. */
    public static void queue(ServerPlayer player, int delayTicks) {
        PENDING.put(player.getUUID(), Math.max(1, delayTicks));
    }

    /**
     * Send the camera move for {@code player} straight away.
     *
     * <p>Silently does nothing when the player has cinematics turned off ({@code
     * introCinematicEnabled}) — one switch covers both shots, so a player who doesn't want the
     * join intro doesn't get this one either.</p>
     */
    public static void playNow(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!isBuilderLevel(level)) return;
        if (!DungeonTrainConfig.isIntroCinematicEnabled()) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        int carriages = carriageCount(data);

        // The volumes rather than the carriage count: an open portal room is a build volume no
        // carriage count describes, and framing the rails instead would aim at empty void.
        Vec3 focus = BuilderCinematic.focus(BuilderBounds.volumesFor(level), dims);
        Vec3 eye = BuilderCinematic.eyeOf(player.getX(), player.getY(), player.getZ());
        Vec3 camStart = BuilderCinematic.cameraStart(focus, eye, carriages, dims);

        DungeonTrainNet.sendTo(player, new BuilderCinematicPacket(
                camStart.x, camStart.y, camStart.z,
                eye.x, eye.y, eye.z,
                focus.x, focus.y, focus.z,
                DungeonTrainConfig.getIntroCinematicDurationTicks()));

        LOGGER.info("[DungeonTrain] Builder cinematic for {}: focus=({}, {}, {}) carriages={} camStart=({}, {}, {})",
                player.getName().getString(),
                String.format("%.1f", focus.x), String.format("%.1f", focus.y), String.format("%.1f", focus.z),
                carriages,
                String.format("%.1f", camStart.x), String.format("%.1f", camStart.y),
                String.format("%.1f", camStart.z));
    }

    /**
     * The rotation that leaves a player facing the template from where they stand. Used for the
     * spawn teleport so the builder is looking at their build the moment control returns.
     */
    public static float[] facingFrom(ServerLevel level, double x, double y, double z) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        Vec3 focus = BuilderCinematic.focus(BuilderBounds.volumesFor(level), data.dims());
        return BuilderCinematic.faceYawPitch(BuilderCinematic.eyeOf(x, y, z), focus);
    }

    /** Drain the queue — hooked into the overworld tick alongside the other per-tick services. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        PENDING.entrySet().removeIf(entry -> {
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                playNow(player);
            }
            return true;
        });
    }

    /** Drop tracking on logout. */
    public static void forget(UUID playerId) {
        PENDING.remove(playerId);
    }

    /**
     * How many carriages this world parked. Read back from the persisted mode rather than
     * recomputed, so a reopened world frames the same template it was stamped with. Zero before
     * the world has been stamped, which is also the right answer for the two carriage-less
     * modes.
     */
    public static int carriagesIn(ServerLevel level) {
        return carriageCount(DungeonTrainWorldData.get(level));
    }

    private static int carriageCount(DungeonTrainWorldData data) {
        return BuilderMode.fromId(data.builderMode())
                .map(BuilderMode::carriageCount)
                .orElse(0);
    }
}
